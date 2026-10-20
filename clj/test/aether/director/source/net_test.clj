(ns aether.director.source.net-test
  "aether-net's and aether-wj-cdn's tests, against a fake file host."
  (:require [aether.director.source.archive-test :refer [lcg pattern]]
            [aether.director.source.fake-server :as fake :refer [with-server]]
            [aether.director.source.net :as net]
            [aether.director.source.wj-cdn :as cdn]
            [charred.api :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director Codec)
           (java.io ByteArrayOutputStream)
           (java.lang.foreign MemorySegment)
           (java.util Arrays)
           (java.util.concurrent CountDownLatch Executors TimeUnit)
           (java.util.concurrent.atomic AtomicLong)
           (java.util.zip GZIPOutputStream)))

(defn- http [& [opts]]
  (net/http (merge {:retry {:max-attempts 3 :base-delay-ms 1 :max-delay-ms 10 :max-retry-after-ms 2000}
                    :chunk-size (* 64 1024) :parallel-parts 4}
                   opts)))

(defmacro ^:private failure [form]
  `(try ~form nil (catch clojure.lang.ExceptionInfo e# {:kind (net/kind e#) :msg (ex-message e#)})))

(defn- xxh [^bytes b] (Codec/xxh64 (MemorySegment/ofArray b) 0))

;; --- policy ---------------------------------------------------------------------------------

(deftest backoff-doubles-and-caps
  (let [p (:retry (net/http))]
    (is (= 500 (net/delay-ms p 1 (ex-info "x" {}))))
    (is (= 2000 (net/delay-ms p 3 (ex-info "x" {}))))
    (is (= 30000 (net/delay-ms p 40 (ex-info "x" {}))))
    (is (= 7000 (net/delay-ms p 1 (net/error :rate-limited "x" {:retry-after-ms 7000}))))))

(deftest retryable-classes
  (is (net/retryable? (net/error :status "x" {:status 503 :body ""})))
  (is (not (net/retryable? (net/error :status "x" {:status 500 :body "error code: 1101"}))))
  (is (not (net/retryable? (net/error :not-found "x"))))
  (is (not (net/retryable? (net/error :unauthorized "x"))))
  (is (net/retryable? (net/error :rate-limited "x")))
  (is (= "https://h.example/p" (net/redact "https://h.example:8443/p?sig=SECRET#frag"))))

(deftest the-limiter-holds-global-and-per-host-bounds
  (let [l (net/limiter 3 2)
        peak (AtomicLong.) now (AtomicLong.) per (java.util.concurrent.ConcurrentHashMap.)
        ex (Executors/newVirtualThreadPerTaskExecutor)]
    (dotimes [i 30]
      (.submit ex ^Runnable
               (fn []
                 (let [host (str "h" (rem i 3))]
                   (with-open [_ (net/acquire! l host)]
                     (let [n (.incrementAndGet now)
                           ^AtomicLong h (.computeIfAbsent per host (reify java.util.function.Function (apply [_ _] (AtomicLong.))))]
                       (.accumulateAndGet peak n (reify java.util.function.LongBinaryOperator (applyAsLong [_ a b] (max a b))))
                       (when (> (.incrementAndGet h) 2) (throw (ex-info "per-host bound" {})))
                       (Thread/sleep 5)
                       (.decrementAndGet h)
                       (.decrementAndGet now)))))))
    (.shutdown ex) (.awaitTermination ex 30 TimeUnit/SECONDS)
    (is (= 3 (.get peak)))
    (testing "host names are case-insensitive"
      (let [l (net/limiter 10 1) p (net/acquire! l "Host")
            got (future (with-open [_ (net/acquire! l "host")] :got))]
        (is (= :timeout (deref got 100 :timeout)))
        (.close p)
        (is (= :got (deref got 5000 :timeout)))))))

;; --- requests --------------------------------------------------------------------------------

(deftest get-bytes
  (with-server [s]
    (let [h (http)]
      (fake/put! s "/doc" (.getBytes "hello"))
      (testing "the body, after a transient error"
        (swap! (:state s) assoc :script [[503 {} "busy"]])
        (is (= "hello" (String. (net/get-bytes h (str (:url s) "/doc") 100))))
        (is (= 2 (count (fake/requests s "/doc")))))
      (testing "redirects are followed by the plain client, never by the API one"
        (swap! (:state s) assoc-in [:redirects "/moved"] "/doc")
        (is (= "hello" (String. (net/get-bytes h (str (:url s) "/moved") 100))))
        (is (= :status (:kind (failure (net/get-bytes h (str (:url s) "/moved") 100 {:api? true}))))))
      (testing "oversized bodies, missing files and bad URLs; no query shown"
        (is (= :protocol (:kind (failure (net/get-bytes h (str (:url s) "/doc") 3)))))
        (let [f (failure (net/get-bytes h (str (:url s) "/missing?key=SECRET") 100))]
          (is (= :not-found (:kind f)))
          (is (not (str/includes? (:msg f) "SECRET"))))
        (is (not (str/includes? (:msg (failure (net/get-bytes h "ht tp://x/?key=SECRET" 100))) "SECRET"))))
      (testing "an endless error body isn't read to the end"
        (swap! (:state s) update :endless-error conj "/endless")
        (let [t0 (System/nanoTime)
              f (failure (net/get-bytes h (str (:url s) "/endless") 100))]
          (is (= :status (:kind f)))
          (is (<= (count (re-find #"x+" (:msg f))) 300))
          (is (< (- (System/nanoTime) t0) 10e9)))))))

(deftest ranged-requests
  (with-server [s]
    (let [h (http) data (lcg 1000) url (str (:url s) "/f")]
      (fake/put! s "/f" data)
      (is (Arrays/equals (Arrays/copyOfRange data 100 200) (net/get-range h url 100 200 1000 nil)))
      (is (= :archive-changed (:kind (failure (net/get-range h url 100 200 999 nil)))))
      (let [[b total] (net/get-suffix h url 64 nil)]
        (is (= 1000 total))
        (is (Arrays/equals (Arrays/copyOfRange data 936 1000) b)))
      (is (= 1000 (net/range-len h url nil)))
      (fake/put! s "/empty" (byte-array 0))
      (swap! (:state s) update :no-range conj "/plain")
      (fake/put! s "/plain" data)
      (is (nil? (net/range-len h (str (:url s) "/plain") nil)) "a server that ignores Range")
      (is (= :protocol (:kind (failure (net/get-suffix h (str (:url s) "/plain") 64 nil))))))))

(deftest whole-file-downloads
  (with-server [s]
    (let [h (http) data (lcg (+ (* 1024 1024) 17)) url (str (:url s) "/big")]
      (fake/put! s "/big" data)
      (testing "ranged servers: one probe and parallel 64 KiB ranges, hashed"
        (let [sink (net/memory-sink)
              r (net/download! h url sink {:expected-hash (xxh data) :expected-len (alength data)})]
          (is (= (alength data) (:len r)))
          (is (Arrays/equals data ^bytes @sink))
          (is (= 18 (count (fake/requests s "/big"))))
          (is (every? :range (fake/requests s "/big")))))
      (testing "a server without Range gets one stream"
        (swap! (:state s) update :no-range conj "/plain")
        (fake/put! s "/plain" data)
        (fake/clear-log! s)
        (let [sink (net/memory-sink)]
          (net/download! h (str (:url s) "/plain") sink)
          (is (Arrays/equals data ^bytes @sink))
          (is (= 2 (count (fake/requests s "/plain"))))))
      (testing "a hash mismatch and a missing file fail"
        (is (= :hash-mismatch (:kind (failure (net/download! h url (net/memory-sink) {:expected-hash 42})))))
        (is (= :not-found (:kind (failure (net/download! h (str (:url s) "/nope") (net/memory-sink)))))))
      (testing "an expected length is checked before any data is fetched"
        (fake/clear-log! s)
        (is (= :protocol (:kind (failure (net/download! h url (net/memory-sink) {:expected-len 5})))))
        (is (= 1 (count (fake/requests s "/big")))))
      (testing "empty files"
        (fake/put! s "/empty" (byte-array 0))
        (is (= (xxh (byte-array 0)) (:hash (net/download! h (str (:url s) "/empty") (net/memory-sink))))))
      (testing "events: started with the total, progress, finished"
        (let [events (atom [])
              h (http {:on-event #(swap! events conj %)})]
          (net/download! h url (net/memory-sink) {:expected-len (alength data)})
          (is (= (alength data) (:total (first (filter #(= :started (:event %)) @events)))))
          (is (>= (reduce + (keep :bytes @events)) (alength data)))
          (is (some #(= :finished (:event %)) @events)))))))

;; --- the Wabbajack CDN ----------------------------------------------------------------------

(deftest cdn-hosts-are-remapped
  (is (= "https://mirror.wabbajack.org/a%20b/c?x=1" (cdn/remap-url "https://Wabbajack-Mirror.b-cdn.net/a%20b/c?x=1")))
  (is (= "https://example.com/x" (cdn/remap-url "https://example.com/x"))))

(def part (* 64 1024))

(defn- cdn-fixture
  "Serves `data` as a CDN file at /files/f (6 parts); returns the definition map."
  [s ^bytes data & [{:keys [corrupt-part oversize]}]]
  (let [parts (vec (for [[i a] (map-indexed vector (range 0 (alength data) part))]
                     (let [p (Arrays/copyOfRange data (int a) (int (min (alength data) (+ a part))))]
                       {"Size" (alength p) "Offset" a "Hash" (net/xxh64-b64 (xxh p)) "Index" i :bytes p})))
        d {"OriginalFileName" "mod.7z" "Size" (if oversize (* 100 1024 1024) (alength data)) "Hash" (net/xxh64-b64 (xxh data))
           "Parts" (mapv #(dissoc % :bytes) (if oversize [{"Size" (* 100 1024 1024) "Offset" 0 "Hash" "AAAAAAAAAAA=" "Index" 0}] parts))}
        gz (let [o (ByteArrayOutputStream.)] (with-open [g (GZIPOutputStream. o)] (.write g (.getBytes ^String (json/write-json-str d) "UTF-8"))) (.toByteArray o))]
    (fake/put! s "/files/f/definition.json.gz" gz)
    (doseq [{i "Index" b :bytes} parts]
      (fake/put! s (str "/files/f/parts/" i) (if (= i corrupt-part) (doto (aclone ^bytes b) (aset-byte 0 (unchecked-byte 7))) b)))
    d))

(deftest cdn-downloads
  (with-server [s]
    (let [h (http) data (lcg (+ (* 5 part) 100))]
      (cdn-fixture s data)
      (testing "every part fetched and checked"
        (let [sink (net/memory-sink)
              d (cdn/download! (cdn/cdn-file h (str (:url s) "/files/f")) sink (xxh data))]
          (is (= "mod.7z" (:original-file-name d)))
          (is (Arrays/equals data ^bytes @sink))
          (is (= 6 (count (fake/requests s "/files/f/parts/"))))))
      (testing "a corrupt part is fetched again, then fails"
        (fake/clear-log! s)
        (cdn-fixture s data {:corrupt-part 1})
        (let [f (failure (cdn/download! (cdn/cdn-file h (str (:url s) "/files/f")) (net/memory-sink)))]
          (is (= :corrupt-part (:kind f)))
          (is (= 3 (count (fake/requests s "/files/f/parts/1"))))))
      (testing "the whole file must match the modlist's hash"
        (cdn-fixture s data)
        (is (= :hash-mismatch (:kind (failure (cdn/download! (cdn/cdn-file h (str (:url s) "/files/f")) (net/memory-sink) 12345))))))
      (testing "an oversized part is refused before any part is fetched"
        (fake/clear-log! s)
        (cdn-fixture s data {:oversize true})
        (is (= :protocol (:kind (failure (cdn/definition (cdn/cdn-file h (str (:url s) "/files/f")))))))
        (is (empty? (fake/requests s "/files/f/parts/"))))
      (testing "a query is sent but never shown"
        (cdn-fixture s data)
        (swap! (:state s) assoc-in [:require-query "/files/f/definition.json.gz"] "token=SECRET")
        (let [events (atom [])
              h (http {:on-event #(swap! events conj %)})
              f (cdn/cdn-file h (str (:url s) "/files/f?token=SECRET#frag"))]
          (is (= "mod.7z" (:original-file-name (cdn/definition f))))
          (is (not (str/includes? (pr-str (dissoc f :uri :h)) "SECRET")))
          (is (not (str/includes? (pr-str @events) "SECRET")))
          (let [miss (failure (cdn/definition (cdn/cdn-file h (str (:url s) "/files/none?token=SECRET"))))]
            (is (= :not-found (:kind miss)))
            (is (not (str/includes? (:msg miss) "SECRET")))))))))
