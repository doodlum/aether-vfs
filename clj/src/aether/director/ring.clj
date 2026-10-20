(ns aether.director.ring
  "The director's ring server: a named shared section holding the control ring
  and bulk arena (aether.director.proto), served by worker threads.

  A worker scans the slots for SUBMITTED, takes one with a CAS to PROCESSING,
  reads the request in place, writes the reply over it, and releases
  COMPLETED. Bulk reads go straight from the content into the slot's arena
  bank. Nothing on that path allocates beyond a request's path string.

  Waking matches vfs-win's EventNotifier: a worker that served a request in
  the last `spin-us` spins; otherwise it waits on the server event for 1 ms."
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.tree :as tree])
  (:import (aether.director Guard Native Native$Section)
           (aether.director.tree Dir File)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

;; --- little-endian access to the mapping (x64 is little-endian) ------------

(defmacro ^:private u32 [seg off] `(Integer/toUnsignedLong (.get ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_INT_UNALIGNED (long ~off))))
(defmacro ^:private u64 [seg off] `(.get ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_LONG_UNALIGNED (long ~off)))
(defmacro ^:private put-i32 [seg off v] `(.set ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_INT_UNALIGNED (long ~off) (unchecked-int ~v)))
(defmacro ^:private put-i64 [seg off v] `(.set ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_LONG_UNALIGNED (long ~off) (long ~v)))
(defmacro ^:private put-u8 [seg off v] `(.set ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_BYTE (long ~off) (unchecked-byte ~v)))

;; --- the server's state ------------------------------------------------------

(deftype Ring [^MemorySegment seg ^long stride ^long cap
               ^long arena-offset ^long bank-size
               tree                                 ; aether.director.tree.Tree
               ^ConcurrentHashMap handles ^AtomicLong next-fh
               ^long srv-event ^long cli-event ^long spin-ns
               ^AtomicBoolean stop ^AtomicLong served])

;; A handler gets the slot's payload offset and request length, writes its
;; reply at the payload offset, and returns (status << 32) | reply-length.
(defmacro ^:private reply [status len] `(bit-or (bit-shift-left (long ~status) 32) (long ~len)))

(defn- path-req
  "The vpath of a `root:u32 | path` request starting at `off`, normalized and
  folded; nil for a root this tree doesn't serve or a path that escapes it."
  [^Ring r ^long off ^long len]
  (let [^MemorySegment seg (.-seg r)]
    (when (and (>= len 4) (zero? (u32 seg off)))
      (let [n (- len 4)
            b (byte-array n)]
        (MemorySegment/copy seg ValueLayout/JAVA_BYTE (+ off 4) b (int 0) (int n))
        (let [s (String. b StandardCharsets/UTF_8)]
          (some-> (path/normalize s) path/fold))))))

(defn- op-getattr ^long [^Ring r ^long off ^long len]
  (let [^MemorySegment seg (.-seg r)
        vp   (path-req r off len)
        node (when vp (tree/lookup (.-tree r) vp))]
    ;; not found is OK with found=0, not an error status
    (put-u8 seg off (if node 1 0))
    (put-u8 seg (+ off 1) (if (instance? Dir node) 1 0))
    (put-i64 seg (+ off 2) (if (instance? File node) (.-size ^File node) 0))
    (put-i64 seg (+ off 10) 0)
    (reply p/ok 18)))

(defn- op-readdir ^long [^Ring r ^long off ^long len]
  (let [vp   (path-req r off len)
        node (when vp (tree/lookup (.-tree r) vp))]
    (cond
      (instance? Dir node)
      (let [^bytes b (.-listing ^Dir node)]
        (if (> (alength b) (.-cap r))
          (reply p/overflow 0)
          (do (MemorySegment/copy b (int 0) ^MemorySegment (.-seg r) ValueLayout/JAVA_BYTE off (alength b))
              (reply p/ok (alength b)))))
      (nil? node) (reply p/not-found 0)
      :else       (reply p/not-a-directory 0))))

(defn- wellformed?
  "The shim sends root-relative `/` paths; anything else is a bug on its side."
  [^String s]
  (not (or (.startsWith s "/") (.startsWith s "\\") (.contains s "\\"))))

(defn- op-open ^long [^Ring r ^long off ^long len]
  (if (< len 8)
    (reply p/bad-request 0)
    (let [^MemorySegment seg (.-seg r)
          flags  (u32 seg (+ off 4))
          n      (- len 8)
          b      (byte-array n)
          _      (MemorySegment/copy seg ValueLayout/JAVA_BYTE (+ off 8) b (int 0) (int n))
          raw    (String. b StandardCharsets/UTF_8)
          node   (when (and (zero? (u32 seg off)) (wellformed? raw))
                   (some->> (path/normalize raw) path/fold (tree/lookup (.-tree r))))]
      (cond
        (not (wellformed? raw))                       (reply p/bad-request 0)
        (nil? node)                                   (reply p/not-found 0)
        (not (zero? (bit-and flags p/open-write)))    (reply p/read-only 0)
        :else
        (let [fh (.incrementAndGet ^AtomicLong (.-next-fh r))]
          (.put ^ConcurrentHashMap (.-handles r) fh node)
          (put-i64 seg off fh)
          (put-i64 seg (+ off 8) (if (instance? File node) (.-size ^File node) 0))
          (put-i64 seg (+ off 16) (if (instance? Dir node) 1 0))   ; is_dir:u8 + 7 pad
          (reply p/ok 24))))))

(defn- op-read
  "`flags-slot` is (flags << 32) | slot: a primitive fn takes at most 4 args."
  ^long [^Ring r ^long off ^long len ^long flags-slot]
  (if (< len 20)
    (reply p/bad-request 0)
    (let [^MemorySegment seg (.-seg r)
          flags (bit-shift-right flags-slot 32)
          slot  (bit-and flags-slot 0xFFFFFFFF)
          fh    (u64 seg off)
          pos   (u64 seg (+ off 8))
          want  (u32 seg (+ off 16))
          node  (.get ^ConcurrentHashMap (.-handles r) fh)]
      (cond
        (nil? node)            (reply p/bad-fh 0)
        (instance? Dir node)   (reply p/is-dir 0)
        :else
        (let [^File f node
              avail (max 0 (- (.-size f) pos))]
          (if (or (not (zero? (bit-and flags p/flag-read-bulk))) (>= want p/bulk-threshold))
            ;; bulk: into this slot's arena bank, reply with its mapping offset
            (let [n    (min want avail (.-bank-size r))
                  bank (+ (.-arena-offset r) (* (rem slot p/slot-count) (.-bank-size r)))]
              (MemorySegment/copy (.-data f) pos seg bank n)
              (put-i32 seg off (bit-or n p/read-resp-bulk-bit))
              (put-i32 seg (+ off 4) 0)
              (put-i64 seg (+ off 8) bank)
              (reply p/ok 16))
            ;; inline: n:u32 pad:u32 data
            (let [n (min want avail (- (.-cap r) 8))]
              (MemorySegment/copy (.-data f) pos seg (+ off 8) n)
              (put-i32 seg off n)
              (put-i32 seg (+ off 4) 0)
              (reply p/ok (+ 8 n)))))))))

(defn- op-close ^long [^Ring r ^long off ^long len]
  (if (< len 8)
    (reply p/bad-request 0)
    (if (.remove ^ConcurrentHashMap (.-handles r) (u64 (.-seg r) off))
      (reply p/ok 0)
      (reply p/bad-fh 0))))

(defn- dispatch ^long [r ^long slot]
  (let [^Ring r r]
  (let [^MemorySegment seg (.-seg r)
        base (+ p/ring-header-size (* slot (.-stride r)))
        off  (+ base p/slot-header-size)
        op   (u32 seg (+ base p/sh-opcode))
        len  (u32 seg (+ base p/sh-payload-len))]
    (if (> len (.-cap r))
      (reply p/bad-request 0)
      (case op
        13 (reply p/ok 0)                                         ; HEARTBEAT
        1  (op-getattr r off len)
        2  (op-readdir r off len)
        3  (op-open r off len)
        5  (op-read r off len (bit-or (bit-shift-left (u32 seg (+ base p/sh-flags)) 32) slot))
        11 (op-close r off len)
        (6 7 8 9 10) (reply p/read-only 0)                        ; writes: this tree is read-only
        (reply p/bad-request 0))))))

(defn- serve-slot [^Ring r ^long slot]
  (let [^MemorySegment seg (.-seg r)
        base   (+ p/ring-header-size (* slot (.-stride r)))
        result (Guard/call dispatch r slot (reply p/io-error 0))]
    (put-i32 seg (+ base p/sh-status) (bit-shift-right result 32))
    (put-i32 seg (+ base p/sh-payload-len) (bit-and result 0xFFFFFFFF))
    (Native/setRelease seg (+ base p/sh-state) p/st-completed)
    (Native/setEvent (.-cli-event r))))

(defn- serve-pass
  "Serve every slot that is SUBMITTED now. Returns how many were served."
  ^long [^Ring r]
  (let [^MemorySegment seg (.-seg r) stride (.-stride r)]
    (loop [slot 0 served 0]
      (if (= slot p/slot-count)
        served
        (let [st (+ p/ring-header-size (* slot stride))]
          (if (and (= p/st-submitted (Native/getAcquire seg st))
                   (Native/cas seg st p/st-submitted p/st-processing))
            (do (serve-slot r slot) (recur (inc slot) (inc served)))
            (recur (inc slot) served)))))))

(defn- worker [^Ring r]
  (let [stop (.-stop r) spin-ns (.-spin-ns r)]
    (loop [last-served (long 0)]
      (when-not (.get ^AtomicBoolean stop)
        (let [n (serve-pass r)]
          (if (pos? n)
            (do (.addAndGet ^AtomicLong (.-served r) n)
                (recur (System/nanoTime)))
            (do (if (< (- (System/nanoTime) last-served) spin-ns)
                  (dotimes [_ 64] (Thread/onSpinWait))
                  (Native/waitFor (.-srv-event r) 1))
                (recur last-served))))))))

;; --- lifecycle -----------------------------------------------------------------

(defn- init-header! [^MemorySegment seg {:keys [slots cap stride]}]
  (put-i32 seg p/rh-magic p/magic)
  (put-i32 seg p/rh-version p/version)
  (put-i32 seg p/rh-slot-count slots)
  (put-i32 seg p/rh-slot-stride stride)
  (put-i32 seg p/rh-payload-cap cap)
  (put-i64 seg p/rh-req-seq 0)
  (put-i32 seg p/rh-submit-seq 0)
  (dotimes [s slots]
    (put-i32 seg (+ p/ring-header-size (* (long s) (long stride))) p/st-free)))

(defn start
  "Creates the section `name` (e.g. \"Local\\\\vfs_ring_…\") and its events,
  and serves `tree` on `workers` platform threads. Returns a server map; the
  shim needs :name and :map-bytes (VFS_RING_SECTION / VFS_RING_BYTES)."
  [tree {:keys [name workers spin-us] :or {workers 16 spin-us 400}}]
  (let [g       (p/geometry)
        section (Native/createSection name (:map-bytes g))
        seg     (.view section)
        _       (init-header! seg g)
        srv     (Native/createEvent (str name "_srv"))
        cli     (Native/createEvent (str name "_cli"))
        ring    (Ring. seg (:stride g) (:cap g) (:arena-offset g) (:bank-size g)
                       tree (ConcurrentHashMap.) (AtomicLong.)
                       srv cli (* 1000 (long spin-us))
                       (AtomicBoolean.) (AtomicLong.))
        threads (vec (for [i (range workers)]
                       (doto (Thread. ^Runnable #(worker ring) (str "ring-worker-" i))
                         (.setDaemon true)
                         (.start))))]
    (merge g {:name name :ring ring :section section :threads threads
              :server-event (str name "_srv") :client-event (str name "_cli")})))

(defn stop [{:keys [^Ring ring ^Native$Section section threads]}]
  (.set ^AtomicBoolean (.-stop ring) true)
  (doseq [^Thread t threads] (.join t 1000))
  (Native/closeHandle (.-srv-event ring))
  (Native/closeHandle (.-cli-event ring))
  (.close section))

(defn stats [{:keys [^Ring ring]}]
  {:served (.get ^AtomicLong (.-served ring))
   :open-handles (.size ^ConcurrentHashMap (.-handles ring))})
