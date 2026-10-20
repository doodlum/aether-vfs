(ns aether.director.source.wj-cdn
  "The Wabbajack CDN (aether-vfs's aether-wj-cdn): a file is a gzipped JSON
  definition plus parts, each checked against its xxHash64 and fetched in
  parallel. Whole archives only: nothing to mount until extracted, so this is
  a downloader, not a provider.

  A file's URL may carry a token in its query: it's sent, never shown."
  (:require [aether.director.source.net :as net]
            [charred.api :as json]
            [clojure.string :as str])
  (:import (aether.director Codec Codec$Xxh64)
           (java.lang.foreign MemorySegment)
           (java.net URI)
           (java.nio.charset StandardCharsets)
           (java.util Locale)
           (java.util.zip GZIPInputStream)))

(set! *warn-on-reflection* true)

(def ^:const max-definition (* 64 1024 1024))
(def ^:const max-part (* 64 1024 1024))

(def domain-remaps
  "Legacy Bunny CDN hosts Wabbajack still lists, and their current names."
  {"wabbajack.b-cdn.net"         "authored-files.wabbajack.org"
   "wabbajack-mirror.b-cdn.net"  "mirror.wabbajack.org"
   "wabbajack-patches.b-cdn.net" "patches.wabbajack.org"
   "wabbajacktest.b-cdn.net"     "test-files.wabbajack.org"})

(defn remap-url
  "`url` with a legacy CDN host replaced; everything else kept as is."
  ^String [^String url]
  (let [u (URI. url)]
    (if-let [h (get domain-remaps (.toLowerCase (str (.getHost u)) Locale/ROOT))]
      (str (URI. (.getScheme u) (.getRawUserInfo u) h (.getPort u) "/" nil nil)
           (subs (.getRawPath u) 1)
           (when (.getRawQuery u) (str "?" (.getRawQuery u)))
           (when (.getRawFragment u) (str "#" (.getRawFragment u))))
      url)))

(defn cdn-file
  "A CDN file at `url` (remapped), fetched through net base `h`."
  [h ^String url]
  (let [u (try (URI. (remap-url url)) (catch Exception _ (throw (net/error :protocol (str "bad URL " (net/redact url))))))]
    {:h h :uri u :label (str/replace (net/redact (str u)) #"/+$" "")}))

(defn- join
  "The file's URL with `suffix` appended to its path; the query (a token, maybe) kept."
  ^String [{:keys [^URI uri]} suffix]
  (str (.getScheme uri) "://" (.getRawAuthority uri)
       (str/replace (str (.getRawPath uri)) #"/+$" "") "/" suffix
       (when (.getRawQuery uri) (str "?" (.getRawQuery uri)))))

(defn parse-definition
  "A definition from its body (gzipped or plain JSON), checked: parts in
  order, contiguous, non-empty, within the part cap, summing to the size."
  [^bytes body]
  (let [json-bytes (if (and (>= (alength body) 2) (= 0x1f (bit-and 0xff (aget body 0))) (= 0x8b (bit-and 0xff (aget body 1))))
                     (with-open [in (GZIPInputStream. (java.io.ByteArrayInputStream. body))]
                       (let [b (.readNBytes in (inc max-definition))]
                         (when (> (alength b) max-definition) (throw (net/error :protocol "CDN definition too large")))
                         b))
                     body)
        d (json/read-json (String. ^bytes json-bytes StandardCharsets/UTF_8))
        size (long (get d "Size"))
        parts (mapv (fn [p] {:size (long (get p "Size")) :offset (long (get p "Offset"))
                             :hash (net/b64-xxh64 (get p "Hash")) :index (long (get p "Index"))})
                    (get d "Parts"))
        total (reduce (fn [^long at [i {:keys [^long size ^long offset ^long index]}]]
                        (when (or (not= index i) (not= offset at) (<= size 0))
                          (throw (net/error :protocol (str "CDN definition: part " i " is out of order or empty"))))
                        (when (> size max-part)
                          (throw (net/error :protocol (str "CDN definition: part " i " is larger than the " max-part "-byte limit"))))
                        (try (Math/addExact at size)
                             (catch ArithmeticException _ (throw (net/error :protocol "CDN definition: part offsets overflow")))))
                      0 (map-indexed vector parts))]
    (when (not= total size) (throw (net/error :protocol (str "CDN definition: parts sum to " total ", not " size))))
    {:original-file-name (get d "OriginalFileName") :size size :hash (net/b64-xxh64 (get d "Hash"))
     :parts parts :munged-name (get d "MungedName" "")}))

(defn definition
  "The file's definition."
  [{:keys [h label] :as f}]
  (net/with-job [job h (str "cdn definition " label) nil]
    (let [url (join f "definition.json.gz")]
      (net/with-retry h job
        (fn []
          (with-open [_ (net/acquire! (:limiter h) (.getHost ^URI (:uri f)))]
            (parse-definition (net/read-body h (net/check (net/send! h url {}) url) url {:max max-definition :job job}))))))))

(defn download!
  "Downloads the file into `sink` (aether.director.source.net/Sink), parts
  in parallel and in order, each checked; the whole file's hash is checked
  against the definition's and `expected-hash` (an xxHash64 long) when given.
  Returns the definition."
  [{:keys [h label] :as f} sink & [expected-hash]]
  (let [d (definition f)]
    (net/with-job [job h (str "cdn " (:original-file-name d)) (:size d)]
      (with-open [x (Codec$Xxh64. 0)]
        (net/ordered-parallel
         (:parallel-parts h)
         (fn [{:keys [index size hash]}]
           (net/with-retry h job
             (fn []
               (let [url (join f (str "parts/" index))
                     data (with-open [_ (net/acquire! (:limiter h) (.getHost ^URI (:uri f)))]
                            (net/read-body h (net/check (net/send! h url {}) url) url {:expect size :job job}))
                     got (Codec/xxh64 (MemorySegment/ofArray ^bytes data) 0)]
                 (when (not= (long hash) got)
                   (throw (net/error :corrupt-part (str (:original-file-name d) " part " index " is corrupt: xxHash64 "
                                                        (net/xxh64-b64 got) ", expected " (net/xxh64-b64 hash)))))
                 data))))
         (fn [{:keys [offset]} ^bytes data]
           (.update x data 0 (alength data))
           (net/write-at! sink offset data))
         (:parts d))
        (let [got (.digest x)]
          (doseq [want (cond-> [(:hash d)] expected-hash (conj expected-hash))]
            (when (not= (long want) got)
              (throw (net/error :hash-mismatch (str label ": expected xxHash64 " (net/xxh64-b64 want) ", got " (net/xxh64-b64 got))
                                {:expected want :actual got}))))
          d)))))
