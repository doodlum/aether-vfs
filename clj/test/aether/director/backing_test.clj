(ns aether.director.backing-test
  "The ring's backings: a file mapped shared (vfs-unix/src/mapping.rs's tests,
  ported), and a ring served over one, driven through a second, independent
  mapping of the file as the shim maps it."
  (:require [aether.director.proto :as p]
            [aether.director.provider.tree :as tree]
            [aether.director.ring :as ring]
            [aether.director.ring.backing :as backing]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director Native ParkWaker)
           (java.io File)
           (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir ^File [] (.toFile (Files/createTempDirectory "aether-backing" (make-array FileAttribute 0))))

(defn- open-existing
  "A second mapping of `f`, as the shim's open_file_backed makes one: the
  file must already be `size` bytes."
  [^File f ^long size ^Arena arena]
  (with-open [ch (FileChannel/open (.toPath f) (into-array OpenOption [StandardOpenOption/READ StandardOpenOption/WRITE]))]
    (when (< (.size ch) size) (throw (ex-info "too short" {})))
    (.map ch FileChannel$MapMode/READ_WRITE 0 size arena)))

(deftest map-file-sizes-the-file-so-a-mapping-cannot-fault-on-touch
  (let [f (io/file (temp-dir) "sized.bin")
        {:keys [^MemorySegment seg close]} (backing/map-file f (* 64 1024))]
    (is (= (* 64 1024) (.byteSize seg)))
    (is (= (* 64 1024) (.length f)))
    (close)))

(deftest map-file-never-shrinks-an-existing-file
  (let [f   (io/file (temp-dir) "noshrink.bin")
        big (backing/map-file f (* 128 1024))
        small (backing/map-file f (* 64 1024))]
    (is (= (* 128 1024) (.length f)) "grow-only: shrinking would fault the live mapping")
    (let [^MemorySegment s (:seg big)]
      (.set s ValueLayout/JAVA_BYTE (long (* 100 1024)) (unchecked-byte 0xCD))
      (is (= (unchecked-byte 0xCD) (.get s ValueLayout/JAVA_BYTE (long (* 100 1024))))))
    ((:close small)) ((:close big))))

(deftest writes-through-one-mapping-are-visible-through-another-and-atomics-work
  (let [f (io/file (temp-dir) "coherent.bin")
        {:keys [^MemorySegment seg close]} (backing/map-file f (* 64 1024))]
    (with-open [arena (Arena/ofConfined)]
      (let [^MemorySegment other (open-existing f (* 64 1024) arena)]
        (.set seg ValueLayout/JAVA_BYTE 4096 (unchecked-byte 0xAB))
        (is (= (unchecked-byte 0xAB) (.get other ValueLayout/JAVA_BYTE 4096)))
        (testing "Native's atomics on the file segment"
          (Native/setRelease seg 64 7)
          (is (= 7 (Native/getAcquire other 64)))
          (is (Native/cas other 64 7 9))
          (is (not (Native/cas seg 64 7 11)))
          (is (= 9 (Native/getAcquire seg 64)))
          (Native/fetchMaxLong seg 128 5)
          (Native/fetchMaxLong other 128 3)
          (is (= 5 (.get seg ValueLayout/JAVA_LONG 128))))))
    (close)))

(deftest the-file-backing-unlinks-a-stale-ring-first
  (let [dir   (temp-dir)
        f     (io/file dir "state" "ring.bin")
        g     (p/geometry)
        _     (io/make-parents f)
        _     (with-open [ch (FileChannel/open (.toPath f) (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
                (.truncate ch 0)
                (.write ch (java.nio.ByteBuffer/wrap (byte-array 16 (byte 0x5A))) (+ 4096 (long (:map-bytes g)))))
        b     (backing/open-backing :file g {:path f})]
    (try
      (is (= (:map-bytes g) (.length f)) "a fresh file, not the longer stale one grown into")
      (is (instance? ParkWaker (:waker b)))
      (is (= {:transport :file :ring-path (.getCanonicalPath f) :host-path (.getCanonicalPath f)}
             (:server b)))
      (finally ((:close b))))
    (testing ":shim-path is the name the shim is told"
      (let [b (backing/open-backing :file g {:path f :shim-path "C:\\vfs-session\\state\\ring.bin"})]
        (is (= "C:\\vfs-session\\state\\ring.bin" (get-in b [:server :ring-path])))
        ((:close b))))))

(defn- call
  "Submits one request to slot `slot` through `seg`, as the shim would, and
  waits for it: [status reply-bytes nanos]."
  [^MemorySegment seg stride slot op ^bytes payload]
  (let [base (+ p/ring-header-size (* (long slot) (long stride)))
        t0   (System/nanoTime)]
    (#'ring/request! seg stride slot op 0 payload)
    (loop []
      (when-not (= p/st-completed (Native/getAcquire seg (+ base p/sh-state)))
        (Thread/onSpinWait) (recur)))
    (let [dt  (- (System/nanoTime) t0)
          st  (.get seg ValueLayout/JAVA_INT_UNALIGNED (long (+ base p/sh-status)))
          len (.get seg ValueLayout/JAVA_INT_UNALIGNED (long (+ base p/sh-payload-len)))
          out (byte-array len)]
      (MemorySegment/copy seg ValueLayout/JAVA_BYTE (long (+ base p/slot-header-size)) out 0 len)
      (Native/setRelease seg (+ base p/sh-state) p/st-free)
      [st out dt])))

(deftest a-ring-served-over-a-file-is-served-through-another-mapping-of-it
  (let [dir (temp-dir)
        f   (io/file dir "state" "ring.bin")
        t   (tree/build [["hello.txt" (doto (.allocate (Arena/ofAuto) 5)
                                        (.copyFrom (MemorySegment/ofArray (.getBytes "hello" StandardCharsets/US_ASCII))))]])
        srv (ring/start t {:transport :file :path f :workers 2 :warm-up 0})]
    (try
      (is (= :file (:transport srv)))
      (is (nil? (:section srv)))
      (with-open [arena (Arena/ofConfined)]
        (let [^MemorySegment seg (open-existing f (:map-bytes srv) arena)
              stride (:stride srv)]
          (is (= p/magic (.get seg ValueLayout/JAVA_INT_UNALIGNED (long p/rh-magic))))
          (is (= 2 (.get seg ValueLayout/JAVA_INT_UNALIGNED (long p/rh-worker-hint))))
          (testing "a getattr, served by a parked worker"
            (Thread/sleep 100)                          ; quiet: the workers are parking
            (let [[st ^bytes out] (call seg stride 0 p/op-getattr (#'ring/le-bytes :u32 0 :utf8 "hello.txt"))]
              (is (= 0 st))
              (is (= 1 (aget out 0)) "found")
              (is (= 5 (.getLong (.order (java.nio.ByteBuffer/wrap out 2 8) java.nio.ByteOrder/LITTLE_ENDIAN))))))
          (testing "a heartbeat on every slot"
            (doseq [s (range p/slot-count)]
              (is (= 0 (first (call seg stride s p/op-heartbeat (byte-array 0)))))))))
      (finally (ring/stop srv)))
    (testing "stopped: the mapping is released, so the file can go"
      (is (.delete f)))))
