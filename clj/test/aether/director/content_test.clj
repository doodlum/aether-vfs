(ns aether.director.content-test
  "Content sources: Stored zips and launch staging."
  (:require [aether.director.stage :as stage]
            [aether.director.provider :as prov]
            [aether.director.provider.tree :as tree]
            [aether.director.provider.zip :as zip]
            [aether.director.test-support :as ts]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director.provider Stat)
           (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio.file Files)
           (java.util.zip CRC32 ZipEntry ZipOutputStream)))

(defn- temp-dir [] (.toFile (Files/createTempDirectory "aether-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-zip! [f entries]
  (with-open [out (ZipOutputStream. (io/output-stream f))]
    (doseq [[name ^bytes data stored?] entries]
      (let [e (ZipEntry. ^String name)]
        (when stored?
          (.setMethod e ZipEntry/STORED)
          (.setSize e (alength data))
          (.setCompressedSize e (alength data))
          (.setCrc e (.getValue (doto (CRC32.) (.update data)))))
        (.putNextEntry out e)
        (.write out data)
        (.closeEntry out)))))

(defn- bytes-of [^MemorySegment s] (.toArray s ValueLayout/JAVA_BYTE))

(deftest stored-zips
  (let [dir (temp-dir) f (io/file dir "a.zip")]
    (write-zip! f [["Game/hello.txt" (.getBytes "hello") true]
                   ["Game/Data/x.bin" (byte-array (range 100)) true]])
    (with-open [arena (Arena/ofConfined)]
      (let [files (zip/open arena f)]
        (testing "a common top folder is stripped, entries are slices of the mapping"
          (is (= ["hello.txt" "Data/x.bin"] (map first files)))
          (is (= "hello" (String. ^bytes (bytes-of (second (first files))))))
          (is (= (seq (byte-array (range 100))) (seq (bytes-of (second (second files)))))))
        (testing "and serve through a tree"
          (is (= 100 (.-size ^Stat (prov/stat (tree/build files) "data/x.bin")))))))
    (write-zip! f [["a.txt" (.getBytes "aaaa") true] ["b.txt" (.getBytes "bbbbbbbbbb") false]])
    (with-open [arena (Arena/ofConfined)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"compressed entry .*b\.txt" (zip/open arena f))
          "compressed entries are refused, by name"))))

(deftest import-tables
  ;; reads java.exe, a PE, from java.home/bin
  (ts/requires [(ts/windows)]
  (let [java-exe (io/file (System/getProperty "java.home") "bin" "java.exe")
        data     (MemorySegment/ofArray (Files/readAllBytes (.toPath java-exe)))]
    (is (stage/pe? data))
    (is (some #{"jli.dll"} (map #(.toLowerCase ^String %) (stage/import-names data))))
    (is (not (stage/pe? (MemorySegment/ofArray (.getBytes "not a program")))))
    (is (stage/system-dll? "KERNEL32.dll"))
    (is (stage/system-dll? "api-ms-win-crt-heap-l1-1-0.dll"))
    (is (not (stage/system-dll? "steam_api64.dll"))))))

(deftest staging-the-import-closure
  ;; stages java.exe and jli.dll from java.home/bin
  (ts/requires [(ts/windows)]
  (let [bin  (io/file (System/getProperty "java.home") "bin")
        seg  #(MemorySegment/ofArray (Files/readAllBytes (.toPath (io/file bin %))))
        t    (tree/build [["Game/Bin/Java.exe" (seg "java.exe")] ["Game/Bin/jli.dll" (seg "jli.dll")]])
        dir  (temp-dir)
        r    (stage/stage t "Game/Bin/Java.exe" {:dir dir})]
    (is (= ["Java.exe" "jli.dll"] (:staged r)) "the EXE and the import the tree serves; system DLLs stay out")
    (is (.isFile (io/file (:exe r))))
    (is (= (.getCanonicalPath (io/file dir "Game/Bin/Java.exe")) (:exe r)) "the EXE keeps its folder")
    (stage/cleanup! r dir)
    (is (empty? (.list dir)) "cleanup removes what staging wrote, and the folders it made")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"VFS has no" (stage/stage t "nope.exe" {:dir dir}))))))
