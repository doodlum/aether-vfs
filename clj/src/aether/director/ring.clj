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
               ^AtomicBoolean stop ^AtomicLong served
               stage-exec])                         ; (fn [vpath] real-path-or-nil), or nil

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
          ;; is_dir u8, flags u8, pad[2], mount_gen u32. A tree never changes
          ;; while served, so content is immutable and its generation is 1:
          ;; that lets the shim cache small reads.
          (put-u8 seg (+ off 16) (if (instance? Dir node) 1 0))
          (put-u8 seg (+ off 17) p/open-flag-immutable)
          (put-u8 seg (+ off 18) 0)
          (put-u8 seg (+ off 19) 0)
          (put-i32 seg (+ off 20) 1)
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

(defn- utf8-at
  "The UTF-8 string of `n` bytes at `off`."
  ^String [^MemorySegment seg ^long off ^long n]
  (let [b (byte-array n)]
    (MemorySegment/copy seg ValueLayout/JAVA_BYTE off b (int 0) (int n))
    (String. b StandardCharsets/UTF_8)))

(defn- reply-utf8
  "Writes `s` as the reply at `off` (raw UTF-8, no length prefix)."
  ^long [^Ring r ^long off ^String s]
  (let [b (.getBytes s StandardCharsets/UTF_8)]
    (if (> (alength b) (.-cap r))
      (reply p/overflow 0)
      (do (MemorySegment/copy b (int 0) ^MemorySegment (.-seg r) ValueLayout/JAVA_BYTE off (alength b))
          (reply p/ok (alength b))))))

(defn- op-stored-names
  "root u32, skip u32 | path -> the content's spelling of the path's components
  from `skip` on, joined by `/` (for GetFinalPathNameByHandle)."
  ^long [^Ring r ^long off ^long len]
  (let [^MemorySegment seg (.-seg r)]
    (if (or (< len 8) (not (zero? (u32 seg off))))
      (reply p/not-found 0)
      (let [skip    (u32 seg (+ off 4))
            vp      (some-> (path/normalize (utf8-at seg (+ off 8) (- len 8))) path/fold)
            spelled (when vp (tree/spelling (.-tree r) vp))]
        (if (nil? spelled)
          (reply p/not-found 0)
          (let [parts (if (= "" spelled) [] (vec (.split spelled "/")))]
            (reply-utf8 r off (String/join "/" ^Iterable (drop skip parts)))))))))

(defn- op-stage-exec
  "root u32 | path -> a real path the shim can start a VFS-only EXE from."
  ^long [^Ring r ^long off ^long len]
  (let [f  (.-stage-exec r)
        vp (path-req r off len)]
    (cond
      (nil? f)  (reply p/not-supported 0)
      (nil? vp) (reply p/not-found 0)
      :else     (if-let [real (f vp)]
                  (reply-utf8 r off real)
                  (reply p/not-found 0)))))

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
        14 (op-stored-names r off len)
        24 (op-stage-exec r off len)
        (6 7 8 9 10 23) (reply p/read-only 0)                     ; writes: this tree is read-only
        (15 16 17 18 19 20 21 22) (reply p/not-supported 0)       ; registry: not served
        (reply p/bad-request 0))))))

(defn- serve-slot
  "Serves the request in `slot` (taken: PROCESSING) and completes it. The
  reply carries `ack`, the low half of the request's id, which the client
  checks. A client that gave up meanwhile has marked the slot ABANDONED: its
  reply is dropped and the slot freed."
  [^Ring r ^long slot]
  (let [^MemorySegment seg (.-seg r)
        base   (+ p/ring-header-size (* slot (.-stride r)))
        state  (+ base p/sh-state)
        req-id (u64 seg (+ base p/sh-req-id))
        result (Guard/call dispatch r slot (reply p/io-error 0))]
    (put-i32 seg (+ base p/sh-status) (bit-shift-right result 32))
    (put-i32 seg (+ base p/sh-payload-len) (bit-and result 0xFFFFFFFF))
    (put-i32 seg (+ base p/sh-ack) req-id)
    (if (Native/cas seg state p/st-processing p/st-completed)
      (Native/setEvent (.-cli-event r))
      (Native/cas seg state p/st-abandoned p/st-free))))

(defn- serve-pass
  "Serve every slot that is SUBMITTED now, scanning from `start` (workers
  rotate it, so no slot is always last). Returns how many were served."
  ^long [^Ring r ^long start]
  (let [^MemorySegment seg (.-seg r) stride (.-stride r)]
    (loop [i 0 served 0]
      (if (= i p/slot-count)
        served
        (let [slot (rem (+ start i) p/slot-count)
              st   (+ p/ring-header-size (* slot stride))]
          (if (and (= p/st-submitted (Native/getAcquire seg st))
                   (Native/cas seg st p/st-submitted p/st-processing))
            (do (serve-slot r slot) (recur (inc i) (inc served)))
            (recur (inc i) served)))))))

(defn- worker [^Ring r]
  (let [stop (.-stop r) spin-ns (.-spin-ns r)]
    (loop [last-served (long 0) start (long 0)]
      (when-not (.get ^AtomicBoolean stop)
        (let [n    (serve-pass r start)
              next (rem (inc start) p/slot-count)]
          (if (pos? n)
            (do (.addAndGet ^AtomicLong (.-served r) n)
                (recur (System/nanoTime) next))
            (do (if (< (- (System/nanoTime) last-served) spin-ns)
                  (dotimes [_ 64] (Thread/onSpinWait))
                  (Native/waitFor (.-srv-event r) 1))
                (recur last-served next))))))))

;; --- lifecycle -----------------------------------------------------------------

(defn- init-header!
  "Writes the ring header (version 4) and frees every slot. `workers` is
  published as worker_hint: a client sizes how many data requests it keeps in
  flight by it."
  [^MemorySegment seg {:keys [slots cap stride]} workers]
  (put-i32 seg p/rh-magic p/magic)
  (put-i32 seg p/rh-version p/version)
  (put-i32 seg p/rh-slot-count slots)
  (put-i32 seg p/rh-slot-stride stride)
  (put-i32 seg p/rh-payload-cap cap)
  (put-i32 seg p/rh-worker-hint workers)
  (put-i64 seg p/rh-req-seq 0)
  (put-i32 seg p/rh-submit-seq 0)
  (put-i64 seg p/rh-reg-gen 0)
  (dotimes [s slots]
    (put-i32 seg (+ p/ring-header-size (* (long s) (long stride))) p/st-free)))

;; --- warm-up -------------------------------------------------------------------
;;
;; A game's startup is the ring's busiest moment and the JVM's coldest: the
;; first few thousand requests would run before the JIT has compiled the
;; handlers. So before serving, the director drives synthetic requests through
;; the same code (serve-pass, dispatch, every read-side handler) on a private
;; copy of the ring, over paths from the real tree. Each reply is checked, so
;; the warm-up is also a self-test of the index.

(defn- request!
  "Writes a request into `slot` of a private ring and marks it SUBMITTED, as a
  client would."
  [^MemorySegment seg stride slot op flags ^bytes payload]
  (let [base (+ p/ring-header-size (* (long slot) (long stride)))]
    (put-i32 seg (+ base p/sh-opcode) op)
    (put-i32 seg (+ base p/sh-flags) flags)
    (put-i32 seg (+ base p/sh-payload-len) (alength payload))
    (MemorySegment/copy payload (int 0) seg ValueLayout/JAVA_BYTE (+ base p/slot-header-size) (alength payload))
    (Native/setRelease seg (+ base p/sh-state) p/st-submitted)))

(defn- le-bytes ^bytes [& fields]
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [[kind v] (partition 2 fields)]
      (case kind
        :u32  (let [b (.order (java.nio.ByteBuffer/allocate 4) java.nio.ByteOrder/LITTLE_ENDIAN)]
                (.write out (.array (.putInt b (unchecked-int v)))))
        :u64  (let [b (.order (java.nio.ByteBuffer/allocate 8) java.nio.ByteOrder/LITTLE_ENDIAN)]
                (.write out (.array (.putLong b (long v)))))
        :utf8 (.write out (.getBytes ^String v StandardCharsets/UTF_8))))
    (.toByteArray out)))

(defn warm-up!
  "Runs `rounds` rounds of synthetic requests (9 per round; 3 when the tree
  has no files) through the server's code on a private ring over `tree`.
  Returns {:requests :ms}; throws if a reply is wrong."
  [tree {:keys [rounds] :or {rounds 3000}}]
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [g      (p/geometry)
          seg    (.allocate arena (long (:map-bytes g)) 64)
          stride (long (:stride g))
          _      (init-header! seg g 1)
          r      (Ring. seg stride (:cap g) (:arena-offset g) (:bank-size g)
                        tree (ConcurrentHashMap.) (AtomicLong.)
                        0 0 0 (AtomicBoolean.) (AtomicLong.) nil)
          {:keys [files dirs]} (tree/sample tree 64)
          slot   (long-array 1)
          n      (long-array 1)
          call   (fn [op flags ^bytes payload]
                   (let [s (rem (aget slot 0) p/slot-count)
                         base (+ p/ring-header-size (* s stride))]
                     (aset slot 0 (inc (aget slot 0)))
                     (aset n 0 (inc (aget n 0)))
                     (request! seg stride s op flags payload)
                     (when-not (= 1 (serve-pass r 0))
                       (throw (ex-info "warm-up: request not served" {:op op})))
                     (let [status (.get seg ValueLayout/JAVA_INT_UNALIGNED (+ base p/sh-status))
                           fh     (.get seg ValueLayout/JAVA_LONG_UNALIGNED (+ base p/slot-header-size))]
                       (Native/setRelease seg (+ base p/sh-state) p/st-free)
                       [status fh])))
          ok!    (fn [[status fh] what]
                   (when-not (zero? (long status))
                     (throw (ex-info (str "warm-up: " what " failed with status " status) {})))
                   fh)
          t0     (System/nanoTime)]
      (dotimes [i rounds]
        (let [^String d (nth dirs (rem i (count dirs)))]       ; there's always the root
          (ok! (call p/op-heartbeat 0 (byte-array 0)) "heartbeat")
          (ok! (call p/op-getattr 0 (le-bytes :u32 0 :utf8 (str d "/no-such-file"))) "getattr (miss)")
          (ok! (call p/op-readdir 0 (le-bytes :u32 0 :utf8 d)) "readdir")
          (when-let [^String f (when (seq files) (nth files (rem i (count files))))]
            (ok! (call p/op-getattr 0 (le-bytes :u32 0 :utf8 f)) "getattr")
            (ok! (call p/op-stored-names 0 (le-bytes :u32 0 :u32 0 :utf8 f)) "stored names")
            (let [fh (ok! (call p/op-open 1 (le-bytes :u32 0 :u32 1 :utf8 f)) "open")]
            (ok! (call p/op-read 0 (le-bytes :u64 fh :u64 0 :u32 4096 :u32 0)) "read")
            (ok! (call p/op-read p/flag-read-bulk (le-bytes :u64 fh :u64 0 :u32 4096 :u32 0)) "bulk read")
            (ok! (call p/op-close 0 (le-bytes :u64 fh)) "close")))))
      {:requests (aget n 0) :ms (/ (- (System/nanoTime) t0) 1e6)})))

(defn start
  "Creates the section `name` (e.g. \"Local\\\\vfs_ring_…\") and its events,
  and serves `tree` on `workers` platform threads. Returns a server map; the
  shim needs :name and :map-bytes (VFS_RING_SECTION / VFS_RING_BYTES).

  First runs `warm-up` rounds of synthetic requests (see warm-up!; 0 skips
  it), so the handlers are compiled before the first real request.
  `stage-exec` answers STAGE_EXEC: (fn [vpath] real-path-or-nil)."
  [tree {:keys [name workers spin-us warm-up stage-exec] :or {workers 16 spin-us 400 warm-up 3000}}]
  (let [warmed  (when (pos? (long warm-up)) (warm-up! tree {:rounds warm-up}))
        g       (p/geometry)
        section (Native/createSection name (:map-bytes g))
        seg     (.view section)
        _       (init-header! seg g workers)
        srv     (Native/createEvent (str name "_srv"))
        cli     (Native/createEvent (str name "_cli"))
        ring    (Ring. seg (:stride g) (:cap g) (:arena-offset g) (:bank-size g)
                       tree (ConcurrentHashMap.) (AtomicLong.)
                       srv cli (* 1000 (long spin-us))
                       (AtomicBoolean.) (AtomicLong.) stage-exec)
        threads (vec (for [i (range workers)]
                       (doto (Thread. ^Runnable #(worker ring) (str "ring-worker-" i))
                         (.setDaemon true)
                         (.start))))]
    (merge g {:name name :ring ring :section section :threads threads :warm-up warmed
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
