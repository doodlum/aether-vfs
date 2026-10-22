(ns aether.director.ring.backing
  "Where the ring's shared memory comes from, and how its workers wake: a
  backing per transport.

    :section  a named page-file-backed section (Windows), with the two named
              events the shim signals and waits on: EventWaker
    :file     a real file both sides map by path (Linux, where the shim runs
              under Wine and the director natively; also Windows, where the
              shim supports VFS_RING_PATH too): ParkWaker, since no event
              could wake across that boundary

  A backing is {:seg MemorySegment :waker Waker :warm-waker Waker :close
  (fn []) :server {…}}: :warm-waker is of :waker's class but wakes nothing
  (for ring/warm-up!), and :server is what the server map carries for the
  shim's env (aether.director.env/transport-env) and fuse.cfg."
  (:import (aether.director EventWaker Native ParkWaker)
           (java.io File IOException RandomAccessFile)
           (java.lang.foreign Arena MemorySegment)
           (java.nio.channels FileChannel$MapMode)
           (java.nio.file Files)))

(set! *warn-on-reflection* true)

(defmulti open-backing
  "Opens a backing of `geometry`'s :map-bytes for `transport`."
  (fn [transport _geometry _opts] transport))

(defmethod open-backing :section
  [_ {:keys [map-bytes]} {:keys [name spin-us] :or {spin-us 400}}]
  (let [section (Native/createSection name map-bytes)
        [srv cli] (try
                    (let [srv (Native/createEvent (str name "_srv"))]
                      (try [srv (Native/createEvent (str name "_cli"))]
                           (catch Throwable t (Native/closeHandle srv) (throw t))))
                    (catch Throwable t (.close section) (throw t)))
        spin-ns (* 1000 (long spin-us))]
    {:seg    (.view section)
     :waker  (EventWaker. srv cli spin-ns)
     ;; SetEvent(NULL) fails at once, as the warm-up's did before wakers
     :warm-waker (EventWaker. 0 0 spin-ns)
     :close  #(.close section)
     :server {:transport :section :name name :section section
              :server-event (str name "_srv") :client-event (str name "_cli")}}))

(defn map-file
  "Maps `f` read/write and shared, `size` bytes, creating it if need be.
  Grow-only: a longer file is never truncated, since shortening a file
  another process has mapped faults that process (SIGBUS on Linux), as
  vfs-unix's FileMapping::create (rust/crates/vfs-unix/src/mapping.rs:30-64).
  The file is grown before it is mapped, by setLength (set_len there): a
  mapping past the end of a file faults on first touch. Returns {:seg :close}."
  [^File f ^long size]
  (let [arena (Arena/ofShared)]
    (try
      (with-open [raf (RandomAccessFile. f "rw")]
        (when (< (.length raf) size) (.setLength raf size))
        ;; the mapping holds the file; the channel can close
        {:seg   (.map (.getChannel raf) FileChannel$MapMode/READ_WRITE 0 size arena)
         :close #(.close arena)})
      (catch Throwable t (.close arena) (throw t)))))

(defmethod open-backing :file
  [_ {:keys [map-bytes]} {:keys [path shim-path spin-us park-us] :or {spin-us 400 park-us [100 500 2000]}}]
  (when-not path (throw (ex-info "a :file ring needs :path" {})))
  (let [f    (.getCanonicalFile (File. (str path)))
        _    (.mkdirs (.getParentFile f))
        ;; Unlinked rather than reused: a ring left by an earlier run would be
        ;; re-initialised under anything still mapping it; a fresh inode leaves
        ;; such a reader on the old one (vfs-embed/src/session/proton/mod.rs:320-327).
        ;; Best effort, as there: Windows refuses to delete a file still mapped.
        _    (try (Files/deleteIfExists (.toPath f)) (catch IOException _))
        {:keys [seg close]} (map-file f map-bytes)]
    (try
      (let [[floor mid cap] park-us
            waker #(ParkWaker. (* 1000 (long spin-us)) (* 1000 (long floor)) (* 1000 (long mid)) (* 1000 (long cap)))]
        {:seg    seg
         :waker  (waker)
         :warm-waker (waker)
         :close  close
         :server {:transport :file :ring-path (or shim-path (.getPath f)) :host-path (.getPath f)}})
      (catch Throwable t (close) (throw t)))))
