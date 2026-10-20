(ns aether.director.source.steam.codec
  "Steam's content formats (aether-vfs's aether-steam manifest.rs and
  chunk.rs): depot manifests and chunks.

  A manifest (format 5) is sections, each u32 magic, u32 length, protobuf:
  0x71F617D0 payload (file mappings), 0x1F4812BE metadata, 0x1B81B817
  signature (skipped), 0x32C415AB end (no length); usually inside a zip of
  one entry. File names may be encrypted with the depot key:
  base64(AES-256-ECB(IV) || AES-256-CBC-PKCS7(name + NUL, IV)).

  A chunk is AES-256-ECB(IV) || AES-256-CBC-PKCS7(payload, IV); the payload
  is VSZa (zstd), VZa (LZMA) or a zip of one entry, and the result is checked
  against its size, Steam's Adler-32 (seed 0) and SHA-1 (the chunk's id)."
  (:require [aether.director.source.archive :as ar]
            [aether.director.source.net :as net]
            [clojure.string :as str])
  (:import (aether.director Codec)
           (java.lang.foreign MemorySegment)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Arrays Base64 HexFormat)
           (javax.crypto Cipher)
           (javax.crypto.spec IvParameterSpec SecretKeySpec)
           (org.tukaani.xz LZMAInputStream)))

(set! *warn-on-reflection* true)

(def ^:const max-chunk (* 32 1024 1024))
(def ^:const max-manifest (* 256 1024 1024))
(def ^:const flag-directory 0x40)

(defn- integrity [msg] (net/error :integrity msg))

;; --- protobuf, the little we need ------------------------------------------------------------

(defn- varint
  "[value next-offset] of the varint at `i`."
  [^bytes b ^long i]
  (loop [i i shift 0 v 0]
    (when (>= i (alength b)) (throw (integrity "truncated protobuf varint")))
    (let [x (bit-and 0xff (long (aget b i)))
          v (bit-or v (bit-shift-left (bit-and x 0x7f) shift))]
      (if (zero? (bit-and x 0x80)) [v (inc i)] (recur (inc i) (+ shift 7) v)))))

(defn pb-fields
  "Calls (f field wire-type value) for each field of the message in b[from,
  to): varints and fixed ints as longs, length-delimited fields as [start end]."
  [^bytes b ^long from ^long to f]
  (loop [i from]
    (when (< i to)
      (let [[tag i] (varint b i) i (long i)
            field (unsigned-bit-shift-right (long tag) 3) wt (bit-and (long tag) 7)]
        (case (int wt)
          0 (let [[v n] (varint b i)] (f field 0 v) (recur (long n)))
          1 (do (f field 1 (.getLong (.order (ByteBuffer/wrap b) ByteOrder/LITTLE_ENDIAN) (int i))) (recur (+ i 8)))
          2 (let [[len n] (varint b i) s (long n) e (+ s (long len))]
              (when (> e to) (throw (integrity "truncated protobuf field")))
              (f field 2 [s e]) (recur e))
          5 (do (f field 5 (bit-and 0xffffffff (long (.getInt (.order (ByteBuffer/wrap b) ByteOrder/LITTLE_ENDIAN) (int i))))) (recur (+ i 4)))
          (throw (integrity (str "protobuf wire type " wt))))))))

(defn- sub ^bytes [^bytes b [s e]] (Arrays/copyOfRange b (int s) (int e)))
(defn- utf8 ^String [^bytes b [s e]] (String. b (int s) (int (- (long e) (long s))) StandardCharsets/UTF_8))

;; --- AES ---------------------------------------------------------------------------------------

(defn- aes ^Cipher [mode ^bytes key ^bytes iv]
  (doto (Cipher/getInstance (if iv "AES/CBC/PKCS5Padding" "AES/ECB/NoPadding"))
    (.init (int mode) (SecretKeySpec. key "AES") ^java.security.spec.AlgorithmParameterSpec (when iv (IvParameterSpec. iv)))))

(defn decrypt
  "Steam's envelope: ECB(IV, 16 bytes) || CBC-PKCS7(payload, IV)."
  ^bytes [^bytes key ^bytes raw]
  (when (< (alength raw) 32) (throw (integrity "encrypted data too short")))
  (let [iv (.doFinal (aes Cipher/DECRYPT_MODE key nil) raw 0 16)]
    (.doFinal (aes Cipher/DECRYPT_MODE key iv) raw 16 (- (alength raw) 16))))

(defn encrypt
  "The envelope around `plain`, with `iv` (for fixtures)."
  ^bytes [^bytes key ^bytes iv ^bytes plain]
  (let [head (.doFinal (aes Cipher/ENCRYPT_MODE key nil) iv)
        body (.doFinal (aes Cipher/ENCRYPT_MODE key iv) plain)]
    (byte-array (concat head body))))

(defn decrypt-name
  "A manifest's encrypted file name."
  ^String [^bytes key ^String b64]
  (let [^bytes plain (try (decrypt key (.decode (Base64/getDecoder) (str/replace b64 #"\s" "")))
                   (catch Exception e (throw (integrity (str "file names: " (ex-message e))))))
        n (let [i (.indexOf (String. plain StandardCharsets/ISO_8859_1) (int 0))] (if (neg? i) (alength plain) i))]
    (String. plain 0 (int n) StandardCharsets/UTF_8)))

;; --- checksums ----------------------------------------------------------------------------------

(defn adler32
  "Steam's Adler-32: seeded with 0, not 1 (java.util.zip.Adler32 can't)."
  ^long [^bytes b]
  (let [n (alength b)]
    (loop [i 0 a 0 s 0]
      (if (< i n)
        ;; 5552 bytes keep the sums below 2^32 between reductions
        (let [end (min n (+ i 5552))
              [a s] (loop [j i a a s s] (if (< j end) (let [a (+ a (bit-and 0xff (long (aget b j))))] (recur (inc j) a (+ s a))) [a s]))]
          (recur end (rem (long a) 65521) (rem (long s) 65521)))
        (bit-or (bit-shift-left s 16) a)))))

(defn sha1 ^bytes [^bytes b] (.digest (MessageDigest/getInstance "SHA-1") b))
(defn hex ^String [^bytes b] (.formatHex (HexFormat/of) b))

;; --- manifests -----------------------------------------------------------------------------------

(defn fold-path
  "A depot path for lookups: \\ as /, no leading /, case folded."
  ^String [^String p]
  (ar/fold (str/replace (str/replace p "\\" "/") #"^/+" "")))

(defn- unzip-single
  "The one entry of a zip (a manifest's container), at most `cap` bytes."
  ^bytes [^bytes body cap]
  (let [idx (ar/read-index body)
        es (:entries idx)]
    (when (not= 1 (count es)) (throw (integrity (str "a manifest zip holds " (count es) " entries"))))
    (let [e (first es)]
      (when (> (long (:usize e)) (long cap)) (throw (integrity "manifest larger than the cap")))
      (ar/read-entry body e))))

(defn parse-manifest
  "A depot manifest from its CDN body, checked: for `depot` and `gid`, names
  decrypted with `key`, regular files only, chunks tiling each file exactly.
  {:depot :gid :files [{:path :size :sha1 :chunks [{:id :offset :len :adler}]}]}."
  [^bytes body depot gid ^bytes key]
  (let [b (if (and (>= (alength body) 4) (= 0x04034b50 (.getInt (.order (ByteBuffer/wrap body) ByteOrder/LITTLE_ENDIAN) 0)))
            (unzip-single body max-manifest)
            body)
        bb (.order (ByteBuffer/wrap b) ByteOrder/LITTLE_ENDIAN)
        sections (loop [i 0 out {}]
                   (if (>= (+ i 4) (alength b))
                     out
                     (let [magic (bit-and 0xffffffff (long (.getInt bb (int i))))]
                       (if (= magic 0x32C415AB)
                         out
                         (let [len (bit-and 0xffffffff (long (.getInt bb (int (+ i 4)))))
                               s (+ i 8) e (+ s len)]
                           (when (> e (alength b)) (throw (integrity "truncated manifest section")))
                           (recur e (assoc out magic [s e])))))))
        [ms me] (or (sections 0x1F4812BE) (throw (integrity "the manifest has no metadata section")))
        md (let [m (volatile! {})]
               (pb-fields b ms me (fn [f _ v] (case (long f) 1 (vswap! m assoc :depot v) 2 (vswap! m assoc :gid v)
                                                 4 (vswap! m assoc :encrypted (= 1 v)) nil)))
               @m)
        _ (when (or (not= (long depot) (long (or (:depot md) -1))) (not= (long gid) (long (or (:gid md) -1))))
            (throw (integrity (str "the manifest is for depot " (:depot md) " / " (:gid md) ", not " depot " / " gid))))
        [ps pe] (or (sections 0x71F617D0) (throw (integrity "the manifest has no payload")))
        files (volatile! [])]
    (pb-fields b ps pe
               (fn [f _ v]
                 (when (= 1 f)
                   (let [[s e] v m (volatile! {:chunks []})]
                     (pb-fields b s e
                                (fn [f _ v]
                                  (case (long f)
                                    1 (vswap! m assoc :name (utf8 b v))
                                    2 (vswap! m assoc :size v)
                                    3 (vswap! m assoc :flags v)
                                    5 (vswap! m assoc :sha1 (sub b v))
                                    7 (vswap! m assoc :link (utf8 b v))
                                    6 (let [[cs ce] v c (volatile! {:offset 0})]
                                        (pb-fields b cs ce (fn [f _ v]
                                                             (case (long f)
                                                               1 (vswap! c assoc :id (sub b v))
                                                               2 (vswap! c assoc :adler v)
                                                               3 (vswap! c assoc :offset v)
                                                               4 (vswap! c assoc :len v)
                                                               nil)))
                                        (vswap! m update :chunks conj @c))
                                    nil)))
                     (vswap! files conj @m)))))
    (let [files (for [{:keys [name size flags link] :as m} @files
                      :when (and (zero? (bit-and (long (or flags 0)) flag-directory)) (str/blank? link))]
                  (let [path (if (:encrypted md) (decrypt-name key name) name)
                        chunks (vec (sort-by :offset (:chunks m)))
                        size (long (or size 0))]
                    (doseq [{:keys [id len]} chunks]
                      (when (or (nil? id) (every? zero? id)) (throw (integrity (str path ": a chunk with no id"))))
                      (when (or (nil? len) (zero? (long len)) (> (long len) max-chunk)) (throw (integrity (str path ": a chunk of " len " bytes")))))
                    (let [end (reduce (fn [^long at {:keys [offset len]}]
                                        (when (not= at (long offset)) (throw (integrity (str path ": chunks leave a gap or overlap at " at))))
                                        (+ at (long len)))
                                      0 chunks)]
                      (when (not= end size) (throw (integrity (str path ": chunks cover " end " of " size " bytes")))))
                    {:path path :size size :sha1 (:sha1 m)
                     :chunks (mapv #(select-keys % [:id :offset :len :adler]) chunks)}))
          files (vec files)]
      (let [dups (->> files (map (comp fold-path :path)) frequencies (filter #(> (long (val %)) 1)))]
        (when (seq dups) (throw (integrity (str "two files at " (ffirst dups))))))
      {:depot depot :gid gid :files files})))

(defn chunks-for
  "Indices [first, last) of the chunks covering [off, off + len) of `file`."
  [{:keys [size chunks]} ^long off ^long len]
  (let [end (min (+ off len) (long size))]
    (if (>= off end)
      [0 0]
      (let [n (count chunks)
            lo (loop [lo 0 hi n] (if (< lo hi) (let [m (quot (+ lo hi) 2) {:keys [offset len]} (nth chunks m)]
                                                   (if (<= (+ (long offset) (long len)) off) (recur (inc m) hi) (recur lo m)))
                                     lo))
            hi (loop [lo (long lo) hi n] (if (< lo hi) (let [m (quot (+ lo hi) 2)]
                                                  (if (< (long (:offset (nth chunks m))) end) (recur (inc m) hi) (recur lo m)))
                                    lo))]
        [lo hi]))))

;; --- chunks ---------------------------------------------------------------------------------------

(defn- le32 ^long [^bytes b ^long i] (bit-and 0xffffffff (long (.getInt (.order (ByteBuffer/wrap b) ByteOrder/LITTLE_ENDIAN) (int i)))))

(defn decode-chunk
  "The plain chunk `c` ({:id :len :adler}) from its CDN bytes, checked."
  ^bytes [^bytes key {:keys [^bytes id len adler]} ^bytes raw]
  (let [len (long len) what (str "chunk " (hex id))]
    (when (> len max-chunk) (throw (integrity (str what ": declared " len " bytes"))))
    (let [^bytes p (try (decrypt key raw) (catch Exception e (throw (integrity (str what ": " (ex-message e))))))
          plain
          (cond
            (and (>= (alength p) 19) (= "VSZa" (String. p 0 4 StandardCharsets/ISO_8859_1)))
            (let [size (le32 p (- (alength p) 11))
                  out (byte-array len)]
              (when (or (not= size len) (not= "zsv" (String. p (- (alength p) 3) 3 StandardCharsets/ISO_8859_1)))
                (throw (integrity (str what ": size mismatch"))))
              (let [n (try (Codec/decompress (MemorySegment/ofArray out) (MemorySegment/ofArray (Arrays/copyOfRange p 8 (- (alength p) 15))))
                           (catch Exception e (throw (integrity (str what ": " (ex-message e))))))]
                (when (not= n len) (throw (integrity (str what ": size mismatch"))))
                out))
            (and (>= (alength p) 22) (= "VZa" (String. p 0 3 StandardCharsets/ISO_8859_1)))
            (let [size (le32 p (- (alength p) 6))
                  props (aget p 7)
                  dict (le32 p 8)]
              (when (or (not= size len) (not= "zv" (String. p (- (alength p) 2) 2 StandardCharsets/ISO_8859_1)))
                (throw (integrity (str what ": size mismatch"))))
              (with-open [in (LZMAInputStream. (java.io.ByteArrayInputStream. p 12 (- (alength p) 12 10)) len props (int dict))]
                (let [out (.readNBytes in (int len))]
                  (when (not= len (alength out)) (throw (integrity (str what ": size mismatch"))))
                  out)))
            (and (>= (alength p) 4) (= 0x04034b50 (le32 p 0)))
            (let [out (unzip-single p len)]
              (when (not= len (alength out)) (throw (integrity (str what ": size mismatch"))))
              out)
            :else (throw (integrity (str what ": unsupported chunk format (raw LZMA or unknown)"))))]
      (when (not= (long adler) (adler32 plain)) (throw (integrity (str what ": Adler-32 mismatch"))))
      (when-not (Arrays/equals id (sha1 plain)) (throw (integrity (str what ": SHA-1 differs from its id"))))
      plain)))

(defn encode-chunk
  "A VSZa chunk around `plain` (for fixtures): [raw {:id :len :adler}]."
  [^bytes key ^bytes plain]
  (let [z (Codec/compress (MemorySegment/ofArray plain) 3)
        bb (.order (ByteBuffer/allocate (+ 8 (alength z) 15)) ByteOrder/LITTLE_ENDIAN)]
    (.put bb (.getBytes "VSZa" StandardCharsets/ISO_8859_1)) (.putInt bb 0) (.put bb z)
    (.putInt bb 0) (.putLong bb (alength plain)) (.put bb (.getBytes "zsv" StandardCharsets/ISO_8859_1))
    [(encrypt key (byte-array 16 (byte 0x17)) (.array bb))
     {:id (sha1 plain) :len (alength plain) :adler (adler32 plain)}]))
