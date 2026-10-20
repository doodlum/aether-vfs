(ns aether.director.store.pack
  "Pack files: the block store's data, byte-compatible with the Rust store's.

  A pack is records back to back, no file header. A record is a 40-byte
  little-endian header and a payload:

    0  u32  magic, the bytes \"SBLK\"
    4  u8   flags: bit 0 = the payload is one zstd frame (else raw bytes)
    5  3    reserved, zero
    8  u32  raw length (the block's)
    12 u32  stored length (the payload's)
    16 16   BLAKE3-128 of the raw block
    32 u64  xxh3-64 of the payload

  The appender owns the active pack: whole batches of records are written
  with one positional write each, so a reader sees them at once (nothing is
  buffered here), and `sync!` forces them before a commit points at them."
  (:require [clojure.java.io :as io])
  (:import (aether.director Codec)
           (java.io File)
           (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.channels FileChannel)
           (java.nio.file StandardOpenOption)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.concurrent.locks ReentrantLock)))

(set! *warn-on-reflection* true)

(def ^:const header-len 40)
(def ^:const flag-compressed 1)
(def ^:const magic 0x4B4C4253)                             ; "SBLK" read little-endian

(defn file-name ^String [id] (format "%08d.pack" (long id)))

(defn pack-file ^File [dir id] (io/file dir "packs" (file-name id)))

(defn pack-id
  "The id in a pack file's name, or nil for any other file."
  [^String name]
  (when-let [[_ n] (re-matches #"(\d{8})\.pack" name)] (parse-long n)))

;; --- records -----------------------------------------------------------------------

(defn encode
  "The record for raw block `data` (bytes) with BLAKE3-128 `hash`: the zstd
  frame at `level` when it's smaller than the block, else the block itself.
  Returns {:bytes record :raw-len :stored-len}."
  [^bytes data ^bytes hash level]
  (let [frame  (Codec/compress (MemorySegment/ofArray data) (int level))
        comp?  (< (alength frame) (alength data))
        ^bytes payload (if comp? frame data)
        n      (alength payload)
        bb     (.order (ByteBuffer/allocate (+ header-len n)) ByteOrder/LITTLE_ENDIAN)]
    (.putInt bb (unchecked-int magic))
    (.put bb (byte (if comp? flag-compressed 0)))
    (.put bb (byte-array 3))
    (.putInt bb (alength data))
    (.putInt bb n)
    (.put bb hash)
    (.putLong bb (Codec/xxh3 (MemorySegment/ofArray payload)))
    (.put bb payload)
    {:bytes (.array bb) :raw-len (alength data) :stored-len n}))

(defn header
  "The header at `off` of segment `seg`, or nil if it isn't a valid one:
  {:flags :raw-len :stored-len :hash :checksum}."
  [^MemorySegment seg ^long off]
  (when (<= (+ off header-len) (.byteSize seg))
    (let [u32 (fn [^long o] (Integer/toUnsignedLong (.get seg ValueLayout/JAVA_INT_UNALIGNED (+ off o))))
          flags (bit-and 0xff (.get seg ValueLayout/JAVA_BYTE (+ off 4)))]
      (when (and (= magic (u32 0))
                 (zero? (bit-and flags (bit-not flag-compressed)))
                 (zero? (bit-and 0xffffff (bit-shift-right (u32 4) 8))))
        {:flags flags :raw-len (u32 8) :stored-len (u32 12)
         :hash (.toArray (.asSlice seg (+ off 16) 16) ValueLayout/JAVA_BYTE)
         :checksum (.get seg ValueLayout/JAVA_LONG_UNALIGNED (+ off 32))}))))

(defn decode!
  "Checks the record in `rec` (header and payload, exactly) against what the
  index expects (raw and stored lengths, hash) and writes the block into
  `dst` (exactly raw-len bytes). Returns nil, or why the record is bad."
  [^MemorySegment rec raw-len stored-len ^bytes hash ^MemorySegment dst]
  (if-let [{:keys [flags ^long checksum] :as h} (header rec 0)]
    (let [payload (.asSlice rec header-len)]
      (cond
        (not= raw-len (:raw-len h))                 "raw length differs from the index"
        (not= stored-len (:stored-len h))           "stored length differs from the index"
        (not= stored-len (.byteSize payload))       "short record"
        (not (java.util.Arrays/equals hash ^bytes (:hash h))) "hash differs from the index"
        (not= checksum (Codec/xxh3 payload))        "checksum mismatch"
        (pos? (bit-and (long flags) flag-compressed))
        (try (if (= (long raw-len) (Codec/decompress dst payload)) nil "zstd frame decodes to the wrong length")
             (catch Exception e (str "zstd: " (.getMessage e))))
        (not= raw-len stored-len)                   "raw record of the wrong length"
        :else (do (MemorySegment/copy payload 0 dst 0 (long raw-len)) nil)))
    "bad header"))

;; --- reading ---------------------------------------------------------------------------

(defn reader
  "Read channels to a store's packs, opened as needed."
  [dir]
  {:dir dir :channels (ConcurrentHashMap.)})

(defn channel
  ^FileChannel [{:keys [dir ^ConcurrentHashMap channels]} id]
  (or (.get channels id)
      (let [ch (FileChannel/open (.toPath (pack-file dir id)) (into-array StandardOpenOption [StandardOpenOption/READ]))]
        (if-let [other (.putIfAbsent channels id ch)]
          (do (.close ch) other)
          ch))))

(defn forget!
  "Closes pack `id`'s read channel (before the file is deleted: Windows won't
  delete an open file)."
  [{:keys [^ConcurrentHashMap channels]} ^long id]
  (when-let [^FileChannel ch (.remove channels id)] (.close ch)))

(defn close-reader [{:keys [^ConcurrentHashMap channels]}]
  (doseq [^FileChannel ch (.values channels)] (.close ch))
  (.clear channels))

(defn read-at!
  "Reads exactly `dst`'s size from pack `id` at `pos` into native segment
  `dst`; false if the file ends first."
  [r ^long id ^long pos ^MemorySegment dst]
  (let [ch (channel r id)
        bb (.asByteBuffer dst)]
    (loop [p pos]
      (if (.hasRemaining bb)
        (let [n (.read ch bb p)]
          (if (neg? n) false (recur (+ p n))))
        true))))

;; --- appending ---------------------------------------------------------------------------

(defrecord Appender [dir ^ReentrantLock lock state])
;; state: {:id the active pack, :ch its channel, :pos its end, :synced the end
;;         last forced, :sealed {pack-id final-size} not yet recorded,
;;         :in-flight {pack-id appends not yet committed or abandoned},
;;         :abandoned? after an I/O error: nothing more goes into that pack}

(defn- open-pack ^FileChannel [dir id create?]
  (FileChannel/open (.toPath (pack-file dir id))
                    (into-array StandardOpenOption
                                (cond-> [StandardOpenOption/WRITE StandardOpenOption/READ]
                                  create? (conj StandardOpenOption/CREATE_NEW)))))

(defn appender
  "The appender for active pack `id`, whose committed content ends at `end`:
  anything after it was appended and never committed, and is cut. A pack
  without a file yet is created."
  [dir ^long id ^long end]
  (.mkdirs (io/file dir "packs"))
  (let [f  (pack-file dir id)
        ch (open-pack dir id (not (.exists f)))]
    (when (> (.size ch) end) (.truncate ch end))
    (->Appender dir (ReentrantLock.) (atom {:id id :ch ch :pos end :synced end :sealed {} :in-flight {}}))))

(defn append!
  "Appends records (byte arrays) in one write, first rotating to pack
  (next-id) when they would take the active pack past `max-size` (an empty
  pack takes anything). The pack is counted in flight until (settle! a id).
  Returns [pack-id offset-of-first-record]."
  [{:keys [dir ^ReentrantLock lock state]} records ^long max-size next-id]
  (let [total (long (reduce + (map #(alength ^bytes %) records)))
        buf   (ByteBuffer/allocate total)]
    (doseq [^bytes r records] (.put buf r))
    (.flip buf)
    (.lock lock)
    (try
      (let [{:keys [id ^FileChannel ch ^long pos abandoned?]} @state]
        (when (or abandoned? (and (pos? pos) (> (+ pos total) max-size)))
          (let [new-id (long (next-id))
                nch    (open-pack dir new-id true)]
            (try (.force ch false) (catch Exception _))      ; a sealed pack is synced whole
            (.close ch)
            (swap! state #(-> % (assoc :id new-id :ch nch :pos 0 :synced 0 :abandoned? false)
                              (assoc-in [:sealed id] pos))))))
      (let [{:keys [id ^FileChannel ch ^long pos]} @state]
        (try
          (loop [p pos] (when (.hasRemaining buf) (recur (+ p (.write ch buf p)))))
          (catch java.io.IOException e
            (swap! state assoc :abandoned? true)
            (throw e)))
        (swap! state #(-> % (assoc :pos (+ pos total)) (update-in [:in-flight id] (fnil inc 0))))
        [id pos])
      (finally (.unlock lock)))))

(defn settle!
  "An append to pack `id` was committed or given up: it's no longer in flight."
  [{:keys [state]} ^long id]
  (swap! state (fn [s] (let [n (dec (long (get-in s [:in-flight id] 1)))]
                         (if (pos? n) (assoc-in s [:in-flight id] n) (update s :in-flight dissoc id))))))

(defn in-flight? [{:keys [state]} ^long id] (contains? (:in-flight @state) id))

(defn sync!
  "Forces the active pack's appended bytes to disk. Returns what a commit
  may now record: {:id active pack :end its durable end :sealed {id size}}."
  [{:keys [^ReentrantLock lock state]}]
  (.lock lock)
  (try
    (let [{:keys [id ^FileChannel ch ^long pos ^long synced sealed]} @state]
      (when (> pos synced)
        (try (.force ch false)
             (catch java.io.IOException e (swap! state assoc :abandoned? true) (throw e)))
        (swap! state assoc :synced pos))
      {:id id :end pos :sealed sealed})
    (finally (.unlock lock))))

(defn recorded!
  "A commit recorded `sealed` (from sync!): they needn't be again."
  [{:keys [state]} sealed]
  (swap! state update :sealed #(apply dissoc % (keys sealed))))

(defn active-id ^long [{:keys [state]}] (:id @state))

(defn close-appender [{:keys [^ReentrantLock lock state] :as a}]
  (sync! a)
  (.lock lock)
  (try (.close ^FileChannel (:ch @state)) (finally (.unlock lock))))

(defn scratch
  "A per-thread native buffer of at least `n` bytes, for reading records."
  (^MemorySegment [^ThreadLocal tl n]
   (let [n (long n)
         ^MemorySegment s (.get tl)]
     (if (and s (>= (.byteSize s) n))
       (.asSlice s 0 n)
       (let [s (.allocate (Arena/ofAuto) (max n 65536) 16)]
         (.set tl s)
         (.asSlice s 0 n))))))
