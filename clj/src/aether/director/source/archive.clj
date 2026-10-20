(ns aether.director.source.archive
  "Random-access archive formats (aether-vfs's aether-archive, without 7z):
  a byte source protocol, seekable zstd, and zip central directories
  (ZIP64, Nexus seek tables).

  Seekable zstd (facebook's contrib format v0.1.0): independent zstd frames,
  then a skippable frame holding the seek table:

    u32 0x184D2A5E  u32 frame size (table length - 8)
    per frame: u32 compressed size, u32 decompressed size [, u32 checksum]
    footer: u32 frame count, u8 descriptor (0x80: checksums; 0x7C reserved), u32 0x8F92EAB1

  A checksum is the low 32 bits of XXH64(frame, seed 0). Nexus's repacked
  zips store each big entry this way (4 MiB frames) and copy its seek-table
  frame into the central-directory extra field 0x4E58; an entry of 4 MiB or
  less is one plain zstd frame."
  (:require [aether.director.path :as path]
            [aether.director.source.net :as net]
            [clojure.string :as str])
  (:import (aether.director Codec)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.channels FileChannel)
           (java.nio.charset Charset StandardCharsets)
           (java.nio.file OpenOption StandardOpenOption)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(defn- invalid [fmt msg] (net/error :invalid (str fmt ": invalid data: " msg)))
(defn- unsupported [fmt msg] (net/error :unsupported (str fmt ": unsupported: " msg)))

(defn- le ^ByteBuffer [^bytes b] (.order (ByteBuffer/wrap b) ByteOrder/LITTLE_ENDIAN))
(defn- u16 ^long [^ByteBuffer b ^long i] (bit-and 0xffff (long (.getShort b (int i)))))
(defn- u32 ^long [^ByteBuffer b ^long i] (bit-and 0xffffffff (long (.getInt b (int i)))))
(defn- u64 ^long [^ByteBuffer b ^long i] (.getLong b (int i)))

(defn fold
  "An archive path for lookups: \\ as /, case folded."
  ^String [^String p]
  (path/fold (str/replace p "\\" "/")))

;; --- byte sources ----------------------------------------------------------------------

(defprotocol RangeRead
  (source-len [r] "The length in bytes.")
  (read-at! [r pos ^bytes buf off len] "Fills buf[off, off+len) from `pos`, exactly, or throws."))

(defn- eof [pos len total]
  (java.io.EOFException. (str "read of " len " bytes at " pos " past end of " total "-byte source")))

(extend-protocol RangeRead
  (Class/forName "[B")
  (source-len [b] (alength ^bytes b))
  (read-at! [b pos buf off len]
    (let [pos (long pos) len (long len)]
      (when (> (+ pos len) (alength ^bytes b)) (throw (eof pos len (alength ^bytes b))))
      (System/arraycopy b pos buf off len))))

(defn read-bytes
  "`len` bytes at `pos` (bounds checked before allocating)."
  ^bytes [r pos len]
  (let [pos (long pos) len (long len) total (long (source-len r))]
    (when (or (neg? pos) (neg? len) (> (+ pos len) total)) (throw (eof pos len total)))
    (let [b (byte-array len)] (read-at! r pos b 0 len) b)))

(defrecord SubRange [inner ^long start ^long end]
  RangeRead
  (source-len [_] (- end start))
  (read-at! [_ pos buf off len]
    (when (> (+ (long pos) (long len)) (- end start)) (throw (eof pos len (- end start))))
    (read-at! inner (+ start (long pos)) buf off len)))

(defn sub-range [inner a b]
  (when (or (neg? (long a)) (> (long a) (long b)) (> (long b) (long (source-len inner))))
    (throw (eof a (- (long b) (long a)) (source-len inner))))
  (->SubRange inner a b))

(defrecord FileRange [^FileChannel ch ^long len]
  RangeRead
  (source-len [_] len)
  (read-at! [_ pos buf off n]
    (when (> (+ (long pos) (long n)) len) (throw (eof pos n len)))
    (let [bb (ByteBuffer/wrap buf (int off) (int n))]
      (loop [p (long pos)]
        (when (.hasRemaining bb)
          (let [k (.read ch bb p)]
            (when (neg? k) (throw (eof pos n len)))
            (recur (+ p k)))))))
  java.io.Closeable
  (close [_] (.close ch)))

(defn file-range [path]
  (let [ch (FileChannel/open (.toPath (java.io.File. (str path))) (into-array OpenOption [StandardOpenOption/READ]))]
    (->FileRange ch (.size ch))))

;; --- seekable zstd ------------------------------------------------------------------------

(def ^:const skippable-magic 0x184D2A5E)
(def ^:const seekable-magic 0x8F92EAB1)
(def ^:const footer-len 9)
(def ^:const max-frame (* 1024 1024 1024))
(def ^:private sz "seekable zstd")

;; frames as parallel primitive arrays: lookups cost no allocation
(defrecord SeekTable [^longs c-offs ^longs c-sizes ^longs d-offs ^longs d-sizes ^ints checksums
                      ^long table-len ^long c-len ^long d-len])

(defn frame-count ^long [^SeekTable t] (alength ^longs (.-c-sizes t)))

(defn table-frame-len
  "The seek-table frame's length from the stream's last 9 bytes."
  ^long [^bytes footer]
  (when (< (alength footer) footer-len) (throw (invalid sz "short footer")))
  (let [b (le footer) o (- (alength footer) footer-len)
        n (u32 b o) desc (bit-and 0xff (long (.get b (int (+ o 4)))))]
    (when (not= seekable-magic (u32 b (+ o 5))) (throw (invalid sz "bad seekable magic")))
    (when (pos? (bit-and desc 0x7C)) (throw (unsupported sz "reserved descriptor bits")))
    (+ 8 (* n (if (pos? (bit-and desc 0x80)) 12 8)) footer-len)))

(defn- build-table [entries checksums? table-len]
  (let [n (count entries)
        co (long-array n) cs (long-array n) do (long-array n) ds (long-array n)
        ck (when checksums? (int-array n))]
    (loop [i 0 c 0 d 0]
      (if (< i n)
        (let [[csz dsz sum] (nth entries i)]
          (when (> (long dsz) max-frame) (throw (unsupported sz (str "frame " i " decompresses to " dsz " bytes"))))
          (aset co i c) (aset cs i (long csz)) (aset do i d) (aset ds i (long dsz))
          (when ck (aset ck i (unchecked-int sum)))
          (recur (inc i) (+ c (long csz)) (+ d (long dsz))))
        (->SeekTable co cs do ds ck table-len c d)))))

(defn parse-seek-table
  "The seek table at the end of `tail` (which ends where the stream does)."
  [^bytes tail]
  (let [tl (table-frame-len tail)]
    (when (> tl (alength tail)) (throw (invalid sz "table longer than the data")))
    (let [o (- (alength tail) tl) b (le tail)
          n (u32 b (- (alength tail) footer-len))
          checks? (pos? (bit-and 0x80 (long (.get b (int (- (alength tail) 5))))))
          w (if checks? 12 8)]
      (when (not= skippable-magic (u32 b o)) (throw (invalid sz "bad skippable magic")))
      (when (not= (- tl 8) (u32 b (+ o 4))) (throw (invalid sz "frame size doesn't match the table")))
      (build-table (vec (for [i (range n) :let [e (+ o 8 (* i w))]]
                          [(u32 b e) (u32 b (+ e 4)) (when checks? (u32 b (+ e 8)))]))
                   checks? tl))))

(defn single-frame
  "A table for one plain zstd frame (no seek table)."
  [c d]
  (build-table [[c d nil]] false 0))

(defn read-seek-table
  "The seek table of a whole seekable stream in `r`."
  [r]
  (let [len (long (source-len r))
        _   (when (< len footer-len) (throw (invalid sz "shorter than a footer")))
        tl  (table-frame-len (read-bytes r (- len footer-len) footer-len))
        _   (when (> tl len) (throw (invalid sz "table longer than the stream")))
        t   (parse-seek-table (read-bytes r (- len tl) tl))]
    (when (not= len (+ (.-c-len ^SeekTable t) tl)) (throw (invalid sz "frames and table don't fill the stream")))
    t))

(defn- first-frame-ending-after
  "The first frame whose decompressed end is > pos."
  ^long [^SeekTable t ^long pos]
  (let [^longs offs (.-d-offs t) ^longs sizes (.-d-sizes t)]
    (loop [lo 0 hi (alength offs)]
      (if (< lo hi)
        (let [mid (quot (+ lo hi) 2)]
          (if (<= (+ (aget offs mid) (aget sizes mid)) pos) (recur (inc mid) hi) (recur lo mid)))
        lo))))

(defn frames-for-range
  "Indices of the frames covering decompressed bytes [a, b), empty frames left out."
  [^SeekTable t ^long a ^long b]
  (when (or (> a b) (> b (.-d-len t))) (throw (invalid sz (str "range " a ".." b " outside " (.-d-len t) " bytes"))))
  (if (= a b)
    []
    (let [^longs offs (.-d-offs t) ^longs sizes (.-d-sizes t)]
      (loop [i (first-frame-ending-after t a) out (transient [])]
        (if (and (< i (alength offs)) (< (aget offs i) b))
          (recur (inc i) (if (pos? (aget sizes i)) (conj! out i) out))
          (persistent! out))))))

(defn decompress-frame!
  "Decompresses frame `i` (its compressed bytes in `src`) into `dst` (exactly
  its decompressed size), checking its checksum if the table has one."
  [^SeekTable t ^long i ^MemorySegment src ^MemorySegment dst]
  (let [csz (aget ^longs (.-c-sizes t) i) dsz (aget ^longs (.-d-sizes t) i)]
    (when (not= csz (.byteSize src)) (throw (invalid sz (str "frame " i ": " (.byteSize src) " compressed bytes, table says " csz))))
    (when (not= dsz (.byteSize dst)) (throw (invalid sz (str "frame " i ": output of " (.byteSize dst) " bytes, table says " dsz))))
    (let [n (try (Codec/decompress dst src)
                 (catch aether.director.Codec$CodecException e (throw (invalid sz (str "frame " i ": " (.getMessage e))))))]
      (when (not= n dsz) (throw (invalid sz (str "frame " i " decompressed to " n " bytes, not " dsz))))
      (when-let [^ints ck (.-checksums t)]
        (let [want (aget ck i) got (unchecked-int (Codec/xxh64 dst 0))]
          (when (not= want got)
            (throw (net/error :checksum (str sz ": checksum mismatch: frame " i)))))))))

(defn read-range
  "Decompressed bytes [a, b) of the seekable stream in `r`."
  ^bytes [r ^SeekTable t ^long a ^long b]
  (let [out (byte-array (- b a))]
    (doseq [i (frames-for-range t a b)]
      (let [i (long i)
            co (aget ^longs (.-c-offs t) i) cs (aget ^longs (.-c-sizes t) i)
            ds (aget ^longs (.-d-offs t) i) dz (aget ^longs (.-d-sizes t) i)
            frame (byte-array dz)]
        (decompress-frame! t i (MemorySegment/ofArray (read-bytes r co cs)) (MemorySegment/ofArray frame))
        (let [s (max a ds) e (min b (+ ds dz))]
          (System/arraycopy frame (- s ds) out (- s a) (- e s)))))
    out))

;; --- zip ---------------------------------------------------------------------------------

(def ^:const method-stored 0)
(def ^:const method-deflate 8)
(def ^:const method-zstd 93)
(def ^:const nexus-seek-table-id 0x4E58)
(def ^:const plain-frame-max (* 4 1024 1024))
(def ^:const local-header-len 30)
(def ^:const zip64-eocd-len 56)
(def ^:const max-tail (+ 22 0xFFFF 20 56))
(def ^:private zf "zip")
(def ^:private cp437 (Charset/forName "IBM437"))

(declare zip64-record)

(defrecord ZipEntry [^String name ^long method ^long flags ^long crc ^long csize ^long usize
                     ^long lh-offset seek-table ^long name-len])

(defn dir? [^ZipEntry e] (str/ends-with? (.-name e) "/"))

(defn locate-central-directory
  "From the last bytes of a zip (`tail`, starting at `tail-start` of a
  `file-len`-byte file): {:offset :size :entries} of the central directory,
  or {:need-zip64 pos} when its ZIP64 record lies before the tail."
  [^bytes tail ^long tail-start ^long file-len]
  (let [n (alength tail)
        _ (when (or (< n 22) (and (pos? tail-start) (< n max-tail))) (throw (invalid zf "tail too short")))
        b (le tail)
        find (fn [exact?]
               (loop [i (- n 22)]
                 (when (>= i 0)
                   (if (and (= 0x06054b50 (u32 b i))
                            (let [end (+ i 22 (u16 b (+ i 20)))] (if exact? (= end n) (<= end n))))
                     i (recur (dec i))))))
        i (or (find true) (find false) (throw (invalid zf "no end of central directory record")))
        disk (u16 b (+ i 4)) cd-disk (u16 b (+ i 6))
        entries (u16 b (+ i 10)) size (u32 b (+ i 12)) offset (u32 b (+ i 16))]
    (when (and (or (pos? disk) (pos? cd-disk)) (not= 0xFFFF disk) (not= 0xFFFF cd-disk))
      (throw (unsupported zf "multi-disk archive")))
    (if (and (>= i 20) (= 0x07064b50 (u32 b (- i 20))))
      (let [l (- i 20)]
        (when (> (u32 b (+ l 16)) 1) (throw (unsupported zf "multi-disk archive")))
        (let [pos (u64 b (+ l 8))]
          (when (or (neg? pos) (> (+ pos zip64-eocd-len) file-len)) (throw (invalid zf "ZIP64 record out of range")))
          (if (< pos tail-start)
            {:need-zip64 pos}
            (let [o (- pos tail-start)]
              (when (> (+ o zip64-eocd-len) n) (throw (invalid zf "ZIP64 record runs past the end")))
              (zip64-record (Arrays/copyOfRange tail (int o) (int (+ o zip64-eocd-len))) file-len)))))
      (do (when (or (= entries 0xFFFF) (= size 0xFFFFFFFF) (= offset 0xFFFFFFFF))
            (throw (invalid zf "ZIP64 markers without a ZIP64 locator")))
          (when (> (+ offset size) file-len) (throw (invalid zf "central directory out of range")))
          {:offset offset :size size :entries entries}))))

(defn zip64-record
  "The central directory from a ZIP64 end record."
  [^bytes rec ^long file-len]
  (let [b (le rec)]
    (when (or (< (alength rec) zip64-eocd-len) (not= 0x06064b50 (u32 b 0))) (throw (invalid zf "bad ZIP64 end record")))
    (when (or (pos? (u32 b 16)) (pos? (u32 b 20))) (throw (unsupported zf "multi-disk archive")))
    (let [entries (u64 b 32) size (u64 b 40) offset (u64 b 48)]
      (when (or (neg? size) (neg? offset) (> (+ offset size) file-len)) (throw (invalid zf "central directory out of range")))
      {:offset offset :size size :entries entries})))

(defn- decode-name ^String [^bytes raw ^long flags]
  (if (pos? (bit-and flags 0x0800))
    (String. raw StandardCharsets/UTF_8)
    (let [dec (.newDecoder StandardCharsets/UTF_8)]
      (try (str (.decode dec (ByteBuffer/wrap raw)))
           (catch java.nio.charset.CharacterCodingException _ (String. raw ^Charset cp437))))))

(defn- nexus-table
  "The seek table in a 0x4E58 extra field, checked against the entry's sizes."
  [^bytes payload ^long csize ^long usize]
  (let [t (try (parse-seek-table payload) (catch clojure.lang.ExceptionInfo _ nil))]
    (when-not (and t (= (alength payload) (.-table-len ^SeekTable t))
                   (= usize (.-d-len ^SeekTable t))
                   (= csize (+ (.-c-len ^SeekTable t) (.-table-len ^SeekTable t))))
      (throw (invalid zf "corrupt Nexus seek table")))
    t))

(defn parse-central-directory
  "The entries of central directory bytes `cd` (`count` entries)."
  [^bytes cd ^long count]
  (when (> count (quot (alength cd) 46)) (throw (invalid zf (str count " entries can't fit in " (alength cd) " bytes"))))
  (let [b (le cd) n (alength cd)]
    (loop [i 0 at 0 out (transient [])]
      (if (= i count)
        (persistent! out)
        (do
          (when (or (> (+ at 46) n) (not= 0x02014b50 (u32 b at))) (throw (invalid zf (str "bad central header " i))))
          (let [flags (u16 b (+ at 8)) method (u16 b (+ at 10)) crc (u32 b (+ at 16))
                csize (u32 b (+ at 20)) usize (u32 b (+ at 24))
                nlen (u16 b (+ at 28)) xlen (u16 b (+ at 30)) clen (u16 b (+ at 32))
                disk (u16 b (+ at 34)) lh (u32 b (+ at 42))
                nstart (+ at 46) xstart (+ nstart nlen) next (+ xstart xlen clen)
                _ (when (> next n) (throw (invalid zf (str "central header " i " runs past the end"))))
                name (decode-name (Arrays/copyOfRange cd (int nstart) (int xstart)) flags)
                ;; the extra fields: ZIP64 sizes, a Nexus seek table
                [usize csize lh table]
                (loop [x xstart usize usize csize csize lh lh table nil]
                  (if (<= (+ x 4) (+ xstart xlen))
                    (let [id (u16 b x) len (u16 b (+ x 2)) d (+ x 4)]
                      (when (> (+ d len) (+ xstart xlen)) (throw (invalid zf (str "extra field of entry " i " runs past its end"))))
                      (case (int id)
                        1 (let [need (fn [v] (= v 0xFFFFFFFF))
                                fields (cond-> [] (need usize) (conj :u) (need csize) (conj :c) (need lh) (conj :l))
                                vals (into {} (map-indexed (fn [k f]
                                                             (when (> (* 8 (inc k)) len) (throw (invalid zf (str "short ZIP64 field in entry " i))))
                                                             [f (u64 b (+ d (* 8 k)))])
                                                           fields))]
                            (recur (+ d len) (long (get vals :u usize)) (long (get vals :c csize)) (long (get vals :l lh)) table))
                        0x4E58 (recur (+ d len) usize csize lh (Arrays/copyOfRange cd (int d) (int (+ d len))))
                        (recur (+ d len) usize csize lh table)))
                    [usize csize lh table]))]
            (when (or (= usize 0xFFFFFFFF) (= csize 0xFFFFFFFF) (= lh 0xFFFFFFFF) (= disk 0xFFFF))
              (throw (invalid zf (str "entry " i " needs a ZIP64 extra field"))))
            (recur (inc i) next
                   (conj! out (->ZipEntry name method flags crc csize usize lh
                                          (when table (nexus-table table csize usize)) nlen)))))))))

(defrecord ZipIndex [entries by-name])

(defn zip-index [entries]
  (->ZipIndex entries
              (persistent! (reduce (fn [m [i ^ZipEntry e]]
                                     (let [k (fold (.-name e))] (if (contains? m k) m (assoc! m k i))))
                                   (transient {}) (map-indexed vector entries)))))

(defn find-entry
  "The entry at `p` (case-insensitive, \\ or /), or nil."
  [^ZipIndex z p]
  (some->> (get (.-by-name z) (fold p)) (nth (.-entries z))))

(defn read-index
  "The central directory of the zip in `r`."
  [r]
  (let [len (long (source-len r))
        n (min len max-tail)
        tail (read-bytes r (- len n) n)
        loc (locate-central-directory tail (- len n) len)
        {:keys [offset size entries]} (if-let [p (:need-zip64 loc)]
                                        (zip64-record (read-bytes r p zip64-eocd-len) len)
                                        loc)]
    (zip-index (parse-central-directory (read-bytes r offset size) entries))))

(defn frame-map
  "How a zstd entry's frames lie: its Nexus table, one plain frame (4 MiB or
  less), or nil (read the table from the end of its data)."
  [^ZipEntry e]
  (when (not= method-zstd (.-method e)) (throw (unsupported zf (str (.-name e) ": method " (.-method e) " isn't zstd"))))
  (or (.-seek-table e)
      (when (<= (.-usize e) plain-frame-max) (single-frame (.-csize e) (.-usize e)))))

(defn local-data-range
  "[start end) of an entry's data from its local header (30 bytes, then the
  name and extra lengths). The size is the central directory's."
  [^bytes lh ^ZipEntry e]
  (when (pos? (bit-and 1 (.-flags e))) (throw (unsupported zf (str (.-name e) " is encrypted"))))
  (let [b (le lh)]
    (when (or (< (alength lh) local-header-len) (not= 0x04034b50 (u32 b 0)))
      (throw (invalid zf (str "bad local header for " (.-name e)))))
    (let [start (+ (.-lh-offset e) local-header-len (u16 b 26) (u16 b 28))]
      (when (neg? start) (throw (invalid zf "offset overflow")))
      [start (+ start (.-csize e))])))

(defn entry-data-range [r ^ZipEntry e]
  (let [[s e* :as rg] (local-data-range (read-bytes r (.-lh-offset e) local-header-len) e)]
    (when (> (long e*) (long (source-len r))) (throw (invalid zf (str (.-name e) " runs past the end"))))
    rg))

(defn read-entry
  "A whole entry, decompressed (stored, deflate, zstd)."
  ^bytes [r ^ZipEntry e]
  (let [[s end] (entry-data-range r e)
        data (read-bytes r s (- (long end) (long s)))
        usize (.-usize e)]
    (case (int (.-method e))
      0 (do (when (not= usize (alength data)) (throw (invalid zf (str (.-name e) ": stored size differs"))))
            data)
      8 (let [inf (java.util.zip.Inflater. true) out (byte-array usize)]
          (try (.setInput inf data)
               (let [n (.inflate inf out)]
                 (when (or (not= n usize) (not (.finished inf))) (throw (invalid zf (str (.-name e) ": inflated size differs"))))
                 out)
               (finally (.end inf))))
      93 (let [t (or (frame-map e) (read-seek-table data))]
           (read-range data t 0 usize))
      (throw (unsupported zf (str (.-name e) ": compression method " (.-method e)))))))
