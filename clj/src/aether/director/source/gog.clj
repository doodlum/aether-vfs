(ns aether.director.source.gog
  "GOG Galaxy content-system v2 (aether-vfs's aether-gog, itself ported from
  NexusMods.App's NexusMods.Networking.GOG, GPL-3.0, checked against
  heroic-gogdl and minigalaxy): OAuth login (paste the redirect URL), builds,
  build details and depot manifests (cached on disk), and random-access
  reads of depot files from GOG's CDN, as a provider.

  A depot file is a run of zlib-compressed chunks (or a slice of the depot's
  small-files container); a read fetches the chunks it covers, up to 8 at a
  time, each checked (MD5 before and after inflating), kept in an LRU (64
  chunks, 256 MiB) and fetched once however many readers want it.

  Secrets: the tokens (credential file, owner-only), and the CDN token,
  which GOG puts in the URL *path*: errors show a scrubbed URL."
  (:require [aether.director.source.net :as net]
            [aether.director.source.tree :as tree]
            [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (aether.director.provider Stat)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat LinkedHashMap)
           (java.util.concurrent CompletableFuture ConcurrentHashMap)
           (java.util.concurrent.atomic AtomicLong)
           (java.util.concurrent.locks ReentrantLock)
           (java.util.zip Inflater)))

(set! *warn-on-reflection* true)

(def client-id "46899977096215655")
(def client-secret "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9")
(def redirect-uri "https://embed.gog.com/on_login_success?origin=client")

(def ^:const expiry-margin-s 60)
(def ^:const link-margin-s 60)
(def ^:const link-default-ttl-s 3600)
(def ^:const min-secret-len 6)
(def ^:const max-token-body (* 64 1024))
(def ^:const max-json-body (* 16 1024 1024))
(def ^:const max-meta-body (* 256 1024 1024))
(def ^:const max-document (* 512 1024 1024))
(def ^:const chunk-body-slack (* 64 1024))
(def ^:const max-read (* 32 1024 1024))

(defn config
  "A GOG configuration: where manifests are cached and credentials kept."
  [cache-dir credentials & [overrides]]
  (merge {:auth-base "https://auth.gog.com" :content-base "https://content-system.gog.com"
          :cdn-meta-base "https://cdn.gog.com" :client-id client-id :client-secret client-secret
          :redirect-uri redirect-uri :cache-dir (io/file cache-dir) :credentials (io/file credentials)
          :chunk-cache-chunks 64 :chunk-cache-bytes (* 256 1024 1024)}
         overrides))

(defn- now-s ^long [] (quot (System/currentTimeMillis) 1000))
(defn- enc ^String [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))
(defn- endpoint ^String [base path] (str (str/replace base #"/+$" "") path))

;; --- documents ---------------------------------------------------------------------------

(defn decode-document
  "A GOG metadata document: zlib-compressed JSON (or plain JSON)."
  [^bytes body]
  (let [inf (Inflater.)
        text (try
               (.setInput inf body)
               (let [out (java.io.ByteArrayOutputStream.) buf (byte-array 65536)]
                 (loop [total 0]
                   (if (.finished inf)
                     (String. (.toByteArray out) StandardCharsets/UTF_8)
                     (let [n (.inflate inf buf)]
                       (when (and (zero? n) (or (.needsInput inf) (.needsDictionary inf)))
                         (throw (java.util.zip.DataFormatException. "truncated")))
                       (when (> (+ total n) max-document) (throw (net/error :protocol "GOG document too large")))
                       (.write out buf 0 n)
                       (recur (+ total n))))))
               (catch java.util.zip.DataFormatException _
                 (let [s (String. body StandardCharsets/UTF_8)]
                   (if (str/starts-with? (str/triml s) "{") s (throw (net/error :protocol "GOG document is neither zlib nor JSON")))))
               (finally (.end inf)))]
    (json/read-json text)))

(defn- u64 [v] (cond (number? v) (long v) (string? v) (parse-long v) :else nil))

;; --- credentials ---------------------------------------------------------------------------

(defn login-url
  "Where the user logs in; they paste back the URL they land on."
  [{:keys [auth-base client-id redirect-uri]}]
  (str (endpoint auth-base "/auth") "?client_id=" client-id "&redirect_uri=" (enc redirect-uri)
       "&response_type=code&layout=client2"))

(defn code-from-paste
  "The login code in what the user pasted: the redirect URL, or the bare code."
  ^String [^String pasted]
  (let [s (str/trim (str pasted))]
    (when (str/blank? s) (throw (net/error :login "nothing was pasted")))
    (if (re-find #"^(?i)https?://" s)
      (let [q (.getRawQuery (java.net.URI. s))
            code (some (fn [kv] (let [[k v] (str/split kv #"=" 2)] (when (= k "code") (java.net.URLDecoder/decode (str v) StandardCharsets/UTF_8))))
                       (str/split (str q) #"&"))]
        (when (str/blank? code) (throw (net/error :login "the pasted URL has no login code")))
        code)
      (do (when (re-find #"[\s?&/=#]" s) (throw (net/error :login "that isn't a login code or the URL GOG sent you to")))
          s))))

(defn- save-credentials! [^java.io.File f creds]
  (let [dir (.getParentFile (.getAbsoluteFile f))
        _ (.mkdirs dir)
        tmp (io/file dir (str (.getName f) "." (.pid (ProcessHandle/current)) "." (System/nanoTime) ".partial"))]
    (try
      (with-open [o (java.io.FileOutputStream. tmp)]
        (.write o (.getBytes ^String (json/write-json-str {"user_id" (:user-id creds) "access_token" (:access-token creds)
                                                           "refresh_token" (:refresh-token creds) "expires_at" (:expires-at creds)}
                                                          :indent-str "  ")
                             StandardCharsets/UTF_8))
        (.sync (.getFD o)))
      (.setReadable tmp false false) (.setReadable tmp true true)
      (.setWritable tmp false false) (.setWritable tmp true true)
      (java.nio.file.Files/move (.toPath tmp) (.toPath f)
                                (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                                                     java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception e (.delete tmp) (throw e)))))

(defn load-credentials
  "The saved login, or nil."
  [^java.io.File f]
  (when (.exists f)
    (let [d (try (json/read-json (slurp f)) (catch Exception _ (throw (net/error :login "the GOG login file is damaged; log in to GOG again"))))]
      {:user-id (str (get d "user_id")) :access-token (get d "access_token")
       :refresh-token (get d "refresh_token") :expires-at (u64 (get d "expires_at"))})))

(defn- token!
  "The token endpoint (a GET): new credentials, or :refused."
  [h {:keys [auth-base client-id client-secret redirect-uri]} grant & kvs]
  (let [url (str (endpoint auth-base "/token") "?client_id=" client-id "&client_secret=" client-secret
                 "&grant_type=" grant "&redirect_uri=" (enc redirect-uri)
                 (apply str (for [[k v] (partition 2 kvs)] (str "&" k "=" (enc v)))))]
    (try
      (let [d (json/read-json (String. (net/get-bytes h url max-token-body {:api? true}) StandardCharsets/UTF_8))]
        {:user-id (str (get d "user_id")) :access-token (get d "access_token") :refresh-token (get d "refresh_token")
         :expires-at (+ (now-s) (long (u64 (get d "expires_in"))))})
      (catch clojure.lang.ExceptionInfo e
        (let [{k ::net/error :keys [status]} (ex-data e)]
          (if (or (= :not-found k) (and (= :status k) (<= 400 (long status) 499)))
            {:refused (or status 404)}
            (throw (net/error (net/kind e) (str "GOG login: " (first (str/split (ex-message e) #"\?")))))))))))

(defn complete-login!
  "Exchanges the pasted redirect URL (or code) for tokens and saves them."
  [h cfg pasted]
  (let [code (code-from-paste pasted)
        r (token! h cfg "authorization_code" "code" code)]
    (when-let [st (:refused r)] (throw (net/error :login (str "GOG refused the login code (HTTP " st "); log in again"))))
    (save-credentials! (:credentials cfg) r)
    r))

;; --- content ---------------------------------------------------------------------------------

(defn content
  "GOG content access: authenticated API calls, manifests, secure links,
  chunks. Fails with :not-logged-in when there's no saved login."
  [h cfg]
  (let [creds (or (load-credentials (:credentials cfg))
                  (throw (net/error :not-logged-in "not logged in to GOG")))]
    {:h h :cfg cfg :creds (atom creds) :lock (ReentrantLock.) :manifests (ConcurrentHashMap.)
     :links (ConcurrentHashMap.) :link-locks (ConcurrentHashMap.)
     :chunks (LinkedHashMap. 64 0.75 true) :chunk-bytes (AtomicLong.) :inflight (ConcurrentHashMap.)}))

(defn- refresh! [{:keys [h cfg creds]} seen]
  ;; another request may have refreshed already
  (when (= seen (:access-token @creds))
    (let [r (token! h cfg "refresh_token" "refresh_token" (:refresh-token @creds))]
      (when (:refused r) (throw (net/error :login-expired "GOG refused the saved login; log in to GOG again")))
      (reset! creds r)
      (try (save-credentials! (:credentials cfg) r) (catch Exception _)))))

(defn- api-get
  "An authenticated GET (bearer token, refreshed when near expiry or refused once)."
  ^bytes [{:keys [h creds ^ReentrantLock lock] :as c} url max]
  (.lock lock)
  (try (when (<= (long (or (:expires-at @creds) 0)) (+ (now-s) expiry-margin-s)) (refresh! c (:access-token @creds)))
       (finally (.unlock lock)))
  (let [call #(net/get-bytes h url max {:api? true :headers {"Authorization" (str "Bearer " (:access-token @creds))}})]
    (try (call)
         (catch clojure.lang.ExceptionInfo e
           (if (= 401 (:status (ex-data e)))
             (do (.lock lock)
                 (try (refresh! c (:access-token @creds)) (finally (.unlock lock)))
                 (try (call)
                      (catch clojure.lang.ExceptionInfo e2
                        (if (= 401 (:status (ex-data e2)))
                          (throw (net/error :login-expired "GOG refused the saved login; log in to GOG again"))
                          (throw e2)))))
             (throw e))))))

(defn builds
  "Builds of `product` for `os` (:windows :osx :linux), newest first."
  [{:keys [cfg] :as c} product os]
  (let [url (str (endpoint (:content-base cfg) (str "/products/" product "/os/" (name os) "/builds")) "?generation=2")]
    (try
      (vec (for [b (get (json/read-json (String. (api-get c url max-json-body) StandardCharsets/UTF_8)) "items")]
             {:build-id (str (get b "build_id")) :product-id (u64 (get b "product_id")) :os (get b "os")
              :version-name (get b "version_name") :generation (or (u64 (get b "generation")) 0)
              :link (get b "link") :date-published (get b "date_published")}))
      (catch clojure.lang.ExceptionInfo e (if (= :not-found (net/kind e)) [] (throw e))))))

(defn build-details
  "A build's details: {:base-product-id :install-directory :depots [{:product-id :manifest :size :languages}]}."
  [{:keys [h]} build]
  (let [d (decode-document (net/get-bytes h (:link build) max-meta-body))]
    {:base-product-id (u64 (get d "baseProductId")) :install-directory (get d "installDirectory")
     :depots (vec (for [x (get d "depots")]
                    {:product-id (u64 (get x "productId")) :manifest (str/lower-case (str (get x "manifest")))
                     :size (or (u64 (get x "size")) 0) :languages (vec (get x "languages"))}))}))

(defn parse-manifest
  "A depot manifest: {:files [{:path :size :chunks :base}] (DepotFile items,
  in order)}. A file in the small-files container is a slice of its chunks."
  [d]
  (let [depot (get d "depot")
        chunk (fn [c] {:md5 (str/lower-case (get c "md5")) :size (long (u64 (get c "size")))
                       :cmd5 (str/lower-case (get c "compressedMd5")) :csize (long (u64 (get c "compressedSize")))})
        container (some->> (get-in depot ["smallFilesContainer" "chunks"]) (mapv chunk))
        container-size (reduce + 0 (map :size container))]
    {:files (vec (for [it (get depot "items") :when (= "DepotFile" (get it "type"))]
                   (let [sfc (get it "sfcRef")
                         chunks (mapv chunk (get it "chunks"))]
                     (if (and sfc container)
                       (let [off (long (u64 (get sfc "offset"))) size (long (u64 (get sfc "size")))]
                         (when (> (+ off size) container-size)
                           (throw (net/error :invalid (str "GOG manifest: " (get it "path") " lies outside its container"))))
                         {:path (get it "path") :size size :chunks container :base off})
                       {:path (get it "path") :size (reduce + 0 (map :size chunks)) :chunks chunks :base 0}))))}))

(defn depot-manifest
  "Manifest `id` (hex), from memory, the disk cache, or the CDN."
  [{:keys [h cfg ^ConcurrentHashMap manifests]} ^String id]
  (let [id (str/lower-case id)]
    (when-not (re-matches #"[0-9a-f]{4,64}" id) (throw (net/error :invalid (str "not a GOG manifest id: " id))))
    (or (.get manifests id)
        (let [f (io/file (:cache-dir cfg) "manifests" id)
              m (or (when (.exists f)
                      (try (parse-manifest (decode-document (java.nio.file.Files/readAllBytes (.toPath f))))
                           (catch Exception _ nil)))
                    (let [body (net/get-bytes h (endpoint (:cdn-meta-base cfg) (str "/content-system/v2/meta/" (subs id 0 2) "/" (subs id 2 4) "/" id)) max-meta-body)
                          m (parse-manifest (decode-document body))]
                      (.mkdirs (.getParentFile f))
                      (let [tmp (io/file (str f "." (System/nanoTime) ".partial"))]
                        (java.nio.file.Files/write (.toPath tmp) ^bytes body ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
                        (java.nio.file.Files/move (.toPath tmp) (.toPath f)
                                                  (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))
                      m))]
          (.put manifests id m)
          m))))

;; --- secure links and chunks ------------------------------------------------------------------

(defn- secure-link
  "Product `product`'s CDN link: {:format :params :path :valid-until}, cached."
  [{:keys [cfg ^ConcurrentHashMap links ^ConcurrentHashMap link-locks] :as c} product]
  (let [ok #(when-let [l (.get links product)] (when (> (long (:valid-until l)) (now-s)) l))]
    (or (ok)
        (let [^ReentrantLock l (.computeIfAbsent link-locks product (reify java.util.function.Function (apply [_ _] (ReentrantLock.))))]
          (.lock l)
          (try
            (or (ok)
                (let [url (str (endpoint (:content-base cfg) (str "/products/" product "/secure_link")) "?generation=2&_version=2&path=/")
                      d (try (json/read-json (String. (api-get c url max-json-body) StandardCharsets/UTF_8))
                             (catch clojure.lang.ExceptionInfo e
                               (if (= 403 (:status (ex-data e)))
                                 (throw (net/error :not-owned (str "this GOG account doesn't own product " product)))
                                 (throw e))))
                      u (first (sort-by #(long (or (u64 (get % "priority")) 0)) (get d "urls")))
                      _ (when-not u (throw (net/error :protocol "GOG gave no CDN URLs")))
                      params (into {} (for [[k v] (get u "parameters")] [k (if (string? v) v (json/write-json-str v))]))
                      link {:format (get u "url_format") :params (dissoc params "path") :path (get params "path")
                            :valid-until (if-let [e (u64 (get params "expires_at"))] (- (long e) link-margin-s) (+ (now-s) link-default-ttl-s))}]
                  (when-not (:path link) (throw (net/error :protocol "GOG's CDN link has no path")))
                  (.put links product link)
                  link))
            (finally (.unlock l)))))))

(defn- fill [{:keys [format params path]} cmd5 show?]
  (reduce (fn [^String s [k v]] (.replace s (str "{" k "}") (if (or show? (= k "base_url")) (str v) "REDACTED")))
          (.replace ^String format "{path}" (str path "/" (subs cmd5 0 2) "/" (subs cmd5 2 4) "/" cmd5))
          params))

(defn- scrub
  "An error message with the link's secrets replaced."
  [msg {:keys [params]}]
  (reduce (fn [^String m [k v]] (if (and (not= k "base_url") (>= (count (str v)) min-secret-len)) (.replace m (str v) "REDACTED") m))
          (str msg) params))

(defn- md5-hex ^String [^bytes b] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "MD5") b)))

(defn- verify-chunk
  "The inflated chunk, after checking both MD5s and the size."
  ^bytes [^bytes body {:keys [md5 size cmd5]}]
  (when (not= cmd5 (md5-hex body)) (throw (net/error :corrupt-part (str "GOG chunk " cmd5 " is corrupt: compressed MD5 differs"))))
  (let [inf (Inflater.) out (byte-array size)]
    (try (.setInput inf body)
         (let [n (.inflate inf out)]
           (when (or (not= n size) (not (.finished inf)))
             (throw (net/error :corrupt-part (str "GOG chunk " cmd5 " is corrupt: inflates to the wrong size")))))
         (catch java.util.zip.DataFormatException _ (throw (net/error :corrupt-part (str "GOG chunk " cmd5 " is corrupt: bad zlib data"))))
         (finally (.end inf)))
    (when (not= md5 (md5-hex out)) (throw (net/error :corrupt-part (str "GOG chunk " cmd5 " is corrupt: MD5 differs"))))
    out))

(defn- download-chunk ^bytes [{:keys [h ^ConcurrentHashMap links] :as c} product ch]
  (let [attempt (fn [link]
                  (net/with-retry h nil
                    (fn []
                      (let [url (fill link (:cmd5 ch) true)]
                        (try
                          (with-open [_ (net/acquire! (:limiter h) (.getHost (java.net.URI. url)))]
                            (let [resp (net/check (net/send! h url {}) url)]
                              (verify-chunk (net/read-body h resp url {:max (+ (long (:csize ch)) chunk-body-slack)}) ch)))
                          (catch clojure.lang.ExceptionInfo e
                            (throw (ex-info (scrub (ex-message e) link) (ex-data e)))))))))
        link (secure-link c product)]
    (try (attempt link)
         (catch clojure.lang.ExceptionInfo e
           (if (#{401 403} (:status (ex-data e)))
             (do (.remove links product link) (attempt (secure-link c product)))
             (throw e))))))

(defn- chunk!
  "Chunk `ch` of `product`: from the LRU, a fetch in flight, or the CDN."
  ^bytes [{:keys [cfg ^LinkedHashMap chunks ^AtomicLong chunk-bytes ^ConcurrentHashMap inflight] :as c} product ch]
  (let [k (:cmd5 ch)]
    (or (locking chunks (.get chunks k))
        (let [mine (CompletableFuture.)
              ^CompletableFuture f (or (.putIfAbsent inflight k mine) mine)]
          (when (identical? f mine)
            (Thread/startVirtualThread
             ^Runnable (fn []
                         (try
                           (let [b (download-chunk c product ch)]
                             (when (and (pos? (long (:chunk-cache-chunks cfg))) (<= (alength b) (long (:chunk-cache-bytes cfg))))
                               (locking chunks
                                 (.put chunks k b)
                                 (.addAndGet chunk-bytes (alength b))
                                 (let [it (.iterator (.entrySet chunks))]
                                   (while (and (.hasNext it) (or (> (.size chunks) (long (:chunk-cache-chunks cfg)))
                                                                 (> (.get chunk-bytes) (long (:chunk-cache-bytes cfg)))))
                                     (let [^java.util.Map$Entry v (.next it)]
                                       (.addAndGet chunk-bytes (- (alength ^bytes (.getValue v))))
                                       (.remove it))))))
                             (.complete mine b))
                           (catch Throwable t (.completeExceptionally mine t))
                           (finally (.remove inflight k))))))
          (try (.get f) (catch java.util.concurrent.ExecutionException e (throw (.getCause e))))))))

(defn read-file!
  "Reads up to `len` bytes of depot file `file` (from the manifest) at `pos`
  into `dst` at `dst-off`; returns the count (short only at EOF)."
  [{:keys [h] :as c} product file pos len ^MemorySegment dst dst-off]
  (let [{:keys [size chunks base]} file
        pos (long pos) size (long size)]
    (if (or (>= pos size) (zero? (long len)))
      0
      (let [n (min (- size pos) (long len))
            start (+ (long base) pos) end (+ start n)
            ends (long-array (reductions + (map :size chunks)))
            first-i (count (take-while #(<= (long %) start) ends))
            last-i (min (count (take-while #(< (long %) end) ends)) (dec (count chunks)))]
        (net/ordered-parallel (:parallel-parts h)
                              (fn [i] (chunk! c product (nth chunks i)))
                              (fn [i ^bytes b]
                                (let [i (long i) ce (aget ends i) cs (- ce (alength b))
                                      s (max start cs) e (min end ce)]
                                  (MemorySegment/copy b (int (- s cs)) dst ValueLayout/JAVA_BYTE (+ (long dst-off) (- s start)) (int (- e s)))))
                              (range first-i (inc last-i)))
        n))))

;; --- the provider -------------------------------------------------------------------------------

(deftype DepotHandle [c product file ^long size]
  prov/Handle
  (handle-stat [_] (Stat. false size tree/mtime))
  (immutable? [_] true)
  (read-into [_ dst dst-off pos len]
    (try (read-file! c product file pos (min (long len) max-read) dst dst-off)
         (catch Exception _ p/io-error)))
  (write-from [_ _ _ _ _] p/read-only)
  (truncate! [_ _] p/read-only)
  (close! [_] nil))

(defn provider
  "Depots ([[product manifest-id] …], earlier wins) as a read-only provider;
  nothing fetched until a read. Best behind storage/cached (10 MiB units: a
  GOG chunk)."
  [c depots]
  (tree/provider (for [[product id] depots
                       file (:files (depot-manifest c id))]
                   {:path (:path file) :size (:size file) :key [product file]})
                 (fn [[product file] size] (DepotHandle. c product file size))
                 {:order :insertion}))
