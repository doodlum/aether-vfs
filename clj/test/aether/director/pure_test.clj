(ns aether.director.pure-test
  "The parts of the director that don't need Windows: the wire geometry,
  case folding, path normalization and the content index."
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.tree :as tree]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director.tree Dir File)
           (java.lang.foreign Arena MemorySegment)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)))

(deftest geometry-matches-the-rust-director
  ;; vfs-director's IpcServe: 32 slots of 1 MiB, a 32 MiB arena, rounded to 64 KiB
  ;; ring version 4: a 48-byte header
  (is (= {:slots 32 :cap 1048576 :stride 1048608 :ring-bytes 33555504 :arena-offset 33555504
          :arena-len 33554432 :bank-size 1048576 :map-bytes 67174400}
         (p/geometry))))

(deftest folding-matches-rust-char-to-lowercase
  (is (= "data/skyrim.esm" (path/fold "Data/Skyrim.ESM")))
  (let [s "already/folded"] (is (identical? s (path/fold s)) "no allocation when nothing changes"))
  (is (= "i̇stanbul" (path/fold "İstanbul")) "U+0130 lowercases to two code points")
  (is (= "σσ" (path/fold "ΣΣ")) "per code point: no final-sigma rule, unlike String/toLowerCase")
  (is (= "𐐨" (path/fold "𐐀")) "supplementary code points"))

(deftest normalizing
  (is (= "a/b" (path/normalize "a/b")))
  (is (= "a/b" (path/normalize "\\a\\.\\b\\")))
  (is (= "" (path/normalize ".")))
  (is (= "a/c" (path/normalize "a/b/../c")))
  (is (nil? (path/normalize "../x")) "escaping the root"))

(defn- seg [^String s]
  (let [b (.getBytes s StandardCharsets/UTF_8)]
    (doto (.allocate (Arena/ofAuto) (alength b)) (.copyFrom (MemorySegment/ofArray b)))))

(defn- listing [^Dir d]
  (let [b (.order (ByteBuffer/wrap (.-listing d)) ByteOrder/LITTLE_ENDIAN)]
    (vec (for [_ (range (.getInt b))]
           (let [name (let [a (byte-array (.getInt b))] (.get b a) (String. a StandardCharsets/UTF_8))
                 dir? (= 1 (.get b))
                 size (.getLong b)]
             (.getLong b)
             [name dir? size])))))

(deftest the-index
  (let [t (tree/build [["Data/Skyrim.esm" (seg "esm!")] ["Data/Meshes/a.nif" (seg "n")] ["top.txt" (seg "")]])]
    (testing "files are found by folded path"
      (is (= 4 (.-size ^File (tree/lookup t "data/skyrim.esm"))))
      (is (nil? (tree/lookup t "Data/Skyrim.esm")) "the ring always sends folded paths"))
    (testing "directories are implied, list their children in the archive's spelling"
      (is (= [["Data" true 0] ["top.txt" false 0]] (listing (tree/lookup t ""))))
      (is (= [["Skyrim.esm" false 4] ["Meshes" true 0]] (listing (tree/lookup t "data"))))
      (is (= [["a.nif" false 1]] (listing (tree/lookup t "data/meshes")))))))
