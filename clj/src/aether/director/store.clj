(ns aether.director.store
  "The block store: a deduplicating, compressing, sparse store of file
  contents, after aether-vfs's aether-block-store (Rust), with its pack
  format byte for byte and its index in LMDB.

  A file is named by any byte string (its id) and has a length and a
  manifest of block slots: fixed `block-size` blocks (the last one shorter),
  each present or missing, so a file can be fully, partly or not at all
  stored. Blocks are identified by BLAKE3-128 of their content and stored
  once (dedup across files), zstd-compressed, in append-only pack files
  (aether.director.store.pack). The index (aether.director.store.lmdb):

    files   [u16 id length][id][u32 segment] -> segment 0: len u64, 8 reserved;
            then the segment's block ids, u64 each (0: missing), 4096 a segment
    blocks  u64 block id -> pack u32 | offset u64 | stored u32 | raw u32 | refs u32 | hash 16
    dedup   hash 16 -> u64 block id
    packs   u32 pack id -> live bytes u64 | durable end u64 | state u8 (0 active, 1 sealed, 2 retired)
    meta    name -> u64 (block_size, schema_version, next_block_id, next_pack_id)

  (keys big-endian, values little-endian). Unlike the Rust store, every
  write is durable when it returns: commits are group-committed and synced,
  and each syncs the pack bytes its rows point at first. So recovery is
  short: cut the active pack back to its recorded end, delete retired and
  unregistered packs, and carry on.

  Reads take no lock but a snapshot. A record that fails its checks is
  healed: its rows are dropped and the block reads as missing, for the
  caller to fetch again."
  (:require [aether.director.store.lmdb :as l]
            [aether.director.store.pack :as pack]
            [clojure.java.io :as io])
  (:import (aether.director Codec)
           (java.io File)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.channels FileChannel FileLock OverlappingFileLockException)
           (java.nio.charset StandardCharsets)
           (java.nio.file StandardOpenOption)
           (java.util Arrays HashMap)
           (java.util.concurrent Callable ExecutionException ExecutorService Executors Future
                                 ThreadFactory)
           (java.util.concurrent.atomic AtomicLong)
           (java.util.concurrent.locks ReentrantReadWriteLock)))

(set! *warn-on-reflection* true)

(def defaults
  {:block-size      65536
   :max-pack-size   (* 4 1024 1024 1024)
   :zstd-level      6
   :max-file-id-len 256
   :write-txn-bytes (* 16 1024 1024)
   :map-size        (* 256 1024 1024)
   :durability      :sync
   :threads         (.availableProcessors (Runtime/getRuntime))})

(def ^:const seg-slots 4096)
(def ^:const seg0-header 16)
(def ^:const row-len 40)
(def ^:const active 0)
(def ^:const sealed 1)
(def ^:const retired 2)

(defrecord Store [dir cfg env ^long block-size appender reader ^FileChannel lock-ch ^FileLock lock
                  ^AtomicLong next-block ^AtomicLong next-pack ^ReentrantReadWriteLock pack-io
                  ^ExecutorService pool ^AtomicLong healed ^ThreadLocal rec-buf ^ThreadLocal raw-buf])

(defn crash-point
  "For crash tests: halts the JVM at point `name` when the system property
  aether.store.crash names it."
  [point]
  (when (= point (System/getProperty "aether.store.crash"))
    (.halt (Runtime/getRuntime) 99)))

(defn- fail [kind msg & [data]] (throw (ex-info msg (assoc data ::error kind))))

(defn error
  "The kind of a store failure (:not-found :already-exists :unaligned
  :out-of-range :file-id-too-long :locked :corrupt :config), or nil."
  [e]
  (::error (ex-data e)))

;; --- keys and rows ----------------------------------------------------------------------

(defn- file-key ^bytes [^bytes id seg]
  (let [bb (ByteBuffer/allocate (+ 6 (alength id)))]
    (.putShort bb (unchecked-short (alength id)))
    (.put bb id)
    (.putInt bb (unchecked-int seg))
    (.array bb)))

(defn- file-prefix ^bytes [^bytes id]
  (let [bb (ByteBuffer/allocate (+ 2 (alength id)))]
    (.putShort bb (unchecked-short (alength id))) (.put bb id) (.array bb)))

(defn- u64-key ^bytes [v] (l/be-key [:u64 v]))
(defn- u32-key ^bytes [v] (l/be-key [:u32 v]))
(defn- meta-key ^bytes [^String k] (.getBytes k StandardCharsets/UTF_8))

(defn- le ^ByteBuffer [n] (.order (ByteBuffer/allocate n) ByteOrder/LITTLE_ENDIAN))
(defn- le-u64-bytes ^bytes [v] (.array (.putLong (le 8) (long v))))

(defn- dbi ^long [store db-name] (l/dbi (:env store) db-name))

(defn- get-meta [store txn k]
  (when-let [s (l/get-seg txn (dbi store "meta") (meta-key k))] (l/le-u64 s 0)))

(defn- put-meta! [store txn k v]
  (l/put! txn (dbi store "meta") (meta-key k) (le-u64-bytes v)))

;; A blocks row, decoded.
(defrecord Row [^long pack ^long offset ^long stored ^long raw ^long refs ^bytes hash])

(defn- decode-row [^MemorySegment s]
  (->Row (l/le-u32 s 0) (l/le-u64 s 4) (l/le-u32 s 12) (l/le-u32 s 16) (l/le-u32 s 20)
         (.toArray (.asSlice s 24 16) ValueLayout/JAVA_BYTE)))

(defn- encode-row ^bytes [^Row r]
  (let [bb (le row-len)]
    (.putInt bb (unchecked-int (.-pack r))) (.putLong bb (.-offset r))
    (.putInt bb (unchecked-int (.-stored r))) (.putInt bb (unchecked-int (.-raw r)))
    (.putInt bb (unchecked-int (.-refs r))) (.put bb ^bytes (.-hash r))
    (.array bb)))

(defn- get-row [store txn id]
  (when-let [s (l/get-seg txn (dbi store "blocks") (u64-key id))] (decode-row s)))

(defn- put-row! [store txn id row] (l/put! txn (dbi store "blocks") (u64-key id) (encode-row row)))

(defn- record-len ^long [^Row r] (+ pack/header-len (.-stored r)))

;; packs rows: {:live :end :state}
(defn- decode-pack [^MemorySegment s]
  {:live (l/le-u64 s 0) :end (l/le-u64 s 8) :state (bit-and 0xff (.get s ValueLayout/JAVA_BYTE 16))})

(defn- get-pack [store txn id]
  (some-> (l/get-seg txn (dbi store "packs") (u32-key id)) decode-pack))

(defn- get-pack-or-new
  "The packs row of `id`, or a fresh active one."
  [store txn id]
  (or (get-pack store txn id) {:live 0 :end 0 :state active}))

(defn- put-pack! [store txn id {:keys [live end state]}]
  (let [bb (le 17)]
    (.putLong bb live) (.putLong bb end) (.put bb (byte state))
    (l/put! txn (dbi store "packs") (u32-key id) (.array bb))))

(defn- add-live! [store txn pack-id delta]
  (let [p (get-pack-or-new store txn pack-id)]
    (put-pack! store txn pack-id (update p :live #(max 0 (+ (long %) delta))))))

(defn- all-packs
  "{pack-id {:live :end :state}} in a read or write txn."
  [store txn]
  (l/reduce-range txn (dbi store "packs") nil (constantly true)
                  (fn [m ^MemorySegment k v] (assoc m (l/be-u32 k 0) (decode-pack v)))
                  {}))

;; --- manifests ----------------------------------------------------------------------------

(defn- blocks-in ^long [^long len ^long bs] (quot (+ len (dec bs)) bs))
(defn- block-len ^long [^long len ^long bs ^long i] (max 0 (min bs (- len (* i bs)))))
(defn- segs-for ^long [^long nslots] (max 1 (quot (+ nslots (dec seg-slots)) seg-slots)))
(defn- slots-in-seg ^long [^long nslots ^long seg] (max 0 (min seg-slots (- nslots (* seg seg-slots)))))

(defn file-len
  "In a txn: a file's length, or nil if there's no such file."
  [store txn ^bytes id]
  (when-let [s (l/get-seg txn (dbi store "files") (file-key id 0))] (l/le-u64 s 0)))

(defn- read-seg
  "Segment `seg`'s block ids (a long[] of the slots it holds)."
  ^longs [store txn ^bytes id seg]
  (if-let [^MemorySegment s (l/get-seg txn (dbi store "files") (file-key id seg))]
    (let [h (if (zero? seg) seg0-header 0)
          n (quot (- (.byteSize s) h) 8)
          a (long-array n)]
      (MemorySegment/copy s ValueLayout/JAVA_LONG_UNALIGNED h a 0 n)
      a)
    (long-array 0)))

(defn- write-seg! [store txn ^bytes id seg len ^longs ids]
  (let [h   (if (zero? seg) seg0-header 0)
        dst (l/reserve! txn (dbi store "files") (file-key id seg) (+ h (* 8 (alength ids))))]
    (when (zero? seg)
      (.set dst ValueLayout/JAVA_LONG_UNALIGNED 0 (long len))
      (.set dst ValueLayout/JAVA_LONG_UNALIGNED 8 0))
    (MemorySegment/copy ids 0 dst ValueLayout/JAVA_LONG_UNALIGNED h (alength ids))))

(defn- read-slots
  "Block ids of slots [from, to) of a file of `nslots` slots."
  ^longs [store txn ^bytes id from to]
  (let [out (long-array (max 0 (- to from)))]
    (loop [i from]
      (when (< i to)
        (let [seg (quot i seg-slots)
              a   (read-seg store txn id seg)
              lo  (- i (* seg seg-slots))
              n   (min (- (alength a) lo) (- to i))]
          (when (pos? n) (System/arraycopy a lo out (- i from) n))
          (recur (+ i (max 1 n))))))
    out))

(defn- write-slots!
  "Sets slots [from, from + n) of a file of length `len` to `ids`; returns the old ids."
  ^longs [store txn ^bytes id len from ^longs ids]
  (let [bs     (.-block-size ^Store store)
        nslots (blocks-in len bs)
        to     (+ from (alength ids))
        old    (long-array (alength ids))]
    (loop [seg (quot from seg-slots)]
      (when (< (* seg seg-slots) to)
        (let [a    (read-seg store txn id seg)
              base (* seg seg-slots)
              a    (if (= (alength a) (slots-in-seg nslots seg)) a (Arrays/copyOf a (int (slots-in-seg nslots seg))))]
          (doseq [i (range (max from base) (min to (+ base seg-slots)))]
            (aset old (- i from) (aget a (- i base)))
            (aset a (- i base) (aget ids (- i from))))
          (write-seg! store txn id seg len a)
          (recur (inc seg)))))
    old))

;; --- reference counts -----------------------------------------------------------------------

(defn- incref! [store txn bid]
  (when-let [^Row r (get-row store txn bid)]
    (put-row! store txn bid (assoc r :refs (inc (.-refs r))))))

(defn- drop-block! [store txn bid ^Row r]
  (l/del! txn (dbi store "blocks") (u64-key bid))
  (let [k (.-hash r)]
    (when-let [s (l/get-seg txn (dbi store "dedup") k)]
      (when (= bid (l/le-u64 s 0)) (l/del! txn (dbi store "dedup") k))))
  (add-live! store txn (.-pack r) (- (record-len r))))

(defn- decref! [store txn bid]
  (when (pos? bid)
    (when-let [^Row r (get-row store txn bid)]
      (if (<= (.-refs r) 1)
        (drop-block! store txn bid r)
        (put-row! store txn bid (assoc r :refs (dec (.-refs r))))))))

(defn- swap-slots!
  "Writes `ids` to slots from `from`, increfs them, then decrefs what they replaced."
  [store txn ^bytes id len from ^longs ids]
  (let [old (write-slots! store txn id len from ids)]
    (doseq [b ids] (when (pos? (long b)) (incref! store txn b)))
    (doseq [b old] (decref! store txn b))))

(defn resize!
  "In a write txn: creates file `id` at `new-len`, or resizes it: slots past
  the new end are dropped, and so is a last block whose length changes."
  [store txn ^bytes id new-len]
  (let [bs (.-block-size ^Store store)]
    (if-let [old-len (file-len store txn id)]
      (let [old-len (long old-len)
            old-n   (blocks-in old-len bs)
            new-n   (blocks-in new-len bs)
            from    (max 0 (dec (min old-n new-n)))
            tail    (read-slots store txn id from old-n)]
        ;; what's dropped
        (doseq [i (range from old-n)]
          (when (or (>= i new-n) (not= (block-len old-len bs i) (block-len new-len bs i)))
            (decref! store txn (aget tail (- i from)))
            (aset tail (- i from) 0)))
        ;; rewrite the segments from `from`'s to the new last one, and the header
        (let [first-seg (quot from seg-slots)
              last-seg  (dec (segs-for new-n))]
          (doseq [seg (range (min first-seg last-seg) (inc last-seg))]
            (let [a (Arrays/copyOf (read-seg store txn id seg) (int (slots-in-seg new-n seg)))
                  base (* seg seg-slots)]
              (doseq [i (range (max from base) (min new-n old-n (+ base seg-slots)))]
                (aset a (- i base) (aget tail (- i from))))
              (write-seg! store txn id seg new-len a)))
          (when (pos? (min first-seg last-seg))
            (write-seg! store txn id 0 new-len (read-seg store txn id 0)))
          (doseq [seg (range (segs-for new-n) (segs-for old-n))]
            (l/del! txn (dbi store "files") (file-key id seg)))))
      (doseq [seg (range (segs-for (blocks-in new-len bs)))]
        (write-seg! store txn id seg new-len (long-array (slots-in-seg (blocks-in new-len bs) seg)))))))

(defn delete-file!
  "In a write txn: deletes file `id`; nil if there was none."
  [store txn ^bytes id]
  (when-let [len (file-len store txn id)]
    (let [n (blocks-in len (.-block-size ^Store store))]
      (doseq [b (read-slots store txn id 0 n)] (decref! store txn b))
      (doseq [seg (range (segs-for n))] (l/del! txn (dbi store "files") (file-key id seg))))
    true))

;; --- opening ---------------------------------------------------------------------------------

(defn- on-commit!
  "Every commit records the active pack's durable end (syncing it first),
  packs sealed since the last commit, and the id counters."
  [store txn]
  (let [{:keys [appender ^AtomicLong next-block ^AtomicLong next-pack]} store
        {:keys [id end] newly-sealed :sealed} (pack/sync! appender)]
    (put-pack! store txn id (assoc (get-pack-or-new store txn id) :end end :state active))
    (doseq [[sid size] newly-sealed]
      (put-pack! store txn sid (assoc (get-pack-or-new store txn sid) :end size :state sealed)))
    (put-meta! store txn "next_block_id" (.get next-block))
    (put-meta! store txn "next_pack_id" (.get next-pack))
    (when (seq newly-sealed) #(pack/recorded! appender newly-sealed))))

(defn open
  "Opens (creating) the store in directory `dir`. Options: see `defaults`;
  :block-size is fixed when the store is created. One process at a time.
  :extra-dbs names more LMDB databases in the same environment, for a layer
  above (aether.director.storage) whose rows commit with the store's;
  :page-size sets LMDB's page size when the index is created."
  [dir & [opts]]
  (let [cfg     (merge defaults opts)
        bs      (long (:block-size cfg))
        _       (when-not (<= 4096 bs (* 16 1024 1024)) (fail :config "block size must be 4 KiB to 16 MiB"))
        _       (.mkdirs (io/file dir "packs"))
        lock-ch (FileChannel/open (.toPath (io/file dir "LOCK"))
                                  (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
        lock    (try (.tryLock lock-ch) (catch OverlappingFileLockException _ nil))
        _       (when-not lock (.close lock-ch) (fail :locked (str dir " is open in another process")))
        env     (l/open (io/file dir "index") (into ["files" "blocks" "dedup" "packs" "meta"] (:extra-dbs cfg))
                        {:map-size (:map-size cfg) :durability (:durability cfg) :page-size (:page-size cfg)})
        store   (map->Store {:dir dir :cfg cfg :env env :block-size bs :lock-ch lock-ch :lock lock
                             :next-block (AtomicLong.) :next-pack (AtomicLong.) :pack-io (ReentrantReadWriteLock.)
                             :healed (AtomicLong.) :rec-buf (ThreadLocal.) :raw-buf (ThreadLocal.)})
        ;; recovery, in one commit before the hook is registered
        resume  (l/with-write [txn env]
                  (let [stored-bs (get-meta store txn "block_size")]
                    (when (and stored-bs (not= bs stored-bs))
                      (fail :config (str "the store's block size is " stored-bs ", not " bs)))
                    (when-let [v (get-meta store txn "schema_version")]
                      (when (not= 1 v) (fail :config (str "schema version " v))))
                    (put-meta! store txn "block_size" bs)
                    (put-meta! store txn "schema_version" 1)
                    (let [rows   (all-packs store txn)
                          on-disk (into #{} (keep #(pack/pack-id (.getName ^File %)))
                                        (.listFiles (io/file dir "packs")))
                          live-rows
                          (reduce-kv
                           (fn [m id {:keys [state live] :as p}]
                             (let [f (pack/pack-file dir id)]
                               (cond
                                 (= state retired) (do (.delete f) (l/del! txn (dbi store "packs") (u32-key id)) m)
                                 (not (.exists f))
                                 (if (zero? (long live))
                                   (do (l/del! txn (dbi store "packs") (u32-key id)) m)
                                   (fail :corrupt (str "pack " id " holds live blocks and is missing")))
                                 :else (assoc m id p))))
                           {} rows)]
                      (doseq [id on-disk :when (not (contains? rows id))]
                        (.delete (pack/pack-file dir id)))           ; never committed
                      (let [actives (sort (keep (fn [[id p]] (when (= active (:state p)) id)) live-rows))
                            resume  (last actives)]
                        (doseq [id (butlast actives)]
                          (put-pack! store txn id (assoc (live-rows id) :state sealed)))
                        (.set ^AtomicLong (:next-block store) (long (or (get-meta store txn "next_block_id") 1)))
                        (.set ^AtomicLong (:next-pack store)
                              (inc (long (reduce max (long (or (get-meta store txn "next_pack_id") 1))
                                                 (concat (keys rows) on-disk)))))
                        (if resume
                          [resume (:end (live-rows resume))]
                          [(dec (.incrementAndGet ^AtomicLong (:next-pack store))) 0])))))
        [rid rend] resume
        store   (assoc store
                       :appender (pack/appender dir rid rend)
                       :reader (pack/reader dir)
                       :pool (Executors/newFixedThreadPool
                              (int (:threads cfg))
                              (reify ThreadFactory
                                (newThread [_ r] (doto (Thread. r "store-compress") (.setDaemon true))))))]
    (when (>= (long (:max-file-id-len cfg)) (- (l/max-key-size env) 6))
      (fail :config (str "file ids up to " (:max-file-id-len cfg) " bytes don't fit LMDB's keys")))
    (l/on-commit env (fn [txn] (on-commit! store txn)))
    store))

(defn close
  "Closes the store. Everything written is already durable."
  [{:keys [env appender reader ^FileChannel lock-ch ^FileLock lock ^ExecutorService pool]}]
  (l/close env)
  (pack/close-appender appender)
  (pack/close-reader reader)
  (.shutdown pool)
  (.release lock)
  (.close lock-ch))

;; --- small operations ------------------------------------------------------------------------

(defn- check-id [store ^bytes id]
  (when (> (alength id) (long (get-in store [:cfg :max-file-id-len])))
    (fail :file-id-too-long (str "file id of " (alength id) " bytes"))))

(defn stat
  "{:len} of file `id` (bytes), or nil."
  [store ^bytes id]
  (l/with-read [txn (:env store)]
    (when-let [len (file-len store txn id)] {:len len})))

(defn set-len!
  "Creates file `id` with length `len`, or resizes it (dropping blocks past
  the end, and a last block whose length changes). :also, a fn of the write
  txn, runs in the same commit; its result is returned."
  [store ^bytes id len & [{:keys [also]}]]
  (check-id store id)
  (l/with-write [txn (:env store)] (resize! store txn id len) (when also (also txn))))

(defn delete!
  "Deletes file `id`; false if there was none."
  [store ^bytes id]
  (l/with-write [txn (:env store)] (boolean (delete-file! store txn id))))

(defn write!
  "Runs (f txn) in a write transaction of the store's environment (for rows
  of the layer above); its result once committed."
  [store f]
  (l/write! (:env store) f))

(defn read*
  "Runs (f txn) in a read snapshot of the store's environment."
  [store f]
  (l/read* (:env store) f))

(defn clone-in!
  "In a write txn: copies each [src dst] file's manifest to a new file (no
  data is copied). Throws :not-found for a missing source, :already-exists
  for an existing destination."
  [store txn pairs]
  (doseq [[_ d] pairs] (check-id store d))
  (let [bs (.-block-size ^Store store)]
    (doseq [[^bytes src ^bytes dst] pairs]
      (let [len (or (file-len store txn src) (fail :not-found "no such file" {:id src}))]
        (when (file-len store txn dst) (fail :already-exists "file exists" {:id dst}))
        (let [n (blocks-in len bs)]
          (doseq [seg (range (segs-for n))]
            (let [a (read-seg store txn src seg)]
              (write-seg! store txn dst seg len a)
              (doseq [b a] (when (pos? (long b)) (incref! store txn b)))))))))
  nil)

(defn clone-files!
  "Copies each [src dst] file's manifest to a new file (no data is copied).
  All or nothing: :not-found for a missing source, :already-exists for an
  existing destination."
  [store pairs]
  (l/with-write [txn (:env store)] (clone-in! store txn pairs)))

(defn file-ids
  "Every file's id."
  [store]
  (l/with-read [txn (:env store)]
    (l/reduce-range txn (dbi store "files") nil (constantly true)
                    (fn [acc ^MemorySegment k _]
                      (let [n (bit-and 0xffff (.get k (.withOrder ValueLayout/JAVA_SHORT_UNALIGNED ByteOrder/BIG_ENDIAN) 0))]
                        (if (zero? (l/be-u32 k (+ 2 n)))
                          (conj acc (.toArray (.asSlice k 2 n) ValueLayout/JAVA_BYTE))
                          acc)))
                    [])))

;; --- writing ---------------------------------------------------------------------------------------

(defn- parallel
  "(f x) for each of xs, on the store's pool when there's more than one."
  [store f xs]
  (if (next xs)
    (let [^ExecutorService pool (:pool store)
          fs (mapv (fn [x] (.submit pool ^Callable (fn [] (f x)))) xs)]
      (mapv (fn [^Future fu] (try (.get fu) (catch ExecutionException e (throw (.getCause e))))) fs))
    (mapv f xs)))

(defn- hash-at ^bytes [^bytes hashes i] (Arrays/copyOfRange hashes (int (* 16 i)) (int (* 16 (inc i)))))

(defn- known-blocks
  "{hash-code [hash block-id]} for those of `hashes` the store already holds."
  [store hashes]
  (l/with-read [txn (:env store)]
    (into {} (keep (fn [^bytes h]
                     (when-let [s (l/get-seg txn (dbi store "dedup") h)]
                       [(Arrays/hashCode h) [h (l/le-u64 s 0)]])))
          hashes)))

(defn- append-blocks!
  "Encodes `blocks` ([hash data] pairs, distinct hashes) and appends them.
  Returns {hash-key {:pack :offset :stored :raw :hash}}."
  [store blocks]
  (when (seq blocks)
    (let [{:keys [appender cfg ^AtomicLong next-pack]} store
          encoded (parallel store (fn [[h d]] (pack/encode d h (:zstd-level cfg))) blocks)
          [pid off] (pack/append! appender (map :bytes encoded) (:max-pack-size cfg)
                                  #(dec (.incrementAndGet next-pack)))]
      (loop [[[^bytes h] & bs] blocks [e & es] encoded off (long off) out {}]
        (if h
          (recur bs es (+ off (alength ^bytes (:bytes e)))
                 (assoc out (Arrays/hashCode h) {:pack pid :offset off :stored (:stored-len e)
                                                 :raw (:raw-len e) :hash h}))
          out)))))

(defn- place!
  "In a write txn: the block id for each of `hashes`, from the index or from
  `fresh` (just appended; given a row now). Throws ::retry with the indices
  whose block vanished since it was looked up."
  [store txn hashes fresh ^HashMap placed]
  (let [ids     (long-array (count hashes))
        missing (volatile! [])]
    (doseq [[i ^bytes h] (map-indexed vector hashes)]
      (let [k (Arrays/hashCode h)]
        (if-let [s (l/get-seg txn (dbi store "dedup") h)]
          (aset ids i (l/le-u64 s 0))
          (if-let [bid (.get placed k)]
            (aset ids i (long bid))
            (if-let [{:keys [pack offset stored raw]} (get fresh k)]
              (let [bid (.getAndIncrement ^AtomicLong (:next-block store))]
                (put-row! store txn bid (->Row pack offset stored raw 0 h))
                (l/put! txn (dbi store "dedup") h (le-u64-bytes bid))
                (add-live! store txn pack (+ pack/header-len (long stored)))
                (.put placed k bid)
                (aset ids i bid))
              (vswap! missing conj i))))))
    (when (seq @missing) (throw (ex-info "blocks vanished" {::retry @missing})))
    ids))

(defn store-blocks!
  "Stores the blocks of `files` ([{:id :len :from :data (bytes)}], each data
  whole blocks from slot :from; :len set means create/resize to it first)
  in one commit, encoding and appending only blocks the store lacks. `also`
  (a fn of the write txn, or nil) runs in the same commit; returns its result."
  [store files & [also]]
  (let [bs     (.-block-size ^Store store)
        hashes (mapv (fn [{:keys [^bytes data]}]
                       (let [h (Codec/blake3Blocks (MemorySegment/ofArray data) bs)]
                         (mapv #(hash-at h %) (range (quot (alength h) 16)))))
                     files)
        block  (fn [fi i] (let [^bytes d (:data (nth files fi))]
                            (Arrays/copyOfRange d (int (* i bs)) (int (min (alength d) (* (inc i) bs))))))
        known  (known-blocks store (apply concat hashes))]
    (loop [need (vec (for [[fi hs] (map-indexed vector hashes)
                           [i h] (map-indexed vector hs)
                           :when (not (known (Arrays/hashCode ^bytes h)))]
                       [fi i]))]
      (let [distinct-need (vals (into {} (map (fn [[fi i]] (let [h (nth (nth hashes fi) i)] [(Arrays/hashCode ^bytes h) [h (block fi i)]])))
                                      need))
            fresh  (append-blocks! store distinct-need)
            _      (crash-point "write-after-append")
            packs  (distinct (map :pack (vals fresh)))
            result (try
                     (l/write! (:env store)
                               (fn [txn]
                                 (let [placed (HashMap.)]
                                   (doseq [[{:keys [^bytes id len ^long from]} hs] (map vector files hashes)]
                                     (when len (resize! store txn id len))
                                     (let [flen (or (file-len store txn id) (fail :not-found "no such file" {:id id}))]
                                       (when (> (+ (* from bs) (reduce + (map #(block-len flen bs %) (range from (+ from (count hs))))))
                                                (long flen))
                                         (fail :out-of-range "write past the end"))
                                       (swap-slots! store txn id flen from (place! store txn hs fresh placed))))
                                   {:ok (when also (also txn))})))
                     (catch clojure.lang.ExceptionInfo e
                       (if-let [r (::retry (ex-data e))] {:retry r} (throw e)))
                     (finally (doseq [p packs] (pack/settle! (:appender store) p))))]
        (if-let [r (:retry result)]
          ;; a block we meant to share was freed meanwhile: store those too
          (recur (vec (for [[fi hs] (map-indexed vector hashes) [i _] (map-indexed vector hs)] [fi i])))
          (:ok result))))))

(defn write-blocks!
  "Writes `data` (bytes) to file `id` from block `first-block`: whole blocks,
  the last one shorter only where it ends at the file's end. The file must
  exist (set-len!) and be long enough."
  [store ^bytes id first-block ^bytes data]
  (let [bs   (.-block-size ^Store store)
        len  (or (:len (stat store id)) (fail :not-found "no such file" {:id id}))
        end  (+ (* first-block bs) (alength data))]
    (when (> end (long len)) (fail :out-of-range (str "write to " end " past the end, " len)))
    (when (and (pos? (rem (alength data) bs)) (not= end len))
      (fail :unaligned "a write must be whole blocks, or end at the file's end"))
    (let [chunk (* bs (max 1 (quot (long (get-in store [:cfg :write-txn-bytes])) bs)))]
      (loop [off 0]
        (when (< off (alength data))
          (let [n (min chunk (- (alength data) off))]
            (store-blocks! store [{:id id :from (+ first-block (quot off bs))
                                   :data (Arrays/copyOfRange data (int off) (int (+ off n)))}])
            (recur (+ off n))))))))

(defn put-files!
  "Writes whole files ([[id bytes] …]), creating or replacing each, in one
  commit (with `also`, a fn of the txn, as store-blocks!)."
  [store files & [also]]
  (doseq [[id] files] (check-id store id))
  (store-blocks! store (mapv (fn [[id ^bytes data]] {:id id :len (alength data) :from 0 :data data}) files) also))

;; --- reading ---------------------------------------------------------------------------------------

(defn- heal!
  "Drops a block whose record is bad (if its row still points there), so it
  reads as missing; the caller fetches it again."
  [store bid pack-id offset]
  (.incrementAndGet ^AtomicLong (:healed store))
  (l/submit (:env store)
            (fn [txn]
              (when-let [^Row r (get-row store txn bid)]
                (when (and (= pack-id (.-pack r)) (= offset (.-offset r)))
                  (drop-block! store txn bid r))))))

(defn- read-block!
  "Reads block `bid` (its row `r`) into `dst` (raw-len bytes); nil, or why it's bad."
  [store ^Row r ^MemorySegment dst]
  (let [rec (pack/scratch (:rec-buf store) (record-len r))]
    (if (pack/read-at! (:reader store) (.-pack r) (.-offset r) rec)
      (pack/decode! rec (.-raw r) (.-stored r) (.-hash r) dst)
      "record past the end of its pack")))

(defn- merge-range [ranges s e]
  (let [[ls le] (peek ranges)]
    (if (and le (= (long le) s)) (conj (pop ranges) [ls e]) (conj ranges [s e]))))

(defn read!
  "Reads file `id` from `offset` into `dst` (a MemorySegment; reads stop at
  the file's end). Returns nil for no such file, else {:bytes n :missing
  [[start end] …]}: the byte count covered, and the ranges (file offsets) of
  it whose blocks aren't stored, which `dst` doesn't hold."
  [store ^bytes id offset ^MemorySegment dst]
  (let [bs   (.-block-size ^Store store)
        rl   (.readLock ^ReentrantReadWriteLock (:pack-io store))
        bad  (volatile! [])]
    (.lock rl)
    (let [result
          (try
            (l/with-read [txn (:env store)]
              (when-let [len (file-len store txn id)]
                (let [len (long len)
                      end (min len (+ offset (.byteSize dst)))]
                  (if (>= offset end)
                    {:bytes 0 :missing []}
                    (let [b0  (quot offset bs)
                          b1  (blocks-in end bs)
                          ids (read-slots store txn id b0 b1)]
                      (loop [b b0 missing []]
                        (if (< b b1)
                          (let [bstart (* b bs)
                                blen   (block-len len bs b)
                                s      (max offset bstart)
                                e      (min end (+ bstart blen))
                                bid    (aget ids (- b b0))
                                ^Row r (when (pos? bid) (get-row store txn bid))
                                whole? (and (= s bstart) (= e (+ bstart blen)))
                                ok?    (when r
                                         (let [out (if whole?
                                                     (.asSlice dst (long (- s offset)) (long blen))
                                                     (pack/scratch (:raw-buf store) blen))
                                               why (read-block! store r out)]
                                           (if why
                                             (do (vswap! bad conj [bid (.-pack r) (.-offset r) why]) false)
                                             (do (when-not whole?
                                                   (MemorySegment/copy ^MemorySegment out (- s bstart) dst (- s offset) (- e s)))
                                                 true))))]
                            (recur (inc b) (if ok? missing (merge-range missing s e))))
                          {:bytes (- end offset) :missing missing})))))))
            (finally (.unlock rl)))]
      (doseq [[bid p o] @bad] (heal! store bid p o))
      result)))

(defn cached-ranges
  "The byte ranges of file `id` whose blocks are stored, merged: [[start end] …]."
  [store ^bytes id]
  (let [bs (.-block-size ^Store store)]
    (l/with-read [txn (:env store)]
      (when-let [len (file-len store txn id)]
        (let [len (long len) n (blocks-in len bs) ids (read-slots store txn id 0 n)]
          (reduce (fn [rs b]
                    (let [bid (aget ids b)]
                      (if (and (pos? bid) (get-row store txn bid))
                        (merge-range rs (* b bs) (+ (* b bs) (block-len len bs b)))
                        rs)))
                  [] (range n)))))))

(defn content-digests-in
  "In a txn: for each file id, [len digest] (32 bytes) or nil: BLAKE3 in
  derive-key mode over the length and each slot's block hash (or absence),
  as the Rust store's content_digests: equal contents, equal digests;
  compaction doesn't change it."
  [store txn ids]
  (let [bs (.-block-size ^Store store)]
    (mapv (fn [^bytes id]
            (when-let [len (file-len store txn id)]
              (let [d   (Codec/derive "aether-block-store content digest v1")
                    len (long len)
                    n   (blocks-in len bs)
                    ids (read-slots store txn id 0 n)]
                (.update d (le-u64-bytes len))
                (doseq [b (range n)]
                  (let [bid (aget ids b)
                        r   (when (pos? bid) (get-row store txn bid))]
                    (if r
                      (.update d (byte-array (cons 1 (seq ^bytes (:hash r)))))
                      (.update d (byte-array [0])))))
                [len (.finish d 32)])))
          ids)))

(defn content-digests
  "content-digests-in, in a read snapshot."
  [store ids]
  (l/with-read [txn (:env store)] (content-digests-in store txn ids)))

;; --- compaction ---------------------------------------------------------------------------------------

(defn- pack-size ^long [store ^long id] (.length (pack/pack-file (:dir store) id)))

(defn- live-records
  "[[block-id offset record-len] …] of pack `pid`, from the index."
  [store pid]
  (l/with-read [txn (:env store)]
    (l/reduce-range txn (dbi store "blocks") nil (constantly true)
                    (fn [acc k ^MemorySegment v]
                      (if (= pid (l/le-u32 v 0))
                        (conj acc [(l/be-u64 k 0) (l/le-u64 v 4) (+ pack/header-len (l/le-u32 v 12))])
                        acc))
                    [])))

(defn- move-records!
  "Copies records (each [block-id offset len], all in pack `pid`) unchanged
  to the active pack and repoints the rows that still point at them."
  [store pid recs]
  (when (seq recs)
    (let [{:keys [appender cfg ^AtomicLong next-pack]} store
          data  (mapv (fn [[_ off len]]
                        (let [seg (MemorySegment/ofArray (byte-array len))]
                          (when-not (pack/read-at! (:reader store) pid off (.asSlice (pack/scratch (:rec-buf store) len) 0 (long len)))
                            (fail :corrupt (str "pack " pid " ends early")))
                          (MemorySegment/copy (pack/scratch (:rec-buf store) len) 0 seg 0 len)
                          (.toArray seg ValueLayout/JAVA_BYTE)))
                      recs)
          [npid noff] (pack/append! appender data (:max-pack-size cfg) #(dec (.incrementAndGet next-pack)))]
      (try
        (l/write! (:env store)
                  (fn [txn]
                    (loop [[[bid off len] & more] recs at (long noff)]
                      (when bid
                        (when-let [^Row r (get-row store txn bid)]
                          (when (and (= pid (.-pack r)) (= off (.-offset r)))
                            (put-row! store txn bid (assoc r :pack npid :offset at))
                            (add-live! store txn pid (- (long len)))
                            (add-live! store txn npid len)))
                        (recur more (+ at (long len)))))))
        (finally (pack/settle! appender npid)))
      (reduce + (map #(nth % 2) recs)))))

(defn compact!
  "Rewrites sealed packs with at least `min-garbage-ratio` garbage (worst
  first; up to `max-bytes` copied): live records are copied unchanged to the
  active pack, bad ones healed, and the pack is retired and deleted once no
  read can be using it. Returns {:packs [ids] :copied bytes :freed bytes}."
  [store & [{:keys [min-garbage-ratio max-bytes] :or {min-garbage-ratio 0.5}}]]
  (let [{:keys [appender]} store
        packs  (l/with-read [txn (:env store)] (all-packs store txn))
        cands  (->> packs
                    (keep (fn [[id {:keys [live state]}]]
                            (let [size (pack-size store id)]
                              (when (and (= state sealed) (pos? size) (not (pack/in-flight? appender id))
                                         (not= id (pack/active-id appender)))
                                [id (/ (double (- size (long live))) size) size]))))
                    (filter #(>= (double (second %)) (double min-garbage-ratio)))
                    (sort-by (comp - second)))]
    (loop [[[pid _ size] & more] cands done [] copied 0 freed 0]
      (if (or (nil? pid) (and max-bytes (>= copied (long max-bytes))))
        {:packs done :copied copied :freed freed}
        (let [recs (live-records store pid)
              ;; check each before copying: a bad one is healed, not carried along
              good (filterv (fn [[bid off len]]
                              (let [rec (.asSlice (pack/scratch (:rec-buf store) len) 0 (long len))]
                                (or (and (pack/read-at! (:reader store) pid off rec)
                                         (when-let [h (pack/header rec 0)]
                                           (= (:checksum h) (Codec/xxh3 (.asSlice rec pack/header-len)))))
                                    (do (heal! store bid pid off) false))))
                            recs)
              batch (long (get-in store [:cfg :write-txn-bytes]))
              n     (reduce (fn [^long acc part] (+ acc (long (move-records! store pid part))))
                            0 (partition-all (max 1 (quot batch 65576)) good))
              _     (crash-point "compact-after-copy")
              ;; retire, if nothing points into it any more
              ok    (l/write! (:env store)
                              (fn [txn]
                                (let [p (get-pack store txn pid)]
                                  (if (and p (zero? (long (:live p))))
                                    (do (put-pack! store txn pid (assoc p :state retired)) true)
                                    false))))]
          (crash-point "compact-after-retire")
          (when ok
            (let [wl (.writeLock ^ReentrantReadWriteLock (:pack-io store))]
              (.lock wl)
              (try (pack/forget! (:reader store) pid)
                   (when (.delete (pack/pack-file (:dir store) pid))
                     (l/write! (:env store) (fn [txn] (l/del! txn (dbi store "packs") (u32-key pid)))))
                   (finally (.unlock wl)))))
          (recur more (if ok (conj done pid) done) (+ copied (long n)) (if ok (+ freed (long size)) freed)))))))

;; --- verification and stats ---------------------------------------------------------------------------

(defn verify
  "A full check: every manifest, reference count, dedup row, pack's live
  bytes and record. Returns {:files n :blocks n :problems [strings]}."
  [store]
  (let [bs (.-block-size ^Store store)
        problems (volatile! [])
        problem! #(vswap! problems conj %)]
    (l/with-read [txn (:env store)]
      (let [ids    (file-ids store)
            refs   (HashMap.)
            _      (doseq [^bytes id ids]
                     (let [len (long (file-len store txn id))
                           n   (blocks-in len bs)]
                       (doseq [seg (range (segs-for n))]
                         (let [a (read-seg store txn id seg)]
                           (when (not= (alength a) (slots-in-seg n seg))
                             (problem! (str "file " (String. id) " segment " seg " holds " (alength a) " slots, not " (slots-in-seg n seg))))
                           (doseq [b a] (when (pos? (long b)) (.merge refs b 1 +)))))))
            packs  (all-packs store txn)
            counted (HashMap.)
            nblock (l/reduce-range
                    txn (dbi store "blocks") nil (constantly true)
                    (fn [^long n k v]
                      (let [bid (l/be-u64 k 0) ^Row r (decode-row v)
                            want (long (or (.get refs bid) 0))]
                        (when (not= want (.-refs r))
                          (problem! (str "block " bid " counts " (.-refs r) " references, has " want)))
                        (let [d (l/get-seg txn (dbi store "dedup") (.-hash r))]
                          (when-not (and d (= bid (l/le-u64 d 0)))
                            (problem! (str "block " bid " isn't its hash's dedup entry"))))
                        (if-let [p (packs (.-pack r))]
                          (when (= retired (:state p)) (problem! (str "block " bid " is in retired pack " (.-pack r))))
                          (problem! (str "block " bid " is in unknown pack " (.-pack r))))
                        (.merge counted (.-pack r) (record-len r) +)
                        (let [why (read-block! store r (pack/scratch (:raw-buf store) (.-raw r)))]
                          (when why (problem! (str "block " bid ": " why))))
                        (inc n)))
                    0)]
        (l/reduce-range txn (dbi store "dedup") nil (constantly true)
                        (fn [_ k v]
                          (let [bid (l/le-u64 v 0)]
                            (when-not (l/get-seg txn (dbi store "blocks") (u64-key bid))
                              (problem! (str "dedup entry for missing block " bid)))))
                        nil)
        (doseq [[pid {:keys [live]}] packs]
          (let [want (long (or (.get counted pid) 0))]
            (when (not= want (long live))
              (problem! (str "pack " pid " counts " live " live bytes, has " want)))))
        {:files (count ids) :blocks nblock :problems @problems}))))

(defn stats
  "{:packs [{:id :state :bytes :live :garbage}] :healed n :blocks n :files n}."
  [store]
  (l/with-read [txn (:env store)]
    (let [packs (all-packs store txn)]
      {:packs  (vec (for [[id {:keys [live state]}] (sort packs)]
                      (let [size (pack-size store id)]
                        {:id id :state ({0 :active 1 :sealed 2 :retired} state) :bytes size :live live
                         :garbage (if (pos? size) (/ (double (- size (long live))) size) 0.0)})))
       :healed (.get ^AtomicLong (:healed store))
       :blocks (l/reduce-range txn (dbi store "blocks") nil (constantly true) (fn [^long n _ _] (inc n)) 0)})))
