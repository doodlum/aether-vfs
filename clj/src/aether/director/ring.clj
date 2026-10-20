(ns aether.director.ring
  "The director's ring server: a named shared section holding the control ring
  and bulk arena (aether.director.proto), served by worker threads over one
  provider per root (aether.director.provider).

  A worker scans the slots for SUBMITTED, takes one with a CAS to PROCESSING,
  reads the request in place, writes the reply over it, and completes it with
  a CAS to COMPLETED (or frees a slot its client abandoned). Bulk reads go
  straight from the provider into the slot's arena bank.

  Waking matches vfs-win's EventNotifier: a worker that served a request in
  the last `spin-us` spins; otherwise it waits on the server event for 1 ms."
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [aether.director.registry :as registry])
  (:import (aether.director Guard Native Native$Section)
           (aether.director.provider Stat)
           (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong LongAdder)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

;; --- little-endian access to the mapping (x64 is little-endian) ------------

(defmacro ^:private u32 [seg off] `(Integer/toUnsignedLong (.get ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_INT_UNALIGNED (long ~off))))
(defmacro ^:private u64 [seg off] `(.get ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_LONG_UNALIGNED (long ~off)))
(defmacro ^:private put-i32 [seg off v] `(.set ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_INT_UNALIGNED (long ~off) (unchecked-int ~v)))
(defmacro ^:private put-i64 [seg off v] `(.set ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_LONG_UNALIGNED (long ~off) (long ~v)))
(defmacro ^:private put-u8 [seg off v] `(.set ~(with-meta seg {:tag `MemorySegment}) ValueLayout/JAVA_BYTE (long ~off) (unchecked-byte ~v)))

;; --- the server's state ------------------------------------------------------

(def ^:const max-roots 16)

;; An open file: its handle, and whether writes append.
(deftype Open [handle ^boolean append?])

(deftype Ring [^MemorySegment seg ^long stride ^long cap
               ^long arena-offset ^long bank-size
               ^objects roots                       ; root id -> provider (or nil)
               ^ConcurrentHashMap handles ^AtomicLong next-fh
               ^long srv-event ^long cli-event ^long spin-ns
               ^AtomicBoolean stop ^AtomicLong served
               stage-exec                           ; (fn [vpath] real-path-or-nil), or nil
               registry                             ; an aether.director.registry, or nil (not served)
               ^ConcurrentHashMap trace])           ; "op outcome path" -> LongAdder, or nil (off)

;; A handler gets the slot's payload offset and request length, writes its
;; reply at the payload offset, and returns (status << 32) | reply-length.
(defmacro ^:private reply [status len] `(bit-or (bit-shift-left (long ~status) 32) (long ~len)))

(defn- provider-of
  "The provider serving root `root`, or nil for a root this director doesn't serve."
  [^Ring r ^long root]
  (let [^objects roots (.-roots r)]
    (when (< root (alength roots)) (aget roots root))))

(defn- roots-array ^objects [root-providers]
  (let [a (object-array max-roots)]
    (doseq [[id prov] root-providers] (aset a (int id) prov))
    a))

(defn- trace!
  "Counts one request by op, outcome and path, when tracing is on."
  [^Ring r op outcome vp]
  (when-let [^ConcurrentHashMap t (.-trace r)]
    (.increment ^LongAdder (.computeIfAbsent t (str op " " outcome " " vp)
                                             (reify java.util.function.Function
                                               (apply [_ _] (LongAdder.)))))))

(defn- utf8-at
  "The UTF-8 string of `n` bytes at `off`."
  ^String [^MemorySegment seg ^long off ^long n]
  (let [b (byte-array n)]
    (MemorySegment/copy seg ValueLayout/JAVA_BYTE off b (int 0) (int n))
    (String. b StandardCharsets/UTF_8)))

(defn- vpath
  "A request's path, normalized (and folded when `fold?`); nil when it would
  leave its root."
  [^String raw fold?]
  (when-let [n (path/normalize raw)] (if fold? (path/fold n) n)))

(defn- reply-bytes ^long [^Ring r ^long off ^bytes b]
  (if (> (alength b) (.-cap r))
    (reply p/overflow 0)
    (do (MemorySegment/copy b (int 0) ^MemorySegment (.-seg r) ValueLayout/JAVA_BYTE off (alength b))
        (reply p/ok (alength b)))))

(defn- reply-utf8 ^long [^Ring r ^long off ^String s]
  (reply-bytes r off (.getBytes s StandardCharsets/UTF_8)))

(defmacro ^:private status-reply
  "A provider op's status as a reply with no payload."
  [st]
  `(let [s# (long ~st)] (reply s# 0)))

;; --- reads --------------------------------------------------------------------

(defn- path-op
  "The provider and folded vpath of a `root u32 | path` request, or nil."
  [^Ring r ^long off ^long len]
  (let [seg (.-seg r)]
    (when (>= len 4)
      (when-let [prov (provider-of r (u32 seg off))]
        (when-let [vp (vpath (utf8-at seg (+ off 4) (- len 4)) true)]
          [prov vp])))))

(defn- op-getattr ^long [^Ring r ^long off ^long len]
  (let [seg (.-seg r)
        [prov vp] (path-op r off len)
        ^Stat st (when prov (prov/stat prov vp))]
    (trace! r "getattr" (if st "found" "missing") vp)
    ;; not found is OK with found=0, not an error status
    (put-u8 seg off (if st 1 0))
    (put-u8 seg (+ off 1) (if (and st (.-dir? st)) 1 0))
    (put-i64 seg (+ off 2) (if st (.-size st) 0))
    (put-i64 seg (+ off 10) (if st (.-mtime st) 0))
    (reply p/ok 18)))

(defn- op-readdir ^long [^Ring r ^long off ^long len]
  (let [[prov vp] (path-op r off len)]
    (trace! r "readdir" (if prov "asked" "no-root") vp)
    (if (nil? prov)
      (reply p/not-found 0)
      (if-let [b (prov/listing-bytes prov vp)]
        (reply-bytes r off b)
        (let [l (prov/list-dir prov vp)]
          (cond (nil? l)    (reply p/not-found 0)
                (number? l) (status-reply l)
                :else       (reply-bytes r off (prov/encode-listing l))))))))

(defn- wellformed?
  "The shim sends root-relative `/` paths; anything else is a bug on its side."
  [^String s]
  (not (or (.startsWith s "/") (.startsWith s "\\") (.contains s "\\"))))

(defn- op-open ^long [^Ring r ^long off ^long len]
  (if (< len 8)
    (reply p/bad-request 0)
    (let [seg   (.-seg r)
          prov  (provider-of r (u32 seg off))
          flags (let [f (u32 seg (+ off 4))] (if (zero? f) p/open-read f))
          raw   (utf8-at seg (+ off 8) (- len 8))
          ;; a path that creates a name keeps the caller's spelling
          vp    (when (wellformed? raw) (vpath raw false))
          h     (cond (nil? vp) p/bad-request
                      (nil? prov) p/not-found
                      :else (prov/open prov vp flags))]
      (trace! r "open" (if (number? h) h "ok") raw)
      (if (number? h)
        (status-reply h)
        (let [fh (.incrementAndGet ^AtomicLong (.-next-fh r))
              ^Stat st (prov/handle-stat h)]
          (.put ^ConcurrentHashMap (.-handles r) fh
                (Open. h (not (zero? (bit-and flags p/open-append)))))
          ;; fh u64, size u64, is_dir u8, flags u8 (bit0 immutable), pad[2],
          ;; mount_gen u32: an immutable handle lets the shim cache small reads
          (put-i64 seg off fh)
          (put-i64 seg (+ off 8) (.-size st))
          (put-u8 seg (+ off 16) (if (.-dir? st) 1 0))
          (put-u8 seg (+ off 17) (if (prov/immutable? h) p/open-flag-immutable 0))
          (put-u8 seg (+ off 18) 0)
          (put-u8 seg (+ off 19) 0)
          (put-i32 seg (+ off 20) 1)
          (reply p/ok 24))))))

(defn- handle-of [^Ring r ^long fh] (.get ^ConcurrentHashMap (.-handles r) fh))

(defn- op-read
  "`flags-slot` is (flags << 32) | slot: a primitive fn takes at most 4 args."
  ^long [^Ring r ^long off ^long len ^long flags-slot]
  (if (< len 20)
    (reply p/bad-request 0)
    (let [seg   (.-seg r)
          flags (bit-shift-right flags-slot 32)
          slot  (bit-and flags-slot 0xFFFFFFFF)
          ^Open o (handle-of r (u64 seg off))
          pos   (u64 seg (+ off 8))
          want  (u32 seg (+ off 16))]
      (cond
        (nil? o) (reply p/bad-fh 0)
        (.-dir? ^Stat (prov/handle-stat (.-handle o))) (reply p/is-dir 0)
        (or (not (zero? (bit-and flags p/flag-read-bulk))) (>= want p/bulk-threshold))
        ;; bulk: into this slot's arena bank, reply with its mapping offset
        (let [bank (+ (.-arena-offset r) (* (rem slot p/slot-count) (.-bank-size r)))
              n    (long (prov/read-into (.-handle o) seg bank pos (min want (.-bank-size r))))]
          (if (neg? n)
            (reply n 0)
            (do (put-i32 seg off (bit-or n p/read-resp-bulk-bit))
                (put-i32 seg (+ off 4) 0)
                (put-i64 seg (+ off 8) bank)
                (reply p/ok 16))))
        :else
        ;; inline: n:u32 pad:u32 data
        (let [n (long (prov/read-into (.-handle o) seg (+ off 8) pos (min want (- (.-cap r) 8))))]
          (if (neg? n)
            (reply n 0)
            (do (put-i32 seg off n)
                (put-i32 seg (+ off 4) 0)
                (reply p/ok (+ 8 n)))))))))

(defn- op-close ^long [^Ring r ^long off ^long len]
  (if (< len 8)
    (reply p/bad-request 0)
    (if-let [^Open o (.remove ^ConcurrentHashMap (.-handles r) (u64 (.-seg r) off))]
      (do (prov/close! (.-handle o)) (reply p/ok 0))
      (reply p/bad-fh 0))))

;; --- writes -------------------------------------------------------------------

(defn- op-write
  "fh u64, offset u64, len u32, pad u32, data -> written u32, pad u32."
  ^long [^Ring r ^long off ^long len]
  (let [seg (.-seg r)]
    (if (< len 24)
      (reply p/bad-request 0)
      (let [^Open o (handle-of r (u64 seg off))
            n       (u32 seg (+ off 16))]
        (cond
          (nil? o)              (reply p/bad-fh 0)
          (> (+ 24 n) len)      (reply p/bad-request 0)
          :else
          (let [h   (.-handle o)
                pos (if (.-append? o) (.-size ^Stat (prov/handle-stat h)) (u64 seg (+ off 8)))
                w   (long (prov/write-from h seg (+ off 24) pos n))]
            (if (neg? w)
              (reply w 0)
              (do (put-i32 seg off w)
                  (put-i32 seg (+ off 4) 0)
                  (reply p/ok 8)))))))))

(defn- op-setattr
  "fh u64, size u64: the file's new length."
  ^long [^Ring r ^long off ^long len]
  (if (< len 16)
    (reply p/bad-request 0)
    (if-let [^Open o (handle-of r (u64 (.-seg r) off))]
      (status-reply (prov/truncate! (.-handle o) (u64 (.-seg r) (+ off 8))))
      (reply p/bad-fh 0))))

(defn- op-delete ^long [^Ring r ^long off ^long len]
  (let [seg (.-seg r)
        prov (when (>= len 4) (provider-of r (u32 seg off)))
        vp   (when prov (vpath (utf8-at seg (+ off 4) (- len 4)) false))]
    (trace! r "delete" (if vp "asked" "bad") vp)
    (if (nil? vp) (reply p/not-found 0) (status-reply (prov/delete! prov vp)))))

(defn- op-mkdir
  "root u32, mode u32 | path (the mode is ignored)."
  ^long [^Ring r ^long off ^long len]
  (let [seg (.-seg r)
        prov (when (>= len 8) (provider-of r (u32 seg off)))
        vp   (when prov (vpath (utf8-at seg (+ off 8) (- len 8)) false))]
    (trace! r "mkdir" (if vp "asked" "bad") vp)
    (if (nil? vp) (reply p/not-found 0) (status-reply (prov/mkdir! prov vp)))))

(defn- copy-across!
  "Moves a file from one provider to another: copy, then delete the source."
  ^long [from-prov from to-prov to]
  (let [src (prov/open from-prov from p/open-read)]
    (cond
      (number? src) src
      (.-dir? ^Stat (prov/handle-stat src)) (do (prov/close! src) p/cross-root)
      :else
      (let [dst (prov/open to-prov to (bit-or p/open-write p/open-create p/open-trunc))]
        (if (number? dst)
          (do (prov/close! src) dst)
          (with-open [arena (Arena/ofConfined)]
            (let [size (.-size ^Stat (prov/handle-stat src))
                  buf  (.allocate arena (long (min size (* 1024 1024))))
                  chunk (.byteSize buf)
                  st   (loop [pos 0]
                         (if (>= pos size)
                           p/ok
                           (let [n (long (prov/read-into src buf 0 pos (min chunk (- size pos))))]
                             (if (<= n 0)
                               p/io-error
                               (let [w (long (prov/write-from dst buf 0 pos n))]
                                 (if (neg? w) w (recur (+ pos n))))))))]
              (prov/close! src)
              (prov/close! dst)
              (if (zero? (long st)) (prov/delete! from-prov from) st))))))))

(defn- op-rename
  "RENAME: root u32, from_len u32, from, to. RENAME_X (`cross?`): from_root
  u32, to_root u32, from_len u32, from, to: a file moves between roots by copy;
  a directory can't (ST_CROSS_ROOT)."
  ^long [^Ring r ^long off ^long len cross?]
  (let [seg  (.-seg r)
        head (if cross? 12 8)]
    (if (< len head)
      (reply p/bad-request 0)
      (let [from-root (u32 seg off)
            to-root   (if cross? (u32 seg (+ off 4)) from-root)
            from-len  (u32 seg (+ off (- head 4)))
            from      (when (<= (+ head from-len) len) (vpath (utf8-at seg (+ off head) from-len) false))
            to        (when from (vpath (utf8-at seg (+ off head from-len) (- len head from-len)) false))
            fp        (provider-of r from-root)
            tp        (provider-of r to-root)]
        (trace! r "rename" (str from-root "->" to-root) (str from " -> " to))
        (cond
          (or (nil? from) (nil? to)) (reply p/bad-request 0)
          (or (nil? fp) (nil? tp))   (reply p/not-found 0)
          (= from-root to-root)      (status-reply (prov/rename! fp from to))
          :else                      (status-reply (copy-across! fp from tp to)))))))

;; --- names, staging ---------------------------------------------------------------

(defn- op-stored-names
  "root u32, skip u32 | path -> the provider's spelling of the path's
  components from `skip` on, joined by `/` (for GetFinalPathNameByHandle)."
  ^long [^Ring r ^long off ^long len]
  (let [seg  (.-seg r)
        prov (when (>= len 8) (provider-of r (u32 seg off)))
        vp   (when prov (vpath (utf8-at seg (+ off 8) (- len 8)) true))
        spelled (when vp (prov/spelling prov vp))]
    (if (nil? spelled)
      (reply p/not-found 0)
      (let [parts (if (= "" spelled) [] (vec (.split ^String spelled "/")))]
        (reply-utf8 r off (String/join "/" ^Iterable (drop (u32 seg (+ off 4)) parts)))))))

(defn- op-stage-exec
  "root u32 | path -> a real path the shim can start a VFS-only EXE from."
  ^long [^Ring r ^long off ^long len]
  (let [f (.-stage-exec r)
        [_ vp] (path-op r off len)]
    (cond
      (nil? f)  (reply p/not-supported 0)
      (nil? vp) (reply p/not-found 0)
      :else     (if-let [real (f vp)]
                  (reply-utf8 r off real)
                  (reply p/not-found 0)))))

;; --- the registry --------------------------------------------------------------

(defn- op-registry
  "Registry ops 15-22, answered from the attached registry (NOT_SUPPORTED
  without one). A request that doesn't decode is BAD_REQUEST; a REG_KEY reply
  larger than an inline reply can carry (cap - 8) is REPLY_TOO_LARGE. A write
  has published reg_gen before it returns, so its reply can't reach the
  writer while another process may still use a cached answer."
  ^long [^Ring r ^long off ^long len ^long op]
  (if-let [reg (.-registry r)]
    (let [b   (byte-array len)
          _   (MemorySegment/copy ^MemorySegment (.-seg r) ValueLayout/JAVA_BYTE off b (int 0) (int len))
          req (try (registry/decode-request op b) (catch Exception _ nil))]
      (if-not req
        (reply p/bad-request 0)
        (let [[st ^bytes out] (registry/serve reg req)]
          (cond
            (not (zero? (long st)))                   (reply st 0)
            (> (alength out) (- (.-cap r) 8))         (reply p/reply-too-large 0)
            :else                                     (reply-bytes r off out)))))
    (reply p/not-supported 0)))

(defn- dispatch ^long [r ^long slot]
  (let [^Ring r r
        seg  (.-seg r)
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
        6  (op-write r off len)
        7  (op-setattr r off len)
        8  (op-rename r off len false)
        9  (op-delete r off len)
        10 (op-mkdir r off len)
        11 (op-close r off len)
        14 (op-stored-names r off len)
        23 (op-rename r off len true)
        24 (op-stage-exec r off len)
        (15 16 17 18 19 20 21 22) (op-registry r off len op)
        (reply p/bad-request 0)))))

(defn- serve-slot
  "Serves the request in `slot` (taken: PROCESSING) and completes it. The
  reply carries `ack`, the low half of the request's id, which the client
  checks. A client that gave up meanwhile has marked the slot ABANDONED: its
  reply is dropped and the slot freed."
  [^Ring r ^long slot]
  (let [seg    (.-seg r)
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
  (let [seg (.-seg r) stride (.-stride r)]
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
;; copy of the ring, over paths from the real root 0. Each reply is checked,
;; so the warm-up is also a self-test of the content.

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
  "Runs `rounds` rounds of synthetic requests (9 per round; 3 when root 0 has
  no files) through the server's code on a private ring over `provider`.
  Returns {:requests :ms}; throws if a reply is wrong. Read-only: nothing is
  written to the provider."
  [provider {:keys [rounds] :or {rounds 3000}}]
  (with-open [arena (Arena/ofConfined)]
    (let [g      (p/geometry)
          seg    (.allocate arena (long (:map-bytes g)) 64)
          stride (long (:stride g))
          _      (init-header! seg g 1)
          r      (Ring. seg stride (:cap g) (:arena-offset g) (:bank-size g)
                        (roots-array {0 provider}) (ConcurrentHashMap.) (AtomicLong.)
                        0 0 0 (AtomicBoolean.) (AtomicLong.) nil nil nil)
          {:keys [files dirs]} (prov/sample provider 64)
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
  and serves `provider` as root 0 on `workers` platform threads. Returns a
  server map; the shim needs :name and :map-bytes (VFS_RING_SECTION /
  VFS_RING_BYTES).

  First runs `warm-up` rounds of synthetic requests (see warm-up!; 0 skips
  it), so the handlers are compiled before the first real request.
  `stage-exec` answers STAGE_EXEC: (fn [vpath] real-path-or-nil). `registry`
  (aether.director.registry/registry) serves the registry ops and publishes
  reg_gen into the header; without one they're NOT_SUPPORTED. `roots`
  serves more roots: {root-id provider}, ids below 16. `trace` counts requests
  (see trace-top)."
  [provider {:keys [name workers spin-us warm-up stage-exec trace roots registry]
             :or {workers 16 spin-us 400 warm-up 3000}}]
  (let [warmed  (when (pos? (long warm-up)) (warm-up! provider {:rounds warm-up}))
        g       (p/geometry)
        section (Native/createSection name (:map-bytes g))
        seg     (.view section)
        _       (init-header! seg g workers)
        srv     (Native/createEvent (str name "_srv"))
        cli     (Native/createEvent (str name "_cli"))
        ring    (Ring. seg (:stride g) (:cap g) (:arena-offset g) (:bank-size g)
                       (roots-array (assoc roots 0 provider)) (ConcurrentHashMap.) (AtomicLong.)
                       srv cli (* 1000 (long spin-us))
                       (AtomicBoolean.) (AtomicLong.) stage-exec registry
                       (when trace (ConcurrentHashMap.)))
        _       (when registry
                  (registry/publish-to! registry #(Native/fetchMaxLong seg p/rh-reg-gen (long %))))
        threads (vec (for [i (range workers)]
                       (doto (Thread. ^Runnable #(worker ring) (str "ring-worker-" i))
                         (.setDaemon true)
                         (.start))))]
    (merge g {:name name :ring ring :section section :threads threads :warm-up warmed
              :registry? (some? registry)
              :server-event (str name "_srv") :client-event (str name "_cli")})))

(defn stop [{:keys [^Ring ring ^Native$Section section threads]}]
  (.set ^AtomicBoolean (.-stop ring) true)
  (doseq [^Thread t threads] (.join t 1000))
  (doseq [^Open o (.values ^ConcurrentHashMap (.-handles ring))] (prov/close! (.-handle o)))
  (Native/closeHandle (.-srv-event ring))
  (Native/closeHandle (.-cli-event ring))
  (.close section))

(defn trace-top
  "The `n` most frequent traced requests: [[\"op outcome path\" count] …]."
  [{:keys [^Ring ring]} n]
  (when-let [^ConcurrentHashMap t (.-trace ring)]
    (->> t (map (fn [[k ^LongAdder v]] [k (.sum v)])) (sort-by (comp - second)) (take n))))

(defn stats [{:keys [^Ring ring]}]
  {:served (.get ^AtomicLong (.-served ring))
   :open-handles (.size ^ConcurrentHashMap (.-handles ring))})
