(ns aether.director.fixture
  "The ring benchmark's fixture: the same tree as
  rust/crates/vfs-ring-harness/src/fixture.rs, which vfs-ring-bench checks."
  (:import (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio.charset StandardCharsets)))

(def ^:const data-len (* 64 1024 1024))

(defn- bytes-of ^MemorySegment [^bytes b]
  (doto (.allocate (Arena/global) (alength b))
    (.copyFrom (MemorySegment/ofArray b))))

(defn- pattern-segment
  "data.bin: byte i is i mod 251."
  ^MemorySegment [^long n]
  (let [period (byte-array 251)
        _      (dotimes [i 251] (aset period i (unchecked-byte i)))
        seg    (.allocate (Arena/global) n)]
    (loop [off 0]
      (when (< off n)
        (let [k (min 251 (- n off))]
          (MemorySegment/copy period (int 0) seg ValueLayout/JAVA_BYTE off (int k))
          (recur (+ off k)))))
    seg))

(defn files
  "[path data] pairs."
  []
  [["hello.txt" (bytes-of (.getBytes "hello" StandardCharsets/US_ASCII))]
   ["big.bin" (bytes-of (byte-array 70000 (byte (int \X))))]
   ["data.bin" (pattern-segment data-len)]
   ["dir/a.txt" (bytes-of (.getBytes "a" StandardCharsets/US_ASCII))]
   ["dir/sub/b.txt" (bytes-of (.getBytes "bb" StandardCharsets/US_ASCII))]])
