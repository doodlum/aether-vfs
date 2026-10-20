(ns aether.director.store.lmdb
  "An LMDB environment for the store's index (aether.director.Lmdb, the FFM
  binding to aether_native's LMDB 1.0).

  Reads run in a per-thread read transaction, renewed for each read (a
  snapshot, no locks). Writes go to one writer thread, which group-commits:
  every write queued while a commit runs goes into the next one, each in a
  nested transaction of its own, so one write's failure doesn't fail the
  others. A commit is durable when `write!` returns (unless the env was
  opened :durability :none), and its `before-commit` hooks have run first:
  the block store syncs the pack bytes a commit's rows point at there.

  The map grows by doubling when a write fills it; that needs no read in
  flight in this process, so reads hold a read lock and the resize the write
  lock."
  (:require [clojure.java.io :as io])
  (:import (aether.director Lmdb Lmdb$Cursor Lmdb$LmdbException)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.nio ByteBuffer ByteOrder)
           (java.util ArrayList)
           (java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit)
           (java.util.concurrent.locks ReentrantReadWriteLock)))

(set! *warn-on-reflection* true)

(defrecord Env [^long ptr dbis ^ReentrantReadWriteLock resize map-size
                ^LinkedBlockingQueue queue ^Thread writer hooks
                ^ThreadLocal readers all-readers dir])

;; --- opening ----------------------------------------------------------------

(def ^:private mib (* 1024 1024))

(declare ^:private run-writer)

(defn open
  "Opens (creating) the environment in directory `dir` with the named
  databases `dbs` (strings). Options: :map-size (bytes to start with; it
  grows), :max-readers, :page-size, :durability (:sync, the default: every
  commit fsyncs; :meta: data fsynced, metadata not, so a system crash can
  undo the last commits but never corrupts; :none: nothing synced until
  `sync!`, and a system crash can corrupt the file)."
  [dir dbs & [{:keys [map-size max-readers page-size durability]
               :or {map-size (* 64 mib) max-readers 1024 durability :sync}}]]
  (.mkdirs (io/file dir))
  (let [ptr   (Lmdb/envCreate)
        flags (bit-or Lmdb/NOTLS Lmdb/NORDAHEAD
                      (case durability :sync 0 :meta Lmdb/NOMETASYNC :none Lmdb/NOSYNC))]
    (try
      (Lmdb/setMaxdbs ptr (int (max 1 (count dbs))))
      (Lmdb/setMaxreaders ptr (int max-readers))
      (Lmdb/setMapsize ptr (long map-size))
      (when page-size (Lmdb/setPagesize ptr (int page-size)))
      (Lmdb/envOpen ptr (.getCanonicalPath (io/file dir)) flags (int 0644))
      (Lmdb/readerCheck ptr)                               ; slots of crashed processes
      (catch Throwable t (Lmdb/envClose ptr) (throw t)))
    (let [txn  (Lmdb/txnBegin ptr 0 0)
          dbis (try (into {} (for [d dbs] [d (Lmdb/dbiOpen txn d Lmdb/CREATE)]))
                    (catch Throwable t (Lmdb/txnAbort txn) (Lmdb/envClose ptr) (throw t)))
          _    (Lmdb/txnCommit txn)
          env  (map->Env {:ptr ptr :dbis dbis :resize (ReentrantReadWriteLock.)
                          :map-size (atom (long map-size)) :queue (LinkedBlockingQueue.)
                          :hooks (atom []) :readers (ThreadLocal.) :all-readers (atom #{})
                          :dir dir})
          w    (doto (Thread. ^Runnable #(run-writer env) (str "lmdb-writer " dir))
                 (.setDaemon true))
          env  (assoc env :writer w)]
      (.start w)
      env)))

(defn dbi
  "The handle of database `name`."
  ^long [env name]
  (or (get (:dbis env) name) (throw (ex-info (str "no database " name) {:name name}))))

(defn max-key-size ^long [env] (Lmdb/maxKeySize (:ptr env)))

;; --- reads --------------------------------------------------------------------

(defn read*
  "Calls (f txn) in a read snapshot; returns its result. Views the txn hands
  out (get, cursors) are valid only inside f. Reads nest: an inner one on
  the same thread shares the outer one's snapshot."
  [{:keys [^long ptr ^ReentrantReadWriteLock resize ^ThreadLocal readers all-readers]} f]
  ;; per thread: [txn depth], the txn kept reset between reads
  (let [^longs r (or (.get readers)
                     (let [t (Lmdb/txnBegin ptr 0 Lmdb/RDONLY)]
                       (Lmdb/txnReset t)
                       (swap! all-readers conj t)
                       (doto (long-array [t 0]) (->> (.set readers)))))
        txn    (aget r 0)
        outer? (zero? (aget r 1))
        rl     (.readLock resize)]
    (when outer?
      (.lock rl)
      (try (Lmdb/txnRenew txn)
           (catch Throwable t (.unlock rl) (throw t))))
    (aset r 1 (inc (aget r 1)))
    (try
      (f txn)
      (finally
        (aset r 1 (dec (aget r 1)))
        (when outer?
          (Lmdb/txnReset txn)
          (.unlock rl))))))

(defmacro with-read
  "Evaluates body with `txn` bound to a read transaction of `env`."
  [[txn env] & body]
  `(read* ~env (fn [~(with-meta txn {:tag 'long})] ~@body)))

(defn get-seg
  "The value under `key` (bytes or a segment) in database `dbi`, as a view
  valid for the transaction, or nil."
  ^MemorySegment [txn dbi key]
  (if (bytes? key)
    (Lmdb/get (long txn) (int dbi) ^bytes key)
    (Lmdb/get (long txn) (int dbi) ^MemorySegment key)))

(defn get-bytes
  "The value under `key`, copied out, or nil."
  ^bytes [^long txn ^long dbi key]
  (when-let [^MemorySegment s (get-seg txn dbi key)] (.toArray s ValueLayout/JAVA_BYTE)))

(defn reduce-range
  "Reduces (f acc key-seg val-seg) over the entries of `dbi` from `start`
  (inclusive; nil for the first) while (more? key-seg) holds. f may return
  (reduced x). The segments are views, valid only during the call."
  [txn dbi start more? f init]
  (with-open [^Lmdb$Cursor c (Lmdb/cursor (long txn) (int dbi))]
    (loop [ok  (if start (.seek c ^bytes start) (.first c))
           acc init]
      (if (and ok (more? (.key c)))
        (let [acc (f acc (.key c) (.val c))]
          (if (reduced? acc) @acc (recur (.next c) acc)))
        acc))))

(defn prefix?
  "A predicate: does a key segment start with `prefix` (bytes)?"
  [^bytes prefix]
  (let [n (alength prefix) p (MemorySegment/ofArray prefix)]
    (fn [^MemorySegment k]
      (and (>= (.byteSize k) n)
           (neg? (MemorySegment/mismatch k 0 n p 0 n))))))

;; --- writes --------------------------------------------------------------------

(defn put!
  "Puts `val` (bytes or segment) under `key` in a write txn; with :no-overwrite,
  returns false (and writes nothing) if the key exists."
  ([txn dbi key val] (put! txn dbi key val nil))
  ([txn dbi key val opt]
   (let [k (if (bytes? key) (MemorySegment/ofArray ^bytes key) key)
         v (if (bytes? val) (MemorySegment/ofArray ^bytes val) val)]
     (Lmdb/put (long txn) (int dbi) ^MemorySegment k ^MemorySegment v
               (int (if (= opt :no-overwrite) Lmdb/NOOVERWRITE 0))))))

(defn reserve!
  "Room for an `n`-byte value under `key`, in the map, to fill now."
  ^MemorySegment [txn dbi key n]
  (Lmdb/reserve (long txn) (int dbi) ^MemorySegment (if (bytes? key) (MemorySegment/ofArray ^bytes key) key) (long n) 0))

(defn del!
  "Deletes `key`; false if it wasn't there."
  [^long txn ^long dbi key]
  (if (bytes? key) (Lmdb/del txn (int dbi) ^bytes key) (Lmdb/del txn (int dbi) ^MemorySegment key)))

(defn on-commit
  "Registers (f txn) to run on the writer thread in each commit that holds
  writes, after them and before the commit: e.g. syncing the files its rows
  point at, and recording that. It may return a fn, called once the commit
  is durable."
  [env f]
  (swap! (:hooks env) conj f))

(deftype ^:private Op [f ^CompletableFuture result])

(defn- ok! [^Op op r] (.complete ^CompletableFuture (.-result op) r))
(defn- fail! [^Op op ^Throwable t] (.completeExceptionally ^CompletableFuture (.-result op) t))

(defn- map-full? [^Throwable t]
  (loop [t t]
    (cond (nil? t) false
          (and (instance? Lmdb$LmdbException t) (= Lmdb/MAP_FULL (.-code ^Lmdb$LmdbException t))) true
          :else (recur (.getCause t)))))

(defn- grow! [{:keys [^long ptr ^ReentrantReadWriteLock resize map-size]}]
  (let [wl (.writeLock resize)]
    (.lock wl)
    (try (Lmdb/setMapsize ptr (long (swap! map-size #(* 2 (long %)))))
         (finally (.unlock wl)))))

(defn- commit-batch
  "Runs `ops` in one write txn, each nested; returns the ops to retry after
  the map grows."
  [{:keys [^long ptr hooks]} ^ArrayList ops]
  (let [txn   (Lmdb/txnBegin ptr 0 0)
        done  (ArrayList.)
        retry (ArrayList.)]
    (try
      (doseq [^Op op ops]
        (let [child (Lmdb/txnBegin ptr txn 0)]
          (try
            (let [r ((.-f op) child)]
              (Lmdb/txnCommit child)
              (.add done [op r]))
            (catch Throwable t
              (Lmdb/txnAbort child)
              (if (map-full? t) (.add retry op) (fail! op t))))))
      (if (.isEmpty done)
        (Lmdb/txnAbort txn)
        (let [after (doall (keep (fn [h] (h txn)) @hooks))]
          (Lmdb/txnCommit txn)
          (doseq [f after] (f))))
      (doseq [[op r] done] (ok! op r))
      retry
      (catch Throwable t
        (Lmdb/txnAbort txn)
        (if (map-full? t)
          (do (.addAll retry (map first done)) retry)
          (do (doseq [[op] done] (fail! op t))
              retry))))))

(defn- run-writer [{:keys [^LinkedBlockingQueue queue] :as env}]
  (loop []
    (let [first-op (.take queue)]
      (when-not (= first-op ::stop)
        (let [batch (ArrayList.)
              _     (.add batch first-op)
              _     (.drainTo queue batch 4096)
              stop? (.remove batch ::stop)]
          (loop [ops batch]
            (let [^ArrayList retry (commit-batch env ops)]
              (when-not (.isEmpty retry)
                (grow! env)
                (recur retry))))
          (when-not stop? (recur)))))))

(defn submit
  "Queues (f txn) for the writer; returns a CompletableFuture of its result,
  completed once the commit holding it is durable."
  ^CompletableFuture [env f]
  (let [r (CompletableFuture.)]
    (.put ^LinkedBlockingQueue (:queue env) (Op. f r))
    r))

(defn write!
  "Runs (f txn) in a write transaction and returns its result once committed.
  f may run more than once (after the map grows), so it should only write."
  [env f]
  (try (.get (submit env f))
       (catch java.util.concurrent.ExecutionException e (throw (.getCause e)))))

(defmacro with-write
  [[txn env] & body]
  `(write! ~env (fn [~(with-meta txn {:tag 'long})] ~@body)))

(defn sync!
  "Flushes everything committed to disk (for :durability :none or :meta)."
  [env]
  (Lmdb/envSync (:ptr env) true))

(defn close
  "Stops the writer (after what's queued), and closes the environment."
  [{:keys [^long ptr ^LinkedBlockingQueue queue ^Thread writer all-readers] :as env}]
  (.put queue ::stop)
  (.join writer 60000)
  (doseq [t @all-readers] (Lmdb/txnAbort (long t)))
  (Lmdb/envSync ptr true)
  (Lmdb/envClose ptr))

;; --- encodings -----------------------------------------------------------------
;; Keys are big-endian, so LMDB's bytewise order is numeric order; values are
;; little-endian, as the Rust store's.

(defn be-key
  "A key of fixed-width big-endian fields: [[:u32 x] [:u64 y] [:bytes b] …]."
  ^bytes [& fields]
  (let [n  (reduce + (map (fn [[t v]] (case t :u8 1 :u32 4 :u64 8 :bytes (alength ^bytes v))) fields))
        bb (ByteBuffer/allocate n)]
    (doseq [[t v] fields]
      (case t
        :u8    (.put bb (unchecked-byte v))
        :u32   (.putInt bb (unchecked-int v))
        :u64   (.putLong bb (long v))
        :bytes (.put bb ^bytes v)))
    (.array bb)))

(defmacro le-u32 [seg off] `(Integer/toUnsignedLong (.get ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_INT_UNALIGNED (long ~off))))
(defmacro le-u64 [seg off] `(.get ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_LONG_UNALIGNED (long ~off)))
(defmacro be-u32 [seg off] `(Integer/toUnsignedLong (.get ~(with-meta seg {:tag `MemorySegment}) (.withOrder ValueLayout/JAVA_INT_UNALIGNED ByteOrder/BIG_ENDIAN) (long ~off))))
(defmacro be-u64 [seg off] `(.get ~(with-meta seg {:tag `MemorySegment}) (.withOrder ValueLayout/JAVA_LONG_UNALIGNED ByteOrder/BIG_ENDIAN) (long ~off)))
