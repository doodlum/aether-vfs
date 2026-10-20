(ns aether.director.provider.layer
  "A layer: a named, persistent, writable namespace in the block store
  (aether-storage's LayerProvider). It goes wherever a disk write layer
  goes, typically as the upper of compose/overlay.

  Its namespace is catalog rows (aether.director.storage.catalog), keyed by
  folded path with the spelling kept; a file's data is the store file
  \"L\" + its GUID. Because the catalog lives in the store's LMDB
  environment, a row and the blocks it names commit together: a create, a
  commit of written blocks with the row's new length, a delete, a rename.
  There's nothing to reconcile after a crash.

  Open files share a cell per file: written blocks collect in it (whole
  blocks, read-modify-write) and are committed when there are many, on
  truncate, and on close. Reads see them; other handles on the same file
  do too. Growing a file writes zero blocks (stored once), so a layer file
  never has a missing block: one is an I/O error, never zeros.

  Semantics follow the disk provider: creating makes missing parents; MKDIR
  is OK on an existing directory; deleting a non-empty directory is an I/O
  error; rename replaces a file. A file deleted while open stays readable
  through its handles and is dropped from the store at the last close."
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [aether.director.storage.catalog :as cat]
            [aether.director.storage.ram :as ram]
            [aether.director.store :as store]
            [clojure.string :as str])
  (:import (aether.director.provider Entry Stat)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.util Arrays HexFormat TreeMap)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong)
           (java.util.concurrent.locks Lock ReentrantLock ReentrantReadWriteLock)))

(set! *warn-on-reflection* true)

(def ^:const max-dirty-blocks 64)

(defn- now [] (quot (System/currentTimeMillis) 1000))
(defn- hex ^String [^bytes b] (.formatHex (HexFormat/of) b))

(defmacro ^:private with-lock
  "Runs body holding `lock` (a java.util.concurrent.locks.Lock)."
  [lock & body]
  `(let [^Lock l# ~lock]
     (.lock l#)
     (try ~@body (finally (.unlock l#)))))

;; --- open files ------------------------------------------------------------------------

;; One per open file, shared by its handles. `dirty` (block -> bs bytes) and
;; `committed` are guarded by `lock`'s write side; reads take the read side.
(deftype Cell [^bytes guid ^bytes file-id ^ReentrantReadWriteLock lock
               ^AtomicLong live-len ^AtomicLong committed ^TreeMap dirty
               ^AtomicLong opens ^AtomicBoolean removed])

;; deftype object fields are Object in bytecode: hinted accessors
(defn- guid-of ^bytes [^Cell c] (.-guid c))
(defn- fid-of ^bytes [^Cell c] (.-file-id c))
(defn- lock-of ^ReentrantReadWriteLock [^Cell c] (.-lock c))
(defn- live-of ^AtomicLong [^Cell c] (.-live-len c))
(defn- committed-of ^AtomicLong [^Cell c] (.-committed c))
(defn- dirty-of ^TreeMap [^Cell c] (.-dirty c))
(defn- opens-of ^AtomicLong [^Cell c] (.-opens c))
(defn- removed-of ^AtomicBoolean [^Cell c] (.-removed c))

(defn- blocks-in ^long [^long len ^long bs] (quot (+ len (dec bs)) bs))
(defn- block-len ^long [^long len ^long bs ^long b] (max 0 (min bs (- len (* b bs)))))

(defn- committed-block
  "Block `b` as committed (its length per the committed size), from the RAM
  tier or the store; nil when the store lacks it (an I/O error)."
  ^bytes [storage ^Cell c ^long b]
  (let [{st :store r :ram} storage
        bs   (long (:block-size st))
        blen (block-len (.get (committed-of c)) bs b)]
    (or (ram/get-block r (fid-of c) b)
        (let [buf (byte-array blen)
              res (store/read! st (fid-of c) (* b bs) (MemorySegment/ofArray buf))]
          (when (and res (empty? (:missing res)) (= blen (long (:bytes res))))
            (ram/put-block! r (fid-of c) b buf)
            buf)))))

(defn- load-block!
  "Block `b` for writing: dirty already, or the committed content padded to a
  whole block (zeros past the committed end). Under the write lock."
  ^bytes [storage ^Cell c ^long b]
  (or (.get (dirty-of c) b)
      (let [bs  (long (:block-size (:store storage)))
            buf (byte-array bs)]
        (when (< (* b bs) (.get (committed-of c)))
          (let [^bytes old (or (committed-block storage c b)
                               (throw (ex-info "layer block missing from the store" {:status p/io-error})))]
            (System/arraycopy old 0 buf 0 (alength old))))
        (.put (dirty-of c) b buf)
        buf)))

(defn- commit!
  "Commits a cell's written blocks and length, with its row's length and
  mtime, in one store commit. Under the write lock."
  [storage ^long layer ^Cell c]
  (let [st      (:store storage)
        bs      (long (:block-size st))
        new-len (.get (live-of c))
        old-len (.get (committed-of c))
        ^TreeMap dirty (dirty-of c)]
    (when (or (not (.isEmpty dirty)) (not= new-len old-len))
      ;; a partial last block whose length changes is rewritten whole, and a
      ;; gap left by growing is written as zeros: no slot is ever missing
      (when (and (> new-len old-len) (pos? (rem old-len bs)))
        (load-block! storage c (quot old-len bs)))
      (when (and (< new-len old-len) (pos? (rem new-len bs)) (< (* (quot new-len bs) bs) old-len))
        (let [b (quot new-len bs) ^bytes buf (load-block! storage c b)]
          (Arrays/fill buf (int (rem new-len bs)) (int bs) (byte 0))))
      (doseq [b (range (blocks-in old-len bs) (blocks-in new-len bs))]
        (when-not (.containsKey dirty b) (.put dirty b (byte-array bs))))
      (let [n     (blocks-in new-len bs)
            idx   (vec (filter #(< (long %) n) (keys dirty)))
            ;; consecutive blocks: a run keeps block - position constant
            runs  (->> idx
                       (map-indexed (fn [i b] [(- (long b) (long i)) b]))
                       (partition-by first)
                       (mapv #(mapv second %)))
            block (fn [b] (let [^bytes buf (.get dirty b) blen (block-len new-len bs b)]
                            (if (= blen bs) buf (Arrays/copyOf buf (int blen)))))
            files (vec (map-indexed
                        (fn [i run]
                          (cond-> {:id (fid-of c) :from (first run)
                                   :data (let [bs* (map block run)
                                               out (byte-array (reduce + (map #(alength ^bytes %) bs*)))]
                                           (reduce (fn [^long at ^bytes b] (System/arraycopy b 0 out at (alength b)) (+ at (alength b)))
                                                   0 bs*)
                                           out)}
                            (zero? i) (assoc :len new-len)))
                        runs))
            row!  (fn [txn]
                    ;; the row follows the file, wherever it was renamed to; a removed file has none
                    (when-not (.get (removed-of c))
                      (doseq [[fp e] (cat/subtree st txn layer "")]
                        (when (and (:guid e) (Arrays/equals ^bytes (:guid e) (guid-of c)))
                          (cat/put-entry! st txn layer fp (assoc e :len new-len :mtime (now)))))))]
        (if (seq files)
          (store/store-blocks! st files row!)
          (store/set-len! st (fid-of c) new-len {:also row!}))
        (ram/invalidate! (:ram storage) (fid-of c))
        (doseq [b idx :when (< (long b) n)] (ram/put-block! (:ram storage) (fid-of c) b (block b)))
        (.set (committed-of c) new-len)
        (.clear dirty)))))

;; --- handles ---------------------------------------------------------------------------------

(declare release!)

(deftype FileHandle [layer ^Cell cell ^boolean writable? ^boolean append? ^AtomicBoolean closed]
  prov/Handle
  (handle-stat [_] (Stat. false (.get (live-of cell)) 0))
  (immutable? [_] false)
  (read-into [_ dst dst-off pos len]
    (let [storage (:storage layer)
          bs      (long (:block-size (:store storage)))]
      (with-lock (.readLock (lock-of cell))
        (let [size (.get (live-of cell))
              pos  (long pos)
              end  (min size (+ pos (long len)))
              ^MemorySegment dst dst]
          (if (>= pos end)
            0
            (loop [at pos]
              (if (>= at end)
                (- end pos)
                (let [b    (quot at bs)
                      boff (- at (* b bs))
                      n    (min (- bs boff) (- end at))
                      ^bytes buf (or (.get (dirty-of cell) b)
                                     (when (< (* b bs) (.get (committed-of cell))) (committed-block storage cell b)))]
                  (cond
                    buf (let [avail (max 0 (min n (- (alength buf) boff)))]
                          (MemorySegment/copy buf (int boff) dst ValueLayout/JAVA_BYTE (+ (long dst-off) (- at pos)) (int avail))
                          (when (< avail n) (.fill (.asSlice dst (long (+ (long dst-off) (- at pos) avail)) (long (- n avail))) (byte 0)))
                          (recur (+ at n)))
                    (>= (* b bs) (.get (committed-of cell)))        ; grown, not committed: zeros
                    (do (.fill (.asSlice dst (+ (long dst-off) (- at pos)) (long n)) (byte 0)) (recur (+ at n)))
                    :else p/io-error)))))))))
  (write-from [_ src src-off pos len]
    (if-not writable?
      p/bad-fh
      (let [storage (:storage layer)
            bs      (long (:block-size (:store storage)))
            ^MemorySegment src src]
        (with-lock (.writeLock (lock-of cell))
          (try
            (let [pos (if append? (.get (live-of cell)) (long pos))
                  end (+ pos (long len))]
              (loop [at pos]
                (when (< at end)
                  (let [b    (quot at bs)
                        boff (- at (* b bs))
                        n    (min (- bs boff) (- end at))
                        buf  (load-block! storage cell b)]
                    (MemorySegment/copy src ValueLayout/JAVA_BYTE (+ (long src-off) (- at pos)) buf (int boff) (int n))
                    (recur (+ at n)))))
              (when (> end (.get (live-of cell))) (.set (live-of cell) end))
              (when (> (.size (dirty-of cell)) max-dirty-blocks) (commit! storage (:id layer) cell))
              (long len))
            (catch clojure.lang.ExceptionInfo e (long (or (:status (ex-data e)) p/io-error))))))))
  (truncate! [_ size]
    (if-not writable?
      p/bad-fh
      (let [storage (:storage layer)
            bs      (long (:block-size (:store storage)))
            size    (long size)]
        (with-lock (.writeLock (lock-of cell))
          (try
            (let [n (blocks-in size bs)]
              (doseq [b (vec (.keySet (.tailMap (dirty-of cell) n true)))] (.remove (dirty-of cell) b))
              (when-let [^bytes buf (and (pos? (rem size bs)) (.get (dirty-of cell) (quot size bs)))]
                (Arrays/fill buf (int (rem size bs)) (int bs) (byte 0)))
              (.set (live-of cell) size)
              (commit! storage (:id layer) cell)
              p/ok)
            (catch clojure.lang.ExceptionInfo e (long (or (:status (ex-data e)) p/io-error))))))))
  (close! [_]
    (when (.compareAndSet closed false true)
      (with-lock (.writeLock (lock-of cell))
        (commit! (:storage layer) (:id layer) cell))
      (release! layer cell))))

(deftype DirHandle []
  prov/Handle
  (handle-stat [_] prov/dir-stat)
  (immutable? [_] false)
  (read-into [_ _ _ _ _] p/is-dir)
  (write-from [_ _ _ _ _] p/is-dir)
  (truncate! [_ _] p/is-dir)
  (close! [_] nil))

;; --- the provider ---------------------------------------------------------------------------------

(defn- live-cell ^Cell [layer ^bytes guid] (.get ^ConcurrentHashMap (:cells layer) (hex guid)))

(defn- acquire!
  "The cell of the file `entry`, created on first open, with one more open.
  Under the namespace lock."
  ^Cell [layer entry]
  (let [^ConcurrentHashMap cells (:cells layer)
        k (hex (:guid entry))
        ^Cell c (or (.get cells k)
                    (let [fid (cat/layer-file-id (:guid entry))
                          len (long (or (:len (store/stat (:store (:storage layer)) fid)) (:len entry)))
                          c   (Cell. (:guid entry) fid (ReentrantReadWriteLock. true) (AtomicLong. len)
                                     (AtomicLong. len) (TreeMap.) (AtomicLong.) (AtomicBoolean.))]
                      (.put cells k c)
                      c))]
    (.incrementAndGet (opens-of c))
    c))

(defmacro ^:private with-ns
  "Runs body holding `layer`'s namespace lock."
  [layer & body]
  `(with-lock (:ns ~layer) ~@body))

(defn- release!
  "One open of `c` fewer; the last one of a deleted file drops its data."
  [layer ^Cell c]
  (with-ns layer
    (when (zero? (.decrementAndGet (opens-of c)))
      (.remove ^ConcurrentHashMap (:cells layer) (hex (guid-of c)))
      (when (.get (removed-of c))
        (store/delete! (:store (:storage layer)) (fid-of c))))))

(defn- stat-of ^Stat [layer e]
  (if (:dir? e)
    prov/dir-stat
    (Stat. false (if-let [c (live-cell layer (:guid e))] (.get (live-of c)) (long (:len e))) (long (:mtime e)))))

(defn ancestors-of
  "The folded and spelled paths of each ancestor of spelled vpath `vp`, root first (not \"\")."
  [^String vp]
  (let [parts (str/split vp #"/")]
    (for [i (range 1 (count parts))]
      (let [spelled (str/join "/" (take i parts))] [(path/fold spelled) (nth parts (dec i))]))))

(defn- make-parents!
  "In a write txn: the missing ancestors of `vp` as directories. Returns a
  status if one is a file."
  [st txn layer ^String vp]
  (reduce (fn [_ [folded nm]]
            (if-let [e (cat/get-entry st txn layer folded)]
              (when-not (:dir? e) (reduced p/not-a-directory))
              (do (cat/put-entry! st txn layer folded {:dir? true :name nm :mtime (now)}) nil)))
          nil (ancestors-of vp)))

(defn- drop-file!
  "In a write txn: a file entry is gone; its data goes now, or at its last
  close if it's open (returns the cell to mark removed after the commit)."
  [layer txn e]
  (if-let [c (live-cell layer (:guid e))]
    c
    (do (store/delete-file! (:store (:storage layer)) txn (cat/layer-file-id (:guid e))) nil)))

(defn- write-flags? [^long flags] (not (zero? (bit-and flags (bit-or p/open-write p/open-trunc p/open-append)))))

(defrecord Layer [storage ^long id name ^ReentrantLock ns ^ConcurrentHashMap cells]
  prov/Provider
  (stat [this vp]
    (store/read* (:store storage) (fn [txn] (some->> (cat/get-entry (:store storage) txn id vp) (stat-of this)))))
  (list-dir [this vp]
    (let [st (:store storage)]
      (store/read* st (fn [txn]
                        (when-let [e (cat/get-entry st txn id vp)]
                          (if-not (:dir? e)
                            p/not-a-directory
                            (vec (for [[_ c] (cat/children st txn id vp)]
                                   (Entry. (:name c) (stat-of this c))))))))))
  (spelling [_ vp]
    (let [st (:store storage)]
      (if (= "" vp)
        ""
        (store/read* st (fn [txn]
                          (let [parts (str/split vp #"/")]
                            (loop [i 1 out []]
                              (if (> i (count parts))
                                (str/join "/" out)
                                (when-let [e (cat/get-entry st txn id (str/join "/" (take i parts)))]
                                  (recur (inc i) (conj out (:name e))))))))))))
  (open [this vp flags]
    (let [st     (:store storage)
          flags  (long flags)
          folded (path/fold vp)
          create? (not (zero? (bit-and flags p/open-create)))
          excl?   (not (zero? (bit-and flags p/open-excl)))
          trunc?  (not (zero? (bit-and flags p/open-trunc)))]
      (with-ns this
        (let [e (store/read* st (fn [txn] (cat/get-entry st txn id folded)))
              e (cond
                  (and e (:dir? e))    (if (write-flags? flags) p/is-dir e)
                  (and e create? excl?) p/exists
                  e                     e
                  (not create?)         p/not-found
                  (= "" folded)         p/is-dir
                  :else
                  (let [guid (cat/new-guid)]
                    (store/write! st (fn [txn]
                                       (if-let [e (cat/get-entry st txn id folded)]
                                         e
                                         (or (make-parents! st txn id vp)
                                             (let [e {:guid guid :len 0 :mtime (now) :name (path/file-name vp)}]
                                               (cat/put-entry! st txn id folded e)
                                               (store/resize! st txn (cat/layer-file-id guid) 0)
                                               e)))))))]
          (cond
            (number? e) e
            (:dir? e)   (DirHandle.)
            :else
            (let [c (acquire! this e)
                  h (FileHandle. this c (write-flags? flags) (not (zero? (bit-and flags p/open-append))) (AtomicBoolean.))]
              (if (and trunc? (pos? (.get (live-of c))))
                (let [s (long (prov/truncate! h 0))]
                  (if (neg? s) (do (prov/close! h) s) h))
                h)))))))
  (mkdir! [this vp]
    (let [st (:store storage) folded (path/fold vp)]
      (with-ns this
        (store/write! st (fn [txn]
                           (if-let [e (cat/get-entry st txn id folded)]
                             (if (:dir? e) p/ok p/io-error)
                             (or (make-parents! st txn id vp)
                                 (do (cat/put-entry! st txn id folded {:dir? true :name (path/file-name vp) :mtime (now)})
                                     p/ok))))))))
  (delete! [this vp]
    (let [st (:store storage) folded (path/fold vp)]
      (if (= "" folded)
        p/bad-request
        (with-ns this
          (let [[status ^Cell removed]
                (store/write! st (fn [txn]
                                   (if-let [e (cat/get-entry st txn id folded)]
                                     (if (:dir? e)
                                       (if (seq (cat/children st txn id folded))
                                         [p/io-error]
                                         (do (cat/del-entry! st txn id folded) [p/ok]))
                                       (do (cat/del-entry! st txn id folded)
                                           [p/ok (drop-file! this txn e)]))
                                     [p/not-found])))]
            (when removed (.set (removed-of removed) true))
            status)))))
  (rename! [this from to]
    (let [st (:store storage)
          f  (path/fold from)
          t  (path/fold to)]
      (cond
        (or (= "" f) (= "" t)) p/bad-request
        (str/starts-with? t (str f "/")) p/bad-request
        :else
        (with-ns this
          (let [[status removed]
                (store/write! st (fn [txn]
                                   (let [src (cat/get-entry st txn id f)
                                         dst (when (not= f t) (cat/get-entry st txn id t))]
                                     (cond
                                       (nil? src) [p/not-found]
                                       (= f t) (do (cat/put-entry! st txn id f (assoc src :name (path/file-name to))) [p/ok])
                                       (and dst (:dir? dst) (seq (cat/children st txn id t))) [p/io-error]
                                       :else
                                       (or (when-let [s (make-parents! st txn id to)] [s])
                                           (let [gone (when (and dst (not (:dir? dst)))
                                                        (drop-file! this txn dst))]
                                             (when dst (cat/del-entry! st txn id t))
                                             (doseq [[p e] (cat/subtree st txn id f)]
                                               (let [np (str t (subs p (count f)))]
                                                 (cat/del-entry! st txn id p)
                                                 (cat/put-entry! st txn id np (if (= p f) (assoc e :name (path/file-name to)) e))))
                                             [p/ok gone]))))))]
            (when removed (.set (removed-of removed) true))
            status))))))

(defn layer
  "The layer provider for layer `id` (`name`) of `storage`
  ({:store :ram})."
  [storage id name]
  (->Layer storage id name (ReentrantLock.) (ConcurrentHashMap.)))

(defn commit-open!
  "Commits every open file's written blocks (before a freeze or a close)."
  [^Layer l]
  (doseq [^Cell c (.values ^ConcurrentHashMap (:cells l))]
    (with-lock (.writeLock (lock-of c))
      (commit! (:storage l) (:id l) c))))

(defn open-files ^long [^Layer l] (.size ^ConcurrentHashMap (:cells l)))
