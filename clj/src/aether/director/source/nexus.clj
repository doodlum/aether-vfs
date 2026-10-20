(ns aether.director.source.nexus
  "Nexus Mods (aether-vfs's aether-nexus): the v3 API (file records, signed
  repacked-zip URLs) and random access into repacked zips, as a provider.

  A repacked zip is ZIP64; an entry over 4 MiB is seekable zstd in 4 MiB
  frames, its seek table copied into the central-directory extra field
  0x4E58 (aether.director.source.archive). Opening an archive is one request
  (a suffix: the central directory, and the length from Content-Range); a
  read fetches only the frames it covers, adjacent frames coalesced into
  runs of up to 32 MiB, the runs in parallel, and decodes them straight into
  the reader's buffer. Decoded frames are kept in a small LRU, so sequential
  reads of less than a frame decode each frame once.

  Signed links are minted lazily (each mint records a download), once per
  file however many readers miss together, renewed 5 minutes before they
  expire or when the file host refuses one; they never appear in errors,
  events or printed values. The API key is sent only to the API, by the
  client that never follows redirects."
  (:require [aether.director.source.archive :as ar]
            [aether.director.source.net :as net]
            [aether.director.source.tree :as tree]
            [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (aether.director.source.archive SeekTable ZipEntry ZipIndex)
           (aether.director.provider Stat)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.net.http HttpResponse)
           (java.nio ByteBuffer ByteOrder)
           (java.time Instant OffsetDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Arrays LinkedHashMap)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong AtomicReferenceArray)
           (java.util.concurrent.locks ReentrantLock)))

(set! *warn-on-reflection* true)

(def api "https://api.nexusmods.com")
(def ^:const expiry-margin-ms (* 300 1000))
(def ^:const link-save-interval-ms (* 30 1000))
(def ^:const quota-max-age-ms (* 300 1000))
(def ^:const max-allowance-wait-ms (* 65 60 1000))
(def ^:const tail-read (* 256 1024))
(def ^:const max-central-directory (* 256 1024 1024))
(def ^:const max-request (* 32 1024 1024))
(def ^:const local-extra-guess 256)
(def ^:const header-join (* 1024 1024))
(def ^:const seek-table-tail (* 64 1024))
(def ^:const max-seek-table (* 64 1024 1024))
(def ^:const max-read (* 32 1024 1024))
(def ^:const frame-cache-bytes (* 64 1024 1024))

(defn uid "A file's uid: (game << 32) + file id." ^long [game file-id] (+ (bit-shift-left (long game) 32) (long file-id)))

(def game-ids "Wabbajack game names (case-insensitive) -> Nexus game ids." {"skyrimspecialedition" 1704 "skyrim" 110})
(defn game-id [name] (get game-ids (str/lower-case (str name))))

;; Secrets print as <redacted>.
(deftype Secret [^String v] Object (toString [_] "<redacted>"))
(defmethod print-method Secret [_ ^java.io.Writer w] (.write w "<redacted>"))

;; --- quota -----------------------------------------------------------------------------

(defn- parse-reset
  "A reset time like `2026-10-02 18:00:00 +0000` (UTC zones only), as epoch ms."
  [^String s]
  (when s
    (let [s (str/trim s)
          base (some (fn [suffix] (when (str/ends-with? s suffix) (str/trim (subs s 0 (- (count s) (count suffix))))))
                     ["+0000" "+00:00" "UTC" "Z"])]
      (when base
        (try (.toEpochMilli (.toInstant (OffsetDateTime/parse (str (str/replace base " " "T") "Z"))))
             (catch Exception _ nil))))))

(defn- bucket [^HttpResponse resp kind now]
  (let [g (fn [k] (some-> (net/header resp (str "x-rl-" kind "-" k)) str/trim))
        limit (some-> (g "limit") parse-long) remaining (some-> (g "remaining") parse-long)]
    (when (and limit remaining (pos? (long limit)))
      {:limit limit :remaining remaining :reset (parse-reset (g "reset")) :at now})))

(defn- record-quota! [{:keys [quota]} ^HttpResponse resp]
  (let [now (System/currentTimeMillis)]
    (doseq [kind ["hourly" "daily"]]
      (when-let [b (bucket resp kind now)]
        (swap! quota update (keyword kind)
               (fn [old] (if (and old (:reset old) (= (:reset old) (:reset b)))
                           (assoc b :remaining (min (long (:remaining old)) (long (:remaining b))))
                           b)))))))

(defn- current? [b now]
  (and b (<= (long (:at b)) (long now)) (< (- (long now) (long (:at b))) quota-max-age-ms)
       (or (nil? (:reset b)) (> (long (:reset b)) (long now)))))

(defn quota
  "{:hourly :daily} buckets still current, and :spare (remaining above half
  the limit, from the daily bucket, else the hourly)."
  [{:keys [quota]}]
  (let [now (System/currentTimeMillis)
        q (into {} (filter (fn [[_ b]] (current? b now)) @quota))
        spare-of (fn [{:keys [limit remaining]}] (max 0 (- (long remaining) (long (Math/ceil (/ (double limit) 2))))))]
    (assoc q :spare (some-> (or (:daily q) (:hourly q)) spare-of))))

;; --- the client ------------------------------------------------------------------------------

(defn- load-links [^java.io.File f]
  (doseq [^java.io.File p (.listFiles (.getParentFile f))
          :when (str/starts-with? (.getName p) (str (.getName f) "."))
          :when (str/ends-with? (.getName p) ".partial")]
    (.delete p))
  (let [now (System/currentTimeMillis)]
    (try
      (let [d (json/read-json (slurp f))]
        (if (= 1 (get d "v"))
          (into {} (for [{u "uid" url "url" exp "expires_at"} (get d "links")
                         :let [ms (* 1000 (long exp))]
                         :when (> ms (+ now expiry-margin-ms))]
                     [(long u) {:url (Secret. url) :expires ms}]))
          {}))
      (catch Exception _ {}))))

(defn client
  "A Nexus client for API key `key` over net base `h`. Options: :base-url,
  :link-file (signed links kept across runs: a secret, never logged)."
  [h ^String key & [{:keys [base-url link-file]}]]
  (let [links (ConcurrentHashMap.)]
    (when link-file
      (io/make-parents link-file)
      (when (.exists (io/file link-file)) (.putAll links (load-links (io/file link-file)))))
    {:h h :key (Secret. key) :base (or base-url api) :links links :locks (ConcurrentHashMap.)
     :quota (atom {}) :links-requested (AtomicLong.) :link-file (some-> link-file io/file)
     :dirty (AtomicBoolean.) :last-save (AtomicLong.) :saving (Object.)}))

(defn save-links!
  "Writes the cached links (a secret) beside, then over, the link file."
  [{:keys [^ConcurrentHashMap links ^java.io.File link-file ^AtomicBoolean dirty ^AtomicLong last-save saving]}]
  (when link-file
    (locking saving
      (.set dirty false)
      (let [now (System/currentTimeMillis)
            body (json/write-json-str
                  {"v" 1 "links" (vec (for [[u {:keys [url expires]}] (sort-by key links)
                                            :when (> (long expires) (+ now expiry-margin-ms))]
                                        {"uid" u "url" (.-v ^Secret url) "expires_at" (quot (long expires) 1000)}))})
            tmp (io/file (str link-file "." (.pid (ProcessHandle/current)) "." now ".partial"))]
        (try
          (spit tmp body)
          (.setReadable tmp false false) (.setReadable tmp true true)
          (java.nio.file.Files/move (.toPath tmp) (.toPath link-file)
                                    (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                                                         java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
          (.set last-save now)
          (catch Exception _ (.set dirty true) (.delete tmp)))))))

(defn- maybe-save! [{:keys [^AtomicBoolean dirty ^AtomicLong last-save link-file] :as c}]
  (when (and link-file (.get dirty) (> (- (System/currentTimeMillis) (.get last-save)) link-save-interval-ms))
    (Thread/startVirtualThread ^Runnable #(save-links! c))))

(defn- problem-detail [^HttpResponse resp]
  (let [b (try (.readNBytes ^java.io.InputStream (.body resp) 4096) (catch Exception _ (byte-array 0)))]
    (net/close-body resp)
    (let [d (try (get (json/read-json (String. ^bytes b "UTF-8")) "detail") (catch Exception _ nil))]
      (some-> d str (#(subs % 0 (min 300 (count %))))))))

(defn- api-check [^HttpResponse resp url]
  (let [st (.statusCode resp)]
    (if (<= 200 st 299)
      resp
      (let [ra (some-> (net/header resp "Retry-After") str/trim parse-long)
            detail (problem-detail resp)]
        (case st
          401 (throw (net/error :unauthorized "Nexus Mods rejected the API key (HTTP 401). Log in to Nexus Mods again with a valid personal API key."))
          403 (throw (net/error :forbidden (str "Nexus Mods refused the request (HTTP 403: " detail "). Downloading repacked archives needs a Premium membership.") {:detail detail}))
          404 (throw (net/error :not-found (str "not found (HTTP 404): " (or detail (net/redact url))) {:what (or detail (net/redact url))}))
          429 (throw (net/error :rate-limited (str "rate limited by " (.getHost (java.net.URI. url)) " (HTTP 429)" (when ra (str ", retry after " ra "s")))
                                {:retry-after-ms (some-> ra (* 1000))}))
          (throw (net/error :status (str "HTTP " st " from " (net/redact url) ": " detail) {:url (net/redact url) :status st :body detail})))))))

(defn- api!
  "An API call's parsed JSON body, retried; a 429 that outlasts the retries
  waits for a spent hourly allowance to reset (a spent daily one fails)."
  [{:keys [h key base] :as c} method path]
  (let [url (str base path)]
    (loop []
      (let [r (try
                {:ok (net/with-job [job h (str "nexus " (name method) " " (net/redact url)) nil]
                       (net/with-retry h job
                         (fn []
                           (with-open [_ (net/acquire! (:limiter h) (.getHost (java.net.URI. url)))]
                             (let [resp (net/send! h url {:method method :api? true
                                                          :headers {"apikey" (.-v ^Secret key) "Accept" "application/json"}})]
                               (record-quota! c resp)
                               (api-check resp url)
                               (let [b (net/read-body h resp url {:max (* 16 1024 1024)})]
                                 (try (json/read-json (String. ^bytes b "UTF-8"))
                                      (catch Exception _ (throw (net/error :protocol (str "unexpected response from " (net/redact url) ": not JSON")))))))))))}
                (catch clojure.lang.ExceptionInfo e
                  (if (= :rate-limited (net/kind e))
                    (let [now (System/currentTimeMillis)
                          resets (keep (fn [[_ b]] (when (and (current? b now) (zero? (long (:remaining b))) (:reset b)) (:reset b))) @(:quota c))
                          wait (when (seq resets) (+ (- (long (apply min resets)) now) 5000))]
                      (if (and wait (<= (long wait) max-allowance-wait-ms))
                        (do (Thread/sleep (long wait)) {:again true})
                        (throw e)))
                    (throw e))))]
        (if (:again r) (recur) (:ok r))))))

(defn file-record
  "The file record of a mod file: {:uid :name :version :category}."
  [c domain file-id]
  (let [d (get (api! c :get (str "/v3/games/" domain "/mod-file-versions/" file-id)) "data")]
    {:uid (parse-long (str (get d "id"))) :name (get d "name")
     :version (get d "version" "") :category (get d "category" "")}))

(defn validate
  "{:name :premium?} of the key's user."
  [c]
  (let [d (api! c :get "/v1/users/validate.json")]
    {:name (get d "name") :premium? (boolean (get d "is_premium"))}))

(defn download-url
  "A signed URL for file `uid` (a Secret), minted at most once however many
  callers miss together, renewed 5 minutes before it expires."
  ^Secret [{:keys [^ConcurrentHashMap links ^ConcurrentHashMap locks ^AtomicLong links-requested ^AtomicBoolean dirty] :as c} uid]
  (let [uid (long uid)
        fresh (fn [] (when-let [{:keys [url expires]} (.get links uid)]
                       (when (> (long expires) (+ (System/currentTimeMillis) expiry-margin-ms)) url)))]
    (or (fresh)
        (let [^ReentrantLock l (.computeIfAbsent locks uid (reify java.util.function.Function (apply [_ _] (ReentrantLock.))))]
          (.lock l)
          (try
            (or (fresh)
                (let [_ (.incrementAndGet links-requested)
                      d (api! c :post (str "/v3/mod-file-versions/" uid "/download-repacked"))
                      url (Secret. (str (get d "download_url")))
                      expires (.toEpochMilli (.toInstant (OffsetDateTime/parse (str (get d "expires_at")))))]
                  (.put links uid {:url url :expires expires})
                  (.set dirty true)
                  (maybe-save! c)
                  url))
            (finally (.unlock l)))))))

(defn forget-refused!
  "Drops `uid`'s link if it is still `refused` (a reader whose refusal arrives
  late mustn't drop a link someone just renewed)."
  [{:keys [^ConcurrentHashMap links]} uid ^Secret refused]
  (.computeIfPresent links (long uid) (reify java.util.function.BiFunction
                                        (apply [_ _ v] (when-not (identical? refused (:url v)) v)))))

(defn has-url? [{:keys [^ConcurrentHashMap links]} uid]
  (when-let [{:keys [expires]} (.get links (long uid))] (> (long expires) (+ (System/currentTimeMillis) expiry-margin-ms))))

(defn- link-refused? [e]
  (let [{k ::net/error :keys [status]} (ex-data e)]
    (and (= :status k) (<= 400 (long status) 499) (not= 408 status) (not= 416 status))))

(defn with-url
  "(f url-string) with `uid`'s signed URL; once more with a fresh one if the
  file host refuses it."
  [c uid f]
  (let [u (download-url c uid)]
    (try (f (.-v u))
         (catch clojure.lang.ExceptionInfo e
           (if (link-refused? e)
             (do (forget-refused! c uid u) (f (.-v (download-url c uid))))
             (throw e))))))

;; --- archives ----------------------------------------------------------------------------------

(defrecord Archive [c ^long uid ^long len ^ZipIndex index ^AtomicReferenceArray ranges
                    ^AtomicReferenceArray tables ^LinkedHashMap frames ^AtomicLong frame-bytes])

;; record fields are Object in bytecode: hinted accessors
(defn- ranges ^AtomicReferenceArray [^Archive a] (.-ranges a))
(defn- tables ^AtomicReferenceArray [^Archive a] (.-tables a))
(defn- frames-of ^LinkedHashMap [^Archive a] (.-frames a))
(defn- frame-bytes ^AtomicLong [^Archive a] (.-frame-bytes a))

(defn- get-range ^bytes [{:keys [c uid len]} s e]
  (let [h (:h c)]
    (with-url c uid (fn [url] (net/with-retry h nil #(net/get-range h url s e len nil))))))

(defn- suffix-failed? [e]
  (let [{k ::net/error :keys [status]} (ex-data e)]
    (or (#{:protocol :network :corrupt-part} k) (and (= :status k) (not= 403 status)))))

(defn open-archive
  "The repacked zip of file `uid`: one request in the usual case (the tail,
  holding the central directory; the length from Content-Range)."
  [c uid]
  (let [h (:h c)
        want (max tail-read ar/max-tail)
        [^bytes tail len] (try (with-url c uid #(net/get-suffix h % want nil))
                               (catch clojure.lang.ExceptionInfo e
                                 (if (suffix-failed? e)
                                   (let [len (or (with-url c uid (fn [url] (net/with-retry h nil #(net/range-len h url nil))))
                                                 (throw (net/error :protocol "file host ignored the Range header")))
                                         n (min (long len) want)]
                                     [(with-url c uid (fn [url] (net/with-retry h nil #(net/get-range h url (- (long len) n) len len nil)))) len])
                                   (throw e))))
        len (long len)
        tail-start (- len (alength tail))
        a0 {:c c :uid uid :len len}
        loc (ar/locate-central-directory tail tail-start len)
        {:keys [offset size entries]} (if-let [pos (:need-zip64 loc)]
                                        (ar/zip64-record (get-range a0 pos (+ (long pos) ar/zip64-eocd-len)) len)
                                        loc)
        _ (when (> (long size) max-central-directory) (throw (net/error :unsupported (str "central directory of " size " bytes"))))
        cd (if (>= (long offset) tail-start)
             (Arrays/copyOfRange tail (int (- (long offset) tail-start)) (int (+ (- (long offset) tail-start) (long size))))
             (get-range a0 offset (+ (long offset) (long size))))
        es (ar/parse-central-directory cd entries)]
    (->Archive c uid len (ar/zip-index es) (AtomicReferenceArray. (count es)) (AtomicReferenceArray. (count es))
               (LinkedHashMap. 64 0.75 true) (AtomicLong.))))

(defn- entry ^ZipEntry [^Archive a id] (nth (:entries (.-index a)) id))

(defn- learn-range! [^Archive a id start]
  (let [e (entry a id) start (long start)
        end (+ start (.-csize e))]
    (when (or (> end (.-len a)) (< start (+ (.-lh-offset e) ar/local-header-len (.-name-len e))))
      (throw (net/error :invalid (str "zip: invalid data: " (.-name e) " runs past the end"))))
    (.set (ranges a) id [start end])
    [start end]))

(defn- fetch-with-header
  "Compressed bytes [rs, re) of entry `id`, learning where its data starts:
  one request with the local header when the range is near the start."
  ^bytes [^Archive a id rs re]
  (let [rs (long rs) re (long re)
        e (entry a id) len (.-len a) lh (.-lh-offset e)
        min-data (+ lh ar/local-header-len (.-name-len e))
        max-data (+ min-data local-extra-guess)
        guess-end (min len (+ max-data re))
        [^bytes head head-start ^bytes body body-start]
        (if (<= rs header-join)
          (let [b (get-range a lh guess-end)] [b lh b lh])
          (let [hd (future (get-range a lh (min max-data len)))
                bs (min (+ min-data rs) guess-end)
                bd (get-range a bs guess-end)]
            [@hd lh bd bs]))
        [start end] (ar/local-data-range (Arrays/copyOf head (int ar/local-header-len)) e)
        start (long start)
        _ (learn-range! a id start)
        want-s (+ start rs) want-e (+ start re)
        body-start (long body-start)]
    (if (and (>= want-s body-start) (<= want-e (+ body-start (alength body))))
      (Arrays/copyOfRange body (int (- want-s body-start)) (int (- want-e body-start)))
      (get-range a want-s want-e))))

(defn- data-range [^Archive a id]
  (or (.get (ranges a) id)
      (do (fetch-with-header a id 0 0) (.get (ranges a) id))))

(defn- seek-table
  "Entry `id`'s seek table: the central directory's, one plain frame, or
  read from the end of its data."
  ^SeekTable [^Archive a id]
  (or (.get (tables a) id)
      (let [e (entry a id)
            t (or (ar/frame-map e)
                  (let [[s end] (data-range a id)
                        s (long s) end (long end)
                        n (min seek-table-tail (- end s))
                        ^bytes tail (get-range a (- end n) end)
                        _ (when (< (alength tail) ar/footer-len) (throw (net/error :invalid (str "zip: invalid data: " (.-name e) " is too short for a seek table"))))
                        need (ar/table-frame-len (Arrays/copyOfRange tail (int (- (alength tail) ar/footer-len)) (alength tail)))
                        _ (when (> need max-seek-table) (throw (net/error :unsupported (str (.-name e) ": a seek table of " need " bytes"))))
                        tail (if (> need (alength tail)) (get-range a (- end need) end) tail)
                        t (ar/parse-seek-table tail)]
                    (when-not (and (= (.-usize e) (.-d-len ^SeekTable t))
                                   (= (.-csize e) (+ (.-c-len ^SeekTable t) (.-table-len ^SeekTable t))))
                      (throw (net/error :invalid (str "zip: invalid data: " (.-name e) "'s seek table doesn't match its sizes"))))
                    t))]
        (.set (tables a) id t)
        t)))

(defn- cached-frame ^bytes [^Archive a k]
  (locking (frames-of a) (.get (frames-of a) k)))

(defn- cache-frame! [^Archive a k ^bytes frame]
  (let [^LinkedHashMap m (frames-of a)]
    (locking m
      (when-not (.containsKey m k)
        (.put m k frame)
        (.addAndGet (frame-bytes a) (alength frame))
        (let [it (.iterator (.entrySet m))]
          (while (and (> (.get (frame-bytes a)) frame-cache-bytes) (.hasNext it))
            (let [^java.util.Map$Entry victim (.next it)]
              (.addAndGet (frame-bytes a) (- (alength ^bytes (.getValue victim))))
              (.remove it))))))))

(defn- runs
  "Frames (indices) grouped into contiguous runs of at most max-request compressed bytes."
  [^SeekTable t frames]
  (reduce (fn [out i]
            (let [i (long i) run (peek out)]
              (if (and run (= (inc (long (peek run))) i)
                       (<= (- (+ (aget ^longs (.-c-offs t) i) (aget ^longs (.-c-sizes t) i))
                              (aget ^longs (.-c-offs t) (long (first run))))
                           max-request))
                (conj (pop out) (conj run i))
                (conj out [i]))))
          [] frames))

(defn read-into!
  "Decompressed bytes [a, b) of entry `id` into `dst` at `dst-off`."
  [^Archive ar id a b ^MemorySegment dst dst-off]
  (let [a (long a) b (long b) dst-off (long dst-off)
        e (entry ar id)]
    (when (or (> b (.-usize e)) (> a b)) (throw (net/error :unsupported (str "read " a ".." b " past " (.-name e)))))
    (when (< a b)
      (case (int (.-method e))
        0 (do (when (not= (.-csize e) (.-usize e)) (throw (net/error :invalid (str "zip: invalid data: stored " (.-name e) " sizes differ"))))
              (let [^bytes data (if-let [[s] (.get (ranges ar) id)]
                                  (get-range ar (+ (long s) a) (+ (long s) b))
                                  (fetch-with-header ar id a b))]
                (MemorySegment/copy data 0 dst ValueLayout/JAVA_BYTE dst-off (alength data))))
        93 (let [t (seek-table ar id)
                 frames (ar/frames-for-range t a b)
                 ;; each frame: the part of [a, b) it holds, straight into dst when it's whole
                 place! (fn [i ^MemorySegment src-frame-or-nil ^bytes compressed]
                          (let [i (long i)
                                ds (aget ^longs (.-d-offs t) i) dz (aget ^longs (.-d-sizes t) i)
                                s (max a ds) en (min b (+ ds dz))
                                whole? (and (= s ds) (= en (+ ds dz)))]
                            (if-let [^bytes f (cached-frame ar [id i])]
                              (MemorySegment/copy f (int (- s ds)) dst ValueLayout/JAVA_BYTE (+ dst-off (- s a)) (int (- en s)))
                              (if whole?
                                (let [out (.asSlice dst (+ dst-off (- s a)) dz)]
                                  (ar/decompress-frame! t i (MemorySegment/ofArray compressed) out))
                                (let [f (byte-array dz)]
                                  (ar/decompress-frame! t i (MemorySegment/ofArray compressed) (MemorySegment/ofArray f))
                                  (cache-frame! ar [id i] f)
                                  (MemorySegment/copy f (int (- s ds)) dst ValueLayout/JAVA_BYTE (+ dst-off (- s a)) (int (- en s))))))))
                 todo (vec (remove #(cached-frame ar [id (long %)]) frames))]
             (doseq [i frames :when (cached-frame ar [id (long i)])] (place! i nil nil))
             (when (seq todo)
               (let [rs (runs t todo)
                     ;; first touch: the header and the first run in one request
                     [first-run & more] rs
                     run-bytes (fn [run ^bytes raw ^long base]
                                 (doseq [i run]
                                   (let [i (long i)
                                         co (aget ^longs (.-c-offs t) i) cs (aget ^longs (.-c-sizes t) i)]
                                     (place! i nil (Arrays/copyOfRange raw (int (- co base)) (int (- (+ co cs) base)))))))
                     span (fn [run] [(aget ^longs (.-c-offs t) (long (first run)))
                                     (let [l (long (peek run))] (+ (aget ^longs (.-c-offs t) l) (aget ^longs (.-c-sizes t) l)))])
                     known (.get (ranges ar) id)
                     rest-runs (if known rs (do (let [[s en] (span first-run)]
                                                  (run-bytes first-run (fetch-with-header ar id s en) s))
                                                more))]
                 (when (seq rest-runs)
                   (let [[ds] (.get (ranges ar) id) ds (long ds)]
                     (net/ordered-parallel (:parallel-parts (:h (.-c ar)))
                                           (fn [run] (let [[s en] (span run)] (get-range ar (+ ds (long s)) (+ ds (long en)))))
                                           (fn [run raw] (run-bytes run raw (first (span run))))
                                           rest-runs))))))
        (throw (net/error :unsupported (str (.-name e) ": compression method " (.-method e))))))))

(defn read-range
  "Decompressed bytes [a, b) of entry `id`."
  ^bytes [^Archive ar id a b]
  (let [out (byte-array (- (long b) (long a)))]
    (read-into! ar id a b (MemorySegment/ofArray out) 0)
    out))

(defn read-entry ^bytes [^Archive ar id] (read-range ar id 0 (.-usize (entry ar id))))

(defn find-entry
  "The index of the entry at `p` (case-insensitive, \\ or /), or nil."
  [^Archive ar p]
  (get (:by-name (.-index ar)) (ar/fold p)))

;; --- the provider ---------------------------------------------------------------------------------

(deftype EntryHandle [^Archive ar ^long id ^long size]
  prov/Handle
  (handle-stat [_] (Stat. false size tree/mtime))
  (immutable? [_] true)
  (read-into [_ dst dst-off pos len]
    (let [pos (long pos)]
      (if (or (>= pos size) (zero? (long len)))
        0
        (let [n (min (- size pos) (long len) max-read)]
          (try (read-into! ar id pos (+ pos n) dst (long dst-off)) n
               (catch Exception _ p/io-error))))))
  (write-from [_ _ _ _ _] p/read-only)
  (truncate! [_ _] p/read-only)
  (close! [_] nil))

(defn provider
  "The archive as a read-only provider: case-insensitive, listings sorted,
  content fetched only when read. Best behind storage/cached (a 4 MiB unit:
  one frame)."
  [^Archive ar]
  (tree/provider (map-indexed (fn [i ^ZipEntry e]
                                (let [n (str/replace (.-name e) "\\" "/")]
                                  (if (str/ends-with? n "/")
                                    {:path n :dir? true}
                                    {:path n :size (.-usize e) :key i})))
                              (:entries (.-index ar)))
                 (fn [id size] (EntryHandle. ar id size))))
