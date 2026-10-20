(ns aether.director.source.fake-server
  "A fake file host for the sources' tests (aether-net's fake server): files
  served with Range support, or without it, redirects, scripted responses,
  an endless error body, a request log."
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)))

(defn- respond [^HttpExchange ex status headers ^bytes body]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) k (str v)))
  (.sendResponseHeaders ex (int status) (if (zero? (alength body)) -1 (alength body)))
  (with-open [o (.getResponseBody ex)] (.write o body)))

(defn- serve-file [^HttpExchange ex state ^bytes data ranged?]
  (let [range (.getFirst (.getRequestHeaders ex) "Range")
        len (alength data)]
    (if (and range ranged?)
      (if-let [[_ n] (re-matches #"bytes=-(\d+)" range)]
        (let [n (min len (parse-long n)) a (- len n)]
          (respond ex 206 {"Content-Range" (str "bytes " a "-" (dec len) "/" len)} (java.util.Arrays/copyOfRange data (int a) (int len))))
        (let [[_ a b] (re-matches #"bytes=(\d+)-(\d+)" range)
              a (parse-long a) b (min (dec len) (parse-long b))]
          (if (>= a len)
            (respond ex 416 {"Content-Range" (str "bytes */" len)} (byte-array 0))
            (respond ex 206 {"Content-Range" (str "bytes " a "-" b "/" len)} (java.util.Arrays/copyOfRange data (int a) (int (inc b)))))))
      (respond ex 200 {} data))))

(defn start
  "A server: {:url base :state atom :stop fn}. State keys: :files {path bytes},
  :no-range #{path}, :redirects {path target-path}, :script [[status headers body] …]
  (served first, in order), :endless-error #{path}, :require-query {path query}, :log [req …]."
  []
  (let [state (atom {:files {} :no-range #{} :redirects {} :script [] :endless-error #{} :require-query {} :log []
                     :routes {}})
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server (Executors/newVirtualThreadPerTaskExecutor))
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (try
                          (let [path (.getPath (.getRequestURI ex))
                                query (.getRawQuery (.getRequestURI ex))
                                req {:method (.getRequestMethod ex) :path path :query query
                                     :range (.getFirst (.getRequestHeaders ex) "Range")
                                     :headers (into {} (map (fn [[k v]] [(.toLowerCase ^String k) (first v)])) (.getRequestHeaders ex))}
                                _ (swap! state update :log conj req)
                                s @state]
                            (cond
                              (seq (:script s))
                              (let [[st h b] (first (:script s))]
                                (swap! state update :script (comp vec rest))
                                (respond ex st h (if (string? b) (.getBytes ^String b "UTF-8") (or b (byte-array 0)))))
                              (some (fn [[re _]] (re-matches re path)) (:routes s))
                              (let [[_ f] (first (filter (fn [[re _]] (re-matches re path)) (:routes s)))
                                    body (.readAllBytes (.getRequestBody ex))
                                    [st h b] (f (assoc req :body body))]
                                (if (= :file st)
                                  (serve-file ex state b (not (contains? (:no-range s) path)))
                                  (respond ex st (or h {}) (if (string? b) (.getBytes ^String b "UTF-8") (or b (byte-array 0))))))
                              (contains? (:endless-error s) path)
                              (do (.sendResponseHeaders ex 400 0)
                                  (with-open [o (.getResponseBody ex)]
                                    (try (dotimes [_ 1000000] (.write o (.getBytes "xxxxxxxxxxxxxxxx")) (.flush o))
                                         (catch Exception _))))
                              (contains? (:redirects s) path)
                              (respond ex 302 {"Location" (str (get (:redirects s) path) (when query (str "?" query)))} (byte-array 0))
                              (and (contains? (:require-query s) path) (not= query (get (:require-query s) path)))
                              (respond ex 403 {} (.getBytes "Invalid signature"))
                              (contains? (:files s) path)
                              (serve-file ex state (get (:files s) path) (not (contains? (:no-range s) path)))
                              :else (respond ex 404 {} (.getBytes "not found"))))
                          (catch Exception _ (try (.close ex) (catch Exception _)))))))
    (.start server)
    {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
     :state state
     :stop #(.stop server 0)}))

(defn route!
  "Answers paths matching regex `re` with (f req) -> [status headers body],
  or [:file nil bytes] to serve bytes as a file (with Range)."
  [srv re f]
  (swap! (:state srv) update :routes assoc re f))

(defn put! [srv path ^bytes data] (swap! (:state srv) assoc-in [:files path] data))
(defn requests [srv prefix] (filter #(.startsWith ^String (:path %) prefix) (:log @(:state srv))))
(defn clear-log! [srv] (swap! (:state srv) assoc :log []))

(defmacro with-server [[s] & body]
  `(let [~s (start)] (try ~@body (finally ((:stop ~s))))))
