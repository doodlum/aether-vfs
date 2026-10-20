(ns aether.director.source.net
  "The HTTP base every source shares (aether-vfs's aether-net): one client
  that follows redirects and one that never does (for requests carrying
  credentials, which must not reach another host), a connection limiter
  (global and per host), a retry policy, events, ranged reads, and
  whole-file downloads hashed as they arrive.

  Blocking, on virtual threads: a call blocks its thread, and a caller that
  wants parallelism starts threads (download! and the archive readers do).

  Errors are ex-info with ::error (see `error`). No error message, label or
  event ever holds a URL's query: signed URLs and tokens live there (see
  `redact`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (aether.director Codec$Xxh64)
           (java.io InputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest
                          HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers
                          HttpTimeoutException)
           (java.nio ByteBuffer)
           (java.nio.channels FileChannel)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.time Duration)
           (java.util LinkedHashMap Locale Map$Entry)
           (java.util.concurrent CompletableFuture ConcurrentHashMap ExecutionException Executors
                                 ExecutorService Future ScheduledExecutorService Semaphore TimeUnit)
           (java.util.concurrent.atomic AtomicLong)
           (java.util.concurrent.locks Lock)))

(set! *warn-on-reflection* true)

(def defaults
  {:global-connections   64
   :per-host-connections 16
   :connect-timeout-ms   30000
   :read-timeout-ms      60000       ; the longest silence while reading a response
   :user-agent           "aether-director/0.1"
   :chunk-size           (* 8 1024 1024)
   :parallel-parts       8
   :retry {:max-attempts 5 :base-delay-ms 500 :max-delay-ms 30000 :max-retry-after-ms 300000}})

(def ^:const error-body-max 300)

;; --- errors -------------------------------------------------------------------------

(defn redact
  "A URL without its query, fragment or port: scheme://host/path."
  ^String [url]
  (try
    (let [u (URI. (str url))]
      (str (.getScheme u) "://" (.getHost u) (.getRawPath u)))
    (catch Exception _ (first (str/split (str url) #"[?#]" 2)))))

(defn error
  "An ex-info of `kind`. Kinds: :not-found :rate-limited :status :network
  :protocol :archive-changed :corrupt-part :hash-mismatch :checksum :invalid
  :unsupported :unauthorized :forbidden :refused :login :not-owned."
  [kind msg & [data]]
  (ex-info msg (assoc data ::error kind)))

(defn kind [e] (::error (ex-data e)))

(defn- unexpected [url what] (error :protocol (str "unexpected response from " (redact url) ": " what)))

(defn- network-error [url detail]
  (error :network (str "network error talking to " (redact url) ": " detail) {:url (redact url)}))

(defn retryable?
  "Transient: network, 429, a corrupt part or frame checksum, and 5xx (but a
  5xx saying \"error code: 1101\" is Nexus's answer to a range past EOF: ours)."
  [e]
  (let [{k ::error :keys [status body]} (ex-data e)]
    (case k
      (:network :rate-limited :corrupt-part :checksum) true
      :status (and (>= (long status) 500) (not (str/includes? (str body) "error code: 1101")))
      false)))

;; --- events ----------------------------------------------------------------------------

(def ^:private job-ids (AtomicLong.))

(defn- emit [h event] (when-let [f (:on-event h)] (try (f event) (catch Throwable _))))

(defn start-job
  "A job: events {:event :started|:progress|:retry|:finished|:failed, :job id ...}."
  [h label total]
  (let [id (.incrementAndGet ^AtomicLong job-ids)]
    (emit h {:event :started :job id :label label :total total})
    {:h h :id id}))

(defn progress! [{:keys [h id]} ^long n] (when (pos? n) (emit h {:event :progress :job id :bytes n})))
(defn- retry! [{:keys [h id]} attempt delay e]
  (emit h {:event :retry :job id :attempt attempt :delay-ms delay :error (ex-message e)}))
(defn finished! [{:keys [h id]}] (emit h {:event :finished :job id}))
(defn failed! [{:keys [h id]} e] (emit h {:event :failed :job id :error (ex-message e)}))

(defmacro with-job
  "Runs body as job `label`; reports how it ended."
  [[job h label total] & body]
  `(let [~job (start-job ~h ~label ~total)]
     (try (let [r# (do ~@body)] (finished! ~job) r#)
          (catch Throwable t# (failed! ~job t#) (throw t#)))))

;; --- the limiter -------------------------------------------------------------------------

(defrecord Limiter [^Semaphore global ^ConcurrentHashMap hosts ^long per-host])

(defn limiter [global per-host]
  (->Limiter (Semaphore. (int (max 1 global)) true) (ConcurrentHashMap.) (max 1 (long per-host))))

(defn acquire!
  "A permit for `host`, released by (.close permit): the host's first, then
  a global one, so a busy host never holds global slots others could use."
  ^java.lang.AutoCloseable [^Limiter l ^String host]
  (let [^Semaphore hs (.computeIfAbsent ^ConcurrentHashMap (.-hosts l) (.toLowerCase (str host) Locale/ROOT)
                                        (fn [_] (Semaphore. (int (.-per-host l)) true)))
        ^Semaphore g (.-global l)]
    (.acquire hs)
    (try (.acquire g) (catch Throwable t (.release hs) (throw t)))
    (reify java.lang.AutoCloseable (close [_] (.release g) (.release hs)))))

;; --- retries --------------------------------------------------------------------------------

(defn delay-ms
  "The wait before retry number `attempt` (1-based): the server's Retry-After,
  else 500 ms doubling to 30 s."
  ^long [{:keys [base-delay-ms max-delay-ms]} attempt e]
  (or (some-> (:retry-after-ms (ex-data e)) long)
      (min (long max-delay-ms) (* (long base-delay-ms) (bit-shift-left 1 (min 20 (dec (long attempt))))))))

(defn with-retry
  "Calls (f) until it succeeds, a fatal error, or the policy's attempts run out."
  [{:keys [retry] :as h} job f]
  (let [{:keys [max-attempts max-retry-after-ms]} retry]
    (loop [attempt 1]
      (let [r (try {:ok (f)} (catch clojure.lang.ExceptionInfo e {:e e}))]
        (if-let [e (:e r)]
          (let [d (delay-ms retry attempt e)]
            (if (and (retryable? e) (< attempt (long max-attempts)) (<= d (long max-retry-after-ms)))
              (do (when job (retry! job attempt d e))
                  (Thread/sleep d)
                  (recur (inc attempt)))
              (throw e)))
          (:ok r))))))

;; --- the clients ---------------------------------------------------------------------------------

(defn- client ^HttpClient [{:keys [connect-timeout-ms]} redirect]
  (-> (HttpClient/newBuilder)
      (.version HttpClient$Version/HTTP_2)
      (.connectTimeout (Duration/ofMillis connect-timeout-ms))
      (.followRedirects redirect)
      (.executor (Executors/newVirtualThreadPerTaskExecutor))
      (.build)))

(defonce ^:private watchdog
  (Executors/newSingleThreadScheduledExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r] (doto (Thread. r "http-watchdog") (.setDaemon true))))))

;; open response streams -> last progress (nanos); a stream silent longer than
;; its read timeout is closed, which fails the read blocked on it
(defonce ^:private live (ConcurrentHashMap.))

(defonce ^:private watching
  (.scheduleWithFixedDelay ^ScheduledExecutorService watchdog
                           ^Runnable (fn []
                                       (let [now (System/nanoTime)]
                                         (doseq [[^InputStream in [^AtomicLong at ^long limit]] live]
                                           (when (> (- now (.get at)) limit)
                                             (.remove ^ConcurrentHashMap live in)
                                             (try (.close in) (catch Exception _))))))
                           1 1 TimeUnit/SECONDS))

(defn http
  "An HTTP base. Options: see `defaults`; :on-event receives events."
  [& [opts]]
  (let [cfg (merge-with (fn [a b] (if (map? a) (merge a b) b)) defaults opts)]
    (assoc cfg
           :client (client cfg HttpClient$Redirect/NORMAL)
           :api-client (client cfg HttpClient$Redirect/NEVER)
           :limiter (limiter (:global-connections cfg) (:per-host-connections cfg)))))

(defn- uri ^URI [url]
  (try (URI. (str url))
       (catch Exception _ (throw (error :protocol (str "bad URL " (redact url)))))))

(defn host "`url`'s host." ^String [url] (.getHost (uri url)))

(defn- send-over
  "Sends a request over `c` (see send!)."
  ^HttpResponse [^HttpClient c h url {:keys [method headers body timeout-ms]}]
  (let [b   (-> (HttpRequest/newBuilder (uri url))
                (.timeout (Duration/ofMillis (long (or timeout-ms (:read-timeout-ms h)))))
                (.header "User-Agent" (:user-agent h)))
        _   (doseq [[k v] headers] (.header b (name k) (str v)))
        req (.build (case (or method :get)
                      :get  (.GET b)
                      :post (.POST b (if body
                                       (HttpRequest$BodyPublishers/ofByteArray body)
                                       (HttpRequest$BodyPublishers/noBody)))))]
    (try
      (.send c req (HttpResponse$BodyHandlers/ofInputStream))
      (catch HttpTimeoutException _ (throw (network-error url "timed out")))
      (catch java.io.IOException e (throw (network-error url (.getMessage e)))))))

(defn send!
  "Sends a request, returning the response with an InputStream body (headers
  received; the body not yet read). `headers` is a map; `:api?` uses the
  client that never follows redirects."
  ^HttpResponse [h url {:keys [api?] :as opts}]
  (send-over (if api? (:api-client h) (:client h)) h url opts))

(defn header ^String [^HttpResponse resp ^String k]
  (.orElse (.firstValue (.headers resp) k) nil))

(defn close-body [^HttpResponse resp] (try (.close ^InputStream (.body resp)) (catch Exception _)))

(defn read-body
  "The body as bytes: exactly `expect` (when given) and at most `max`.
  Reports progress to `job`. Closes the stream."
  ^bytes [h ^HttpResponse resp url {:keys [expect max job]}]
  (let [^InputStream in (.body resp)
        at  (AtomicLong. (System/nanoTime))
        lim (* 1000000 (long (:read-timeout-ms h)))
        cap (long (or max expect Long/MAX_VALUE))
        out (java.io.ByteArrayOutputStream. (int (min (long (or expect 65536)) (* 64 1024 1024))))
        buf (byte-array 65536)]
    (.put ^ConcurrentHashMap live in [at lim])
    (try
      (loop [total 0]
        (let [n (long (try (.read in buf)
                           (catch java.io.IOException e (throw (network-error url (.getMessage e))))))]
          (if (neg? n)
            (do (when (and expect (not= total (long expect)))
                  (throw (error :corrupt-part (str (redact url) " is corrupt: body ended after " total " of " expect " bytes"))))
                (.toByteArray out))
            (let [t (+ total n)]
              (.set at (System/nanoTime))
              (when (> t cap)
                (throw (unexpected url (str "body longer than the " (if expect "expected" "allowed") " " cap " bytes"))))
              (when job (progress! job n))
              (.write out buf 0 n)
              (recur t)))))
      (finally (.remove ^ConcurrentHashMap live in) (.close in)))))

(defn- error-body
  "At most `max` bytes of an error body, then the response is dropped."
  ^String [^HttpResponse resp]
  (let [^InputStream in (.body resp)]
    (try
      (let [b (.readNBytes in (+ error-body-max 3))
            s (String. b java.nio.charset.StandardCharsets/UTF_8)]
        (loop [s s] (if (> (alength (.getBytes ^String s java.nio.charset.StandardCharsets/UTF_8)) error-body-max)
                      (recur (subs s 0 (dec (count s))))
                      (str/replace s "�" ""))))
      (catch Exception _ "")
      (finally (try (.close in) (catch Exception _))))))

(defn check
  "Passes a 2xx response through; otherwise reads at most 300 bytes of its
  body and throws: 404 :not-found, 429 :rate-limited, else :status."
  ^HttpResponse [^HttpResponse resp url]
  (let [st (.statusCode resp)]
    (if (<= 200 st 299)
      resp
      (let [ra   (some-> (header resp "Retry-After") str/trim parse-long)
            body (error-body resp)]
        (case st
          404 (throw (error :not-found (str "not found (HTTP 404): " (redact url)) {:what (redact url)}))
          429 (throw (error :rate-limited (str "rate limited by " (host url) " (HTTP 429)"
                                               (when ra (str ", retry after " ra "s")))
                            {:host (host url) :retry-after-ms (some-> ra (* 1000))}))
          (throw (error :status (str "HTTP " st " from " (redact url) ": " body)
                        {:url (redact url) :status st :body body})))))))

(defn content-range
  "[start end total] (end inclusive) from `bytes a-b/total`, or nil."
  [^String s]
  (when-let [[_ a b t] (and s (re-matches #"(?i)bytes\s+(\d+)-(\d+)/(\d+)" (str/trim s)))]
    [(parse-long a) (parse-long b) (parse-long t)]))

(defn- reject!
  "Drops the response and throws `e`."
  [resp e]
  (close-body resp)
  (throw e))

(defn- check-range!
  "Rejects a ranged response unless it is a 206 for exactly [a, b) of a file
  of `total` bytes (when given)."
  [^HttpResponse resp url a b total]
  (let [st (.statusCode resp)
        [s e t :as cr] (content-range (header resp "Content-Range"))]
    (when (not= 206 st)
      (reject! resp (unexpected url (str "HTTP " st " to a range request"))))
    (when (and total cr (not= (long t) (long total)))
      (reject! resp (error :archive-changed (str "archive changed on the server: now " t " bytes, was " total
                                                 " (" (redact url) ")")
                           {:now t :was total})))
    (when-not (and cr (= (long a) (long s)) (= (dec (long b)) (long e)))
      (reject! resp (unexpected url (str "Content-Range " (header resp "Content-Range")))))))

;; --- requests ----------------------------------------------------------------------------------

(defn get-bytes
  "GET a small document (at most `max-body` bytes), retried. Options as send!."
  ^bytes [h url max-body & [opts]]
  (with-job [job h (str "get " (redact url)) nil]
    (with-retry h job
      (fn []
        (with-open [_ (acquire! (:limiter h) (host url))]
          (let [resp (check (send! h url opts) url)]
            (read-body h resp url {:max max-body :job job})))))))

(defn get-range
  "One attempt at bytes [a, b) of `url`: a 206 for exactly that range, of a
  file of `total` bytes when given (else :archive-changed)."
  ^bytes [h url a b total job & [opts]]
  (let [a (long a) b (long b)]
    (if (>= a b)
      (byte-array 0)
      (with-open [_ (acquire! (:limiter h) (host url))]
        (let [resp (check (send! h url (assoc-in opts [:headers "Range"] (str "bytes=" a "-" (dec b)))) url)]
          (check-range! resp url a b total)
          (read-body h resp url {:expect (- b a) :job job}))))))

(defn get-suffix
  "One attempt at the last `n` bytes: [bytes total]."
  [h url n job & [opts]]
  (with-open [_ (acquire! (:limiter h) (host url))]
    (let [n (long n)
          resp (check (send! h url (assoc-in opts [:headers "Range"] (str "bytes=-" n))) url)]
      (if (= 206 (.statusCode resp))
        (let [[s e t] (content-range (header resp "Content-Range"))]
          (when-not (and t (= (inc (long e)) (long t)) (= (long s) (max 0 (- (long t) n))))
            (reject! resp (unexpected url (str "Content-Range " (header resp "Content-Range")))))
          [(read-body h resp url {:expect (- (long t) (long s)) :job job}) t])
        (let [^bytes b (try (read-body h resp url {:max n :job job})
                            (catch clojure.lang.ExceptionInfo e
                              (throw (if (= :protocol (kind e))
                                       (unexpected url "file host ignored the Range header")
                                       e))))]
          [b (alength b)])))))

(defn range-len
  "The size of `url` by asking for byte 0 (HEAD isn't used: Nexus's file host
  omits Content-Length): the length, or nil when the server ignores Range."
  [h url job & [opts]]
  (with-open [_ (acquire! (:limiter h) (host url))]
    (let [resp (send! h url (assoc-in opts [:headers "Range"] "bytes=0-0"))]
      (if (= 416 (.statusCode resp))
        (do (close-body resp) 0)
        (let [resp (check resp url)]
          (close-body resp)
          (when (= 206 (.statusCode resp))
            (let [[s e t] (content-range (header resp "Content-Range"))]
              (when-not (and t (zero? (long s)) (zero? (long e)))
                (throw (unexpected url (str "Content-Range " (header resp "Content-Range")))))
              t)))))))

;; --- sinks ------------------------------------------------------------------------------------

(defprotocol Sink
  (write-at! [s offset ^bytes data] "Writes `data` at `offset`; called in increasing order (a retried stream restarts at 0)."))

(deftype MemorySink [^java.io.ByteArrayOutputStream out ^:unsynchronized-mutable ^bytes buf ^:unsynchronized-mutable ^long len]
  Sink
  (write-at! [_ offset data]
    (let [off (long offset) ^bytes data data end (+ off (alength data))]
      (when (> end (alength buf))
        (set! buf (java.util.Arrays/copyOf buf (int (max end (* 2 (alength buf)))))))
      (System/arraycopy data 0 buf off (alength data))
      (set! len (max len end))))
  clojure.lang.IDeref
  (deref [_] (java.util.Arrays/copyOf buf (int len))))

(defn memory-sink [] (MemorySink. nil (byte-array 65536) 0))

(defn file-sink
  "Writes into an open FileChannel."
  [^FileChannel ch]
  (reify Sink
    (write-at! [_ offset data]
      (let [bb (ByteBuffer/wrap data)]
        (loop [p (long offset)] (when (.hasRemaining bb) (recur (+ p (.write ch bb p)))))))))

;; --- whole files -------------------------------------------------------------------------------

(defn xxh64-b64
  "xxHash64 as Wabbajack writes it: base64 of the 8 little-endian bytes."
  ^String [^long h]
  (.encodeToString (java.util.Base64/getEncoder)
                   (.array (.putLong (.order (ByteBuffer/allocate 8) java.nio.ByteOrder/LITTLE_ENDIAN) h))))

(defn b64-xxh64 ^long [^String s]
  (let [b (try (.decode (java.util.Base64/getDecoder) s) (catch Exception _ nil))]
    (when-not (and b (= 8 (alength ^bytes b))) (throw (error :invalid (str "not an xxHash64: " s))))
    (.getLong (.order (ByteBuffer/wrap b) java.nio.ByteOrder/LITTLE_ENDIAN))))

(defn ordered-parallel
  "Calls (fetch x) for each of xs, at most `n` at once on virtual threads, and
  (consume x result) in order as results arrive. Stops at the first error."
  [n fetch consume xs]
  (with-open [^ExecutorService ex (Executors/newVirtualThreadPerTaskExecutor)]
    (let [q (java.util.ArrayDeque.)]
      (loop [xs (seq xs)]
        (let [xs (loop [xs xs] (if (and xs (< (.size q) (long n)))
                                 (let [x (first xs)]
                                   (.add q [x (.submit ex ^java.util.concurrent.Callable (fn [] (fetch x)))])
                                   (recur (next xs)))
                                 xs))]
          (when-let [[x ^Future f] (.poll q)]
            (let [r (try (.get f)
                         (catch ExecutionException e
                           (doseq [[_ ^Future g] q] (.cancel g true))
                           (throw (.getCause e))))]
              (consume x r)
              (recur xs))))))))

(defn download!
  "Downloads `url` into `sink`, hashing as it goes: in parallel `chunk-size`
  ranges when the server serves ranges, else as one stream (restarted on a
  retry). Checks `expected-len` before fetching anything and `expected-hash`
  (an xxHash64 long) at the end. Returns {:len :hash}."
  [h url sink & [{:keys [expected-hash expected-len]}]]
  (let [label (str "download " (redact url))]
    (with-job [job h label expected-len]
      (let [len (with-retry h job #(range-len h url job))]
        (when (and len expected-len (not= (long len) (long expected-len)))
          (throw (unexpected url (str len " bytes, expected " expected-len))))
        (let [result
              (if len
                (let [cs (long (:chunk-size h))
                      ranges (for [a (range 0 (long len) cs)] [a (min (long len) (+ a cs))])]
                  (with-open [x (Codec$Xxh64. 0)]
                    (ordered-parallel (:parallel-parts h)
                                      (fn [[a b]] (with-retry h job #(get-range h url a b nil job)))
                                      (fn [[a _] ^bytes data]
                                        (.update x data 0 (alength data))
                                        (write-at! sink a data))
                                      ranges)
                    {:len len :hash (.digest x)}))
                (with-retry h job
                  (fn []
                    (with-open [_ (acquire! (:limiter h) (host url))
                                x (Codec$Xxh64. 0)]
                      (let [resp (check (send! h url {}) url)
                            ^InputStream in (.body resp)
                            declared (some-> (header resp "Content-Length") parse-long)]
                        (when (not= 200 (.statusCode resp))
                          (reject! resp (unexpected url (str "HTTP " (.statusCode resp)))))
                        (when (and declared expected-len (not= (long declared) (long expected-len)))
                          (reject! resp (unexpected url (str declared " bytes, expected " expected-len))))
                        (with-open [in in]
                          (let [buf (byte-array (* 1024 1024))]
                            (loop [off 0]
                              (let [n (.readNBytes in buf 0 (alength buf))]
                                (if (pos? n)
                                  (do (when (and expected-len (> (+ off n) (long expected-len)))
                                        (throw (unexpected url (str "longer than " expected-len " bytes"))))
                                      (progress! job n)
                                      (.update x buf 0 n)
                                      (write-at! sink off (java.util.Arrays/copyOf buf n))
                                      (recur (+ off n)))
                                  (do (when (or (and declared (not= off (long declared))) (and expected-len (not= off (long expected-len))))
                                        (throw (error :corrupt-part (str (redact url) " is corrupt: body ended after " off " bytes"))))
                                      {:len off :hash (.digest x)})))))))))))]
          (when (and expected-hash (not= (long expected-hash) (long (:hash result))))
            (throw (error :hash-mismatch (str (redact url) ": expected xxHash64 " (xxh64-b64 expected-hash) ", got " (xxh64-b64 (:hash result)))
                          {:expected expected-hash :actual (:hash result)})))
          result)))))

;; --- what the sources share: locks, fetches, caches, files ---------------------------------------

(defmacro with-lock
  "Runs body holding `lock` (a java.util.concurrent.locks.Lock)."
  [lock & body]
  `(let [l# ~lock]
     (.lock ^Lock l#)
     (try ~@body (finally (.unlock ^Lock l#)))))

(defn single-flight
  "(f) for key `k`, run once however many callers miss together (`inflight`:
  k -> CompletableFuture); every caller gets its value or its error.
  `detached?` runs it on its own virtual thread, so the first caller being
  cancelled doesn't fail the others."
  [^ConcurrentHashMap inflight k detached? f]
  (let [mine (CompletableFuture.)
        ^CompletableFuture cf (or (.putIfAbsent inflight k mine) mine)]
    (when (identical? cf mine)
      (let [run (fn []
                  (try (.complete mine (f))
                       (catch Throwable t (.completeExceptionally mine t))
                       (finally (.remove inflight k))))]
        (if detached? (Thread/startVirtualThread ^Runnable run) (run))))
    (try (.get cf) (catch ExecutionException e (throw (.getCause e))))))

(defn lru-put!
  "Puts byte array `v` under `k` in access-ordered `m` (unless there), then
  drops the eldest entries while the bytes (counted in `used`) exceed
  `max-bytes` or the entries `max-entries` (when given). Locks `m`."
  [^LinkedHashMap m ^AtomicLong used k ^bytes v max-bytes & [max-entries]]
  (let [max-bytes (long max-bytes) max-entries (long (or max-entries Long/MAX_VALUE))]
    (locking m
      (when-not (.containsKey m k)
        (.put m k v)
        (.addAndGet used (alength v))
        (let [it (.iterator (.entrySet m))]
          (while (and (.hasNext it) (or (> (.size m) max-entries) (> (.get used) max-bytes)))
            (let [^Map$Entry victim (.next it)]
              (.addAndGet used (- (alength ^bytes (.getValue victim))))
              (.remove it))))))))

(defn write-file!
  "Writes `data` over file `f` by way of a temporary beside it
  (f.<pid>.<nanos>.partial, deleted on failure), so readers see the old file
  or the new one. `private?`: owner-only, synced, and renamed atomically."
  [f ^bytes data & [{:keys [private?]}]]
  (let [f   (io/file f)
        dir (.getParentFile (.getAbsoluteFile f))
        _   (.mkdirs dir)
        tmp (io/file dir (str (.getName f) "." (.pid (ProcessHandle/current)) "." (System/nanoTime) ".partial"))]
    (try
      (with-open [o (java.io.FileOutputStream. tmp)]
        (.write o data)
        (when private? (.sync (.getFD o))))
      (when private?
        (.setReadable tmp false false) (.setReadable tmp true true)
        (.setWritable tmp false false) (.setWritable tmp true true))
      (Files/move (.toPath tmp) (.toPath f)
                  (into-array CopyOption (cond-> [StandardCopyOption/REPLACE_EXISTING]
                                           private? (conj StandardCopyOption/ATOMIC_MOVE))))
      (catch Exception e (.delete tmp) (throw e)))))

;; --- bulk ----------------------------------------------------------------------------------------

(defn bulk
  "`conns` separate clients, each with its own connection per host: a file
  host serving ~20 MiB/s a request scales with connections. No limiter, no
  retry, no events: the caller bounds and counts."
  [h & [{:keys [conns] :or {conns 8}}]]
  {:h h :clients (vec (repeatedly (max 1 conns) #(client h HttpClient$Redirect/NORMAL))) :next (AtomicLong.)})

(defn next-conn ^long [{:keys [clients] ^AtomicLong n :next}]
  (mod (.getAndIncrement n) (count clients)))

(defn bulk-range
  "Bytes [a, b) of `url` (of `total` bytes) over connection `conn`, as a stream
  of exactly b - a bytes: {:in InputStream :len n}. The caller closes it."
  [{:keys [h clients]} conn url a b total]
  (when (or (>= (long a) (long b)) (and total (> (long b) (long total))))
    (throw (error :unsupported (str "bad range " a "-" b " of " total))))
  (let [a    (long a) b (long b)
        resp (check (send-over (nth clients conn) h url {:headers {"Range" (str "bytes=" a "-" (dec b))}}) url)]
    (check-range! resp url a b total)
    {:in (.body resp) :len (- b a)}))
