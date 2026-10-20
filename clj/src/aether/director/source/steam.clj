(ns aether.director.source.steam
  "Steam depots (aether-vfs's aether-steam): depot keys and manifests cached
  on disk, random-access reads of depot files from the Steam CDN, and a
  provider. The protocol side (logons, keys, request codes, servers, tokens)
  is aether.director.source.steam.cm; formats are ...steam.codec.

  The CDN: a pool per app, sticky on its current host, hosts ranked by
  time to first byte (an EWMA, seeded by TCP-connect probes), a failing host
  cooled down exponentially while requests go elsewhere, a 403 answered by
  one fresh CDN token per host. A chunk is fetched once however many readers
  want it, kept decoded in an LRU (64 MiB), and copied straight into the
  reader's buffer.

  On disk (the cache directory): depot keys (32 bytes, owner-only),
  manifests (their CDN bodies), CDN server lists (24 h). A cached manifest
  needs no session; a session is opened only for what isn't cached. The
  login (refresh token) is the caller's file; see save-login!."
  (:require [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [aether.director.source.net :as net]
            [aether.director.source.steam.cm :as cm]
            [aether.director.source.steam.codec :as codec]
            [aether.director.source.tree :as tree]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (aether.director.provider Stat)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.net InetSocketAddress Socket)
           (java.net.http HttpResponse)
           (java.nio.charset StandardCharsets)
           (java.util Base64 LinkedHashMap)
           (java.util.concurrent ConcurrentHashMap Semaphore)
           (java.util.concurrent.atomic AtomicLong)))

(set! *warn-on-reflection* true)

(def skyrim-se 489830)
(def ^:const cdn-server-max-age-ms (* 24 60 60 1000))
(def ^:const max-manifest-body (* 64 1024 1024))
(def ^:const max-read (* 32 1024 1024))

(def cdn-defaults
  {:request-timeout-ms 60000 :max-attempts 8 :cooldown-base-ms 1000 :cooldown-max-ms 30000
   :max-concurrent-chunks 16 :chunk-cache-bytes (* 64 1024 1024) :probe-timeout-ms 2000})

(defn- now ^long [] (System/currentTimeMillis))

;; --- the login file -----------------------------------------------------------------------------

(defn- write-private! [f ^bytes data] (net/write-file! f data {:private? true}))

(defn save-login!
  "Saves a login ({:account :refresh-token :guard-data}) to `file`, owner-only."
  [file {:keys [account refresh-token guard-data]}]
  (let [d (cond-> {"account_name" account "refresh_token" refresh-token}
            guard-data (assoc "guard_data" guard-data))]
    (write-private! file (.getBytes ^String (json/write-json-str d :indent-str "  ") StandardCharsets/UTF_8))))

(defn token-expires-at
  "The refresh token's expiry (epoch s) from its JWT, or nil if opaque."
  [^String token]
  (try (let [[_ body] (str/split token #"\.")]
         (get (json/read-json (String. (.decode (Base64/getUrlDecoder) ^String body) StandardCharsets/UTF_8)) "exp"))
       (catch Exception _ nil)))

(defn load-login
  "The saved login in `file`, or nil."
  [file]
  (let [f (io/file file)]
    (when (.exists f)
      (let [d (try (json/read-json (slurp f))
                   (catch Exception _ (throw (net/error :login (str f " is not a Steam login file; log in to Steam again")))))]
        {:account (get d "account_name") :refresh-token (get d "refresh_token") :guard-data (get d "guard_data")}))))

;; --- the CDN pool ----------------------------------------------------------------------------------

(defn- probe! [pool {:keys [host port] :as srv} timeout-ms]
  (Thread/startVirtualThread
   ^Runnable (fn []
               (let [t0 (System/nanoTime)
                     ok (try (with-open [s (Socket.)] (.connect s (InetSocketAddress. ^String host (int port)) (int timeout-ms)) true)
                             (catch Exception _ false))
                     dt (- (System/nanoTime) t0)]
                 (swap! (:hosts pool) update srv
                        (fn [h] (if (:measured h) h (assoc h :latency (if ok dt Long/MAX_VALUE) :probed true))))))))

(defn pool
  "A pool over `servers` ([{:host :port :https}], best first)."
  [servers & [cfg]]
  (let [cfg (merge cdn-defaults cfg)
        p {:cfg cfg :servers (vec servers) :hosts (atom (into {} (for [s servers] [s {}]))) :current (atom (first servers))}]
    (doseq [s servers] (probe! p s (:probe-timeout-ms cfg)))
    p))

(defn- latency ^long [h] (long (or (:latency h) (quot Long/MAX_VALUE 2))))

(defn- pick
  "The host to try: the current one while it's healthy, unless another is
  clearly (2x) faster; a cooled-down host gets one trial at a time."
  [{:keys [servers hosts current]}]
  (let [t (now)
        hs @hosts
        open (filter (fn [s] (let [h (hs s)] (and (<= (long (or (:cooldown-until h) 0)) t)
                                                  (or (zero? (long (or (:failures h) 0))) (<= (long (or (:trial-until h) 0)) t)))))
                     servers)]
    (if (empty? open)
      {:wait (- (long (apply min (map #(long (or (:cooldown-until (hs %)) 0)) servers))) t)}
      (let [best (apply min-key #(latency (hs %)) open)
            cur @current]
        (if (and cur (some #{cur} open) (zero? (long (or (:failures (hs cur)) 0)))
                 (not (< (* 2 (latency (hs best))) (latency (hs cur)))))
          {:host cur}
          (do (reset! current best) {:host best}))))))

(defn- success! [{:keys [hosts]} srv ttfb-ns]
  (swap! hosts update srv (fn [h] (-> h (dissoc :failures :cooldown-until :trial-until)
                                      (assoc :measured true
                                             :latency (if (:measured h) (+ (quot (* 3 (latency h)) 4) (quot (long ttfb-ns) 4)) ttfb-ns))))))

(defn- failure! [{:keys [hosts cfg]} srv retry-after-ms]
  (swap! hosts update srv
         (fn [h] (let [f (inc (long (or (:failures h) 0)))
                       cool (min (long (:cooldown-max-ms cfg))
                                 (long (or retry-after-ms (* (long (:cooldown-base-ms cfg)) (bit-shift-left 1 (min 16 (dec f)))))))]
                   (assoc h :failures f :cooldown-until (+ (now) cool) :trial-until (+ (now) cool (long (:request-timeout-ms cfg))))))))

(defn- url-for [{:keys [host port https]} path token]
  (str (if https "https" "http") "://" host ":" port path (when-not (str/blank? token) (str "?" token))))

(defn fetch!
  "GETs `path` from the pool's hosts and returns (decode body), failing over
  on errors, bad bodies and timeouts; `token-fn` (host -> token or nil)
  answers a host's first 401/403. Errors never hold a URL."
  [h {:keys [cfg] :as pool} path max-body decode what token-fn ^ConcurrentHashMap tokens]
  (loop [attempt 1 last-error nil refreshed #{}]
    (if (> attempt (long (:max-attempts cfg)))
      (throw (net/error :cdn (str what ": gave up after " (:max-attempts cfg) " attempts; last error: " last-error)))
      (let [{srv :host wait :wait} (pick pool)]
        (if wait
          (do (Thread/sleep (max 1 (long wait))) (recur attempt last-error refreshed))
          (let [host (:host srv)
                url (url-for srv path (.get tokens host))
                t0 (System/nanoTime)
                r (try
                    (let [^HttpResponse resp (net/send! h url {:api? true :timeout-ms (:request-timeout-ms cfg)})
                          st (.statusCode resp)]
                      (cond
                        (= 200 st) (let [body (net/read-body h resp url {:max max-body})
                                         v (decode body)]
                                     (success! pool srv (- (System/nanoTime) t0))
                                     {:ok v})
                        (#{401 403} st) (do (net/close-body resp)
                                            (if-let [t (and token-fn (not (contains? refreshed host)) (token-fn host))]
                                              (do (.put tokens host t) {:retry-host host})
                                              (do (failure! pool srv nil) {:error (str host ": HTTP " st)})))
                        :else (let [ra (some-> (net/header resp "Retry-After") str/trim parse-long (* 1000))]
                                (net/close-body resp)
                                (failure! pool srv ra)
                                {:error (str host ": HTTP " st)})))
                    (catch clojure.lang.ExceptionInfo e
                      (failure! pool srv nil)
                      {:error (str host ": " (first (str/split (str (ex-message e)) #"https?://")))})
                    (catch Exception e
                      (failure! pool srv nil)
                      {:error (str host ": " (.getSimpleName (class e)))}))]
            (cond
              (contains? r :ok) (:ok r)
              (:retry-host r) (recur (inc attempt) last-error (conj refreshed (:retry-host r)))
              :else (recur (inc attempt) (:error r) refreshed))))))))

;; --- depot readers ------------------------------------------------------------------------------

(defn reader
  "Reads depot `depot` (of `app`, key `key`) through `pool`. `token-fn`
  (host -> CDN token or nil) answers 403s."
  [h pool app depot ^bytes key & [token-fn]]
  {:h h :pool pool :app app :depot depot :key key :token-fn token-fn :tokens (ConcurrentHashMap.)
   :sem (Semaphore. (int (:max-concurrent-chunks (:cfg pool))))
   :lru (LinkedHashMap. 64 0.75 true) :lru-bytes (AtomicLong.) :inflight (ConcurrentHashMap.)})

(defn fetch-manifest
  "Manifest `gid` of the reader's depot, parsed (and its raw CDN body)."
  [{:keys [h pool depot key token-fn tokens]} gid request-code]
  (let [path (str "/depot/" depot "/manifest/" (Long/toUnsignedString (long gid)) "/5" (when-not (zero? (long request-code)) (str "/" (Long/toUnsignedString (long request-code)))))
        body (fetch! h pool path max-manifest-body identity (str "steam " depot " manifest " gid) token-fn tokens)]
    [(codec/parse-manifest body depot gid key) body]))

(defn- chunk!
  "A decoded chunk: from the LRU, a fetch in flight, or the CDN."
  ^bytes [{:keys [h pool depot key token-fn tokens ^Semaphore sem ^LinkedHashMap lru lru-bytes inflight]} c]
  (let [k (codec/hex (:id c))
        budget (long (:chunk-cache-bytes (:cfg pool)))]
    (or (locking lru (.get lru k))
        (net/single-flight inflight k false
          (fn []
            (.acquire sem)
            (let [^bytes plain (try (fetch! h pool (str "/depot/" depot "/chunk/" k) (+ (* 2 (long (:len c))) 65536)
                                            #(codec/decode-chunk key c %) (str "steam " depot " " k) token-fn tokens)
                                    (finally (.release sem)))]
              (when (<= (alength plain) budget)
                (net/lru-put! lru lru-bytes k plain budget))
              plain))))))

(defn read-file!
  "Reads up to `len` bytes of `file` (from the manifest) at `pos` into
  `dst` at `dst-off`; returns the count (short only at EOF)."
  [rd file pos len ^MemorySegment dst dst-off]
  (let [pos (long pos) size (long (:size file))]
    (if (or (>= pos size) (zero? (long len)))
      0
      (let [n (min (- size pos) (long len))
            end (+ pos n)
            [lo hi] (codec/chunks-for file pos n)]
        (net/ordered-parallel (:max-concurrent-chunks (:cfg (:pool rd)))
                              (fn [i] (chunk! rd (nth (:chunks file) i)))
                              (fn [i ^bytes plain]
                                (let [{:keys [offset]} (nth (:chunks file) i)
                                      cs (long offset) s (max pos cs) e (min end (+ cs (alength plain)))]
                                  (MemorySegment/copy plain (int (- s cs)) dst ValueLayout/JAVA_BYTE (+ (long dst-off) (- s pos)) (int (- e s)))))
                              (range lo hi))
        n))))

;; --- content: caches and the session ------------------------------------------------------------------

(defn content
  "Steam content in `cache-dir`, logged in as the login saved in
  `login-file` (or anonymous when there's none). Options: :login (a login
  map, instead of the file), :cdn (pool settings), :session (cm settings),
  :session-fn (tests)."
  [h cache-dir login-file & [{:keys [cdn session session-fn] :as opts}]]
  (let [login (or (:login opts) (some-> login-file load-login))]
    (when-let [exp (some-> login :refresh-token token-expires-at)]
      (when (<= (long exp) (quot (now) 1000))
        (throw (net/error :login-expired (str "the Steam login for " (:account login) " has expired; log in to Steam again")))))
    {:h h :dir (io/file cache-dir) :login login :cdn-cfg cdn
     :session (delay ((or session-fn #(cm/session % session)) login))
     :anonymous (delay ((or session-fn #(cm/session % session)) nil))
     :pools (ConcurrentHashMap.) :keys (ConcurrentHashMap.)}))

(defn close [{:keys [session anonymous]}]
  (doseq [d [session anonymous] :when (realized? d)] (try (cm/close @d) (catch Exception _))))

(defn depot-key
  "Depot `depot`'s key: cached on disk, else from the session."
  ^bytes [{:keys [dir session] ^ConcurrentHashMap depot-keys :keys} app depot]
  (or (.get depot-keys depot)
      (let [f (io/file dir "depot-keys" (str depot ".key"))
            k (or (when (and (.exists f) (= 32 (.length f))) (java.nio.file.Files/readAllBytes (.toPath f)))
                  (let [k (cm/depot-key @session app depot)]
                    (try (write-private! f k) (catch Exception _))
                    k))]
        (.put depot-keys depot k)
        k)))

(defn cdn-servers
  "`app`'s content servers: the cached list (24 h), else live (logged in or
  anonymous), else a stale list of any age."
  [{:keys [dir login session anonymous]} app]
  (let [f (io/file dir (str "cdn-servers-" app ".json"))
        cached (when (.exists f) (try (json/read-json (slurp f) :key-fn keyword) (catch Exception _ nil)))
        fresh? (and cached (< (- (now) (* 1000 (long (:fetched_at cached)))) cdn-server-max-age-ms))]
    (if fresh?
      (vec (:servers cached))
      (let [live (try (let [s (if login (try (cm/cdn-servers @session app)
                                             (catch clojure.lang.ExceptionInfo e
                                               (if (#{:login-expired :not-logged-in} (net/kind e)) (cm/cdn-servers @anonymous app) (throw e))))
                                  (cm/cdn-servers @anonymous app))]
                        (when (empty? s) (throw (net/error :cdn "Steam listed no content servers")))
                        s)
                      (catch Exception e (when-not cached (throw e))))]
        (if live
          (do (.mkdirs ^java.io.File dir)
              (spit f (json/write-json-str {"fetched_at" (quot (now) 1000) "servers" live}))
              live)
          (vec (:servers cached)))))))

(defn- pool-for [{:keys [^ConcurrentHashMap pools cdn-cfg] :as c} app]
  (.computeIfAbsent pools app (fn [_] (pool (cdn-servers c app) cdn-cfg))))

(defn depot-reader
  "A reader for `depot` of `app`."
  [{:keys [h session] :as c} app depot]
  (reader h (pool-for c app) app depot (depot-key c app depot)
          (fn [host] (try (cm/cdn-token @session app depot host) (catch Exception _ nil)))))

(defn manifest
  "Manifest `gid` of `depot`: the cached body, else fetched (with a request code)."
  [{:keys [dir session] ^ConcurrentHashMap depot-keys :keys :as c} app depot gid]
  (let [f (io/file dir "manifests" (str depot "_" (Long/toUnsignedString (long gid)) ".bin"))
        k (depot-key c app depot)]
    (or (when (.exists f)
          (try (codec/parse-manifest (java.nio.file.Files/readAllBytes (.toPath f)) depot gid k)
               (catch clojure.lang.ExceptionInfo e
                 (when (str/includes? (str (ex-message e)) "file names:")
                   ;; a bad cached key: forget it, and say so
                   (.remove depot-keys depot)
                   (.delete (io/file dir "depot-keys" (str depot ".key")))
                   (throw (net/error :integrity (str "depot " depot "'s cached key can't decrypt its manifest; it was dropped")))))))
        (let [code (cm/manifest-request-code @session app depot gid)
              [m body] (fetch-manifest (depot-reader c app depot) gid code)]
          (net/write-file! f body)
          m))))

;; --- the provider ------------------------------------------------------------------------------------

(deftype DepotHandle [rd file ^long size]
  prov/Handle
  (handle-stat [_] (Stat. false size tree/mtime))
  (immutable? [_] true)
  (read-into [_ dst dst-off pos len]
    (try (read-file! rd file pos (min (long len) max-read) dst dst-off)
         (catch Exception _ p/io-error)))
  (write-from [_ _ _ _ _] p/read-only)
  (truncate! [_ _] p/read-only)
  (close! [_] nil))

(defn provider
  "`app`'s depots ([[depot gid] …], earlier wins) as a read-only provider.
  Manifests are loaded now (from the cache when there); content is fetched
  only when read. Best behind storage/cached (1 MiB units: a Steam chunk)."
  [c app depots]
  (let [readers (into {} (for [[depot] depots] [depot (delay (depot-reader c app depot))]))]
    (tree/provider (for [[depot gid] depots
                         file (:files (manifest c app depot gid))]
                     {:path (:path file) :size (:size file) :key [depot file]})
                   (fn [[depot file] size] (DepotHandle. @(readers depot) file size))
                   {:order :insertion})))
