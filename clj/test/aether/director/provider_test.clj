(ns aether.director.provider-test
  "The provider conformance suite (after vfs-provider's conformance.rs), run
  against every provider; then the overlay's own behaviour."
  (:require [aether.director.provider.compose :as compose]
            [aether.director.provider.disk :as disk]
            [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [aether.director.provider.tree :as tree]
            [aether.director.storage :as storage]
            [aether.director.test-support :as ts]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director.provider Entry Stat)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.nio.file Files)))

;; --- helpers -----------------------------------------------------------------------

(defn- temp-dir ^java.io.File []
  (.toFile (Files/createTempDirectory "aether-prov" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- seg ^MemorySegment [^String s] (MemorySegment/ofArray (.getBytes s "UTF-8")))

(def fixture {"a.txt" "hello" "sub/b.txt" "world!"})

(defn- index-of [files] (tree/build (for [[k v] files] [k (seg v)])))

(defn- layer-of
  "A storage layer holding `files` (its storage stays open for the test run)."
  [files]
  (let [s (storage/open (temp-dir) {:block-size 4096 :threads 2})]
    (storage/put-files! s "test" (for [[k v] files] [k (.getBytes ^String v "UTF-8")]))
    (storage/layer s "test")))

(defn- disk-of [files]
  (let [d (temp-dir)]
    (doseq [[k v] files] (io/make-parents (io/file d k)) (spit (io/file d k) v))
    (disk/disk d)))

(defn- read-all
  "The file at `vp`, read through a handle in `chunk`-byte reads."
  ([prov vp] (read-all prov vp 3))
  ([prov vp chunk]
   (let [h (prov/open prov vp p/open-read)]
     (when-not (number? h)
       (try
         (let [buf (MemorySegment/ofArray (byte-array 4096))]
           (loop [pos 0 out (java.io.ByteArrayOutputStream.)]
             (let [n (long (prov/read-into h buf 0 pos chunk))]
               (if (pos? n)
                 (do (.write out ^bytes (.toArray (.asSlice buf 0 n) ValueLayout/JAVA_BYTE)) (recur (+ pos n) out))
                 (String. (.toByteArray out) "UTF-8")))))
         (finally (prov/close! h)))))))

(defn- write! [prov vp flags & chunks]
  (let [h (prov/open prov vp flags)]
    (if (number? h)
      h
      (try
        (doseq [[pos ^String s] chunks]
          (let [b (seg s)] (prov/write-from h b 0 pos (.byteSize b))))
        p/ok
        (finally (prov/close! h))))))

(defn- names [prov vp]
  (let [l (prov/list-dir prov vp)]
    (when (sequential? l)
      (vec (sort (for [^Entry e l] [(.-name e) (.-dir? ^Stat (.-stat e)) (.-size ^Stat (.-stat e))]))))))

(defn- size [prov vp] (some-> ^Stat (prov/stat prov vp) .-size))
(defn- dir? [prov vp] (some-> ^Stat (prov/stat prov vp) .-dir?))

(def wcreate (bit-or p/open-write p/open-create))

;; --- the conformance suite ----------------------------------------------------------

(defn- case-insensitive-disk
  "nil where the disk provider folds case, else why its case assertions skip:
  it relies on the file system for that (disk.clj), and only Windows' is
  case-insensitive. Folding on a case-sensitive one is a Linux work item
  (docs/linux-port-research.md §6)."
  []
  (when (ts/windows)
    {:skip/kind :os :why "a case-insensitive file system (the disk provider folds through it)"}))

(defn conformance
  "Checks `prov`, which serves `fixture`. `writable?` adds the write cases.
  `disk` is where a real directory takes part: :reads (the fixture is on disk)
  or :writes (writes land on disk); its case-folding assertions need a
  case-insensitive file system."
  ([label prov writable?] (conformance label prov writable? nil))
  ([label prov writable? disk]
   (testing label
     (testing "common"
       (is (dir? prov ""))
       (is (= 5 (size prov "a.txt")))
       (is (dir? prov "sub"))
       (is (= 6 (size prov "sub/b.txt")))
       (is (nil? (prov/stat prov "missing.txt")))
       (is (= p/not-found (prov/open prov "missing.txt" p/open-read)))
       (is (= [["a.txt" false 5] ["sub" true 0]] (names prov "")))
       (is (= [["b.txt" false 6]] (names prov "sub")))
       (is (= p/not-a-directory (prov/list-dir prov "a.txt")))
       (is (nil? (prov/list-dir prov "nope")))
       (let [h1 (prov/open prov "a.txt" p/open-read) h2 (prov/open prov "a.txt" p/open-read)]
         (is (not (identical? h1 h2)) "two opens are two handles")
         (prov/close! h1) (prov/close! h2)))
     (testing "case"
       (ts/requires [(when (= :reads disk) (case-insensitive-disk))]
         (is (= "hello" (read-all prov "A.TXT")) "open folds the caller's spelling"))
       (is (= "a.txt" (prov/spelling prov "a.txt")))
       (is (= "sub/b.txt" (prov/spelling prov "sub/b.txt")))
       (is (= "" (prov/spelling prov "")))
       (is (nil? (prov/spelling prov "missing.txt"))))
     (testing "positional reads"
       (is (= "hello" (read-all prov "a.txt" 3)))
       (is (= "world!" (read-all prov "sub/b.txt" 4)))
       (let [h (prov/open prov "a.txt" p/open-read)
             buf (MemorySegment/ofArray (byte-array 16))]
         (is (= 0 (prov/read-into h buf 0 5 4)) "at EOF")
         (is (= 0 (prov/read-into h buf 0 50 4)) "past EOF")
         (is (= 0 (prov/read-into h buf 0 0 0)) "an empty read")
         (is (= 4 (prov/read-into h buf 0 1 10)))
         (is (= "ello" (String. ^bytes (.toArray (.asSlice buf 0 4) ValueLayout/JAVA_BYTE) "UTF-8")))
         (prov/close! h)))
     (if-not writable?
       (testing "read-only"
         (is (= p/read-only (prov/open prov "a.txt" p/open-write)))
         (is (= p/read-only (prov/open prov "new.txt" wcreate)))
         (is (= p/read-only (prov/delete! prov "a.txt")))
         (is (= "hello" (read-all prov "a.txt"))))
       (testing "writable"
         (is (= p/ok (write! prov "w_new.txt" wcreate [0 "hello"])))
         (is (= 5 (size prov "w_new.txt")))
         (is (= "hello" (read-all prov "w_new.txt")))
         (is (neg? (long (let [h (prov/open prov "w_new.txt" (bit-or wcreate p/open-excl))]
                           (if (number? h) h (do (prov/close! h) 0)))))
             "EXCL on an existing file fails")
         (is (= p/ok (write! prov "w_new.txt" (bit-or p/open-write p/open-trunc))))
         (is (= 0 (size prov "w_new.txt")) "TRUNC")
         (is (= p/ok (write! prov "w_new.txt" p/open-write [0 "abcdef"] [2 "XY"])))
         (is (= "abXYef" (read-all prov "w_new.txt")) "positional writes")
         (let [h (prov/open prov "w_new.txt" p/open-write)]
           (prov/truncate! h 2) (prov/truncate! h 4) (prov/close! h))
         (is (= "ab\u0000\u0000" (read-all prov "w_new.txt")) "truncate, then extend with zeros")
         (is (= p/ok (prov/mkdir! prov "w_dir")))
         (is (dir? prov "w_dir"))
         (is (= p/ok (prov/mkdir! prov "w_dir")) "MKDIR of an existing directory is OK")
         (is (= p/ok (prov/rename! prov "w_new.txt" "w_moved.txt")))
         (is (nil? (prov/stat prov "w_new.txt")))
         (is (= 4 (size prov "w_moved.txt")))
         (is (= p/ok (prov/delete! prov "w_moved.txt")))
         (is (= p/ok (prov/delete! prov "w_dir")))
         (is (nil? (prov/stat prov "w_moved.txt")))
         (is (nil? (prov/stat prov "w_dir")))
         (testing "a non-empty directory: delete fails and keeps it, or succeeds and removes it all"
           (write! prov "w_full/child.txt" wcreate [0 "x"])
           (let [st (prov/delete! prov "w_full")]
             (if (zero? (long st))
               (is (nil? (prov/stat prov "w_full/child.txt")))
               (is (= "x" (read-all prov "w_full/child.txt"))))))
         (testing "names are created as spelled and found ignoring case"
           (is (= p/ok (write! prov "Über.txt" wcreate [0 "x"])))
           (ts/requires [(when disk (case-insensitive-disk))]
             (is (= 1 (size prov "über.txt")))
             (is (= "Über.txt" (prov/spelling prov "über.txt")))
             (is (= p/ok (write! prov "A.TXT" p/open-write [0 "J"])))
             (is (= "Jello" (read-all prov "a.txt")) "writing through another spelling writes the same file"))
           (write! prov "a.txt" (bit-or p/open-write p/open-trunc) [0 "hello"])
           (is (= p/ok (write! prov "SUB/c.txt" wcreate [0 "c"])))
           (ts/requires [(when disk (case-insensitive-disk))]
             (is (some #{"c.txt"} (map first (names prov "sub"))))))
         (testing "creating makes missing parents"
           (is (= p/ok (write! prov "deep/er/d.txt" wcreate [0 "d"])))
           (is (dir? prov "deep/er")))
         (is (= "hello" (read-all prov "a.txt")) "the fixture is intact")
         (is (= "world!" (read-all prov "sub/b.txt"))))))))

(deftest every-provider-conforms
  (conformance "index" (index-of fixture) false)
  (conformance "layered" (compose/layered [(index-of {"a.txt" "stale!!" "sub/b.txt" "world!"})
                                           (index-of {"a.txt" "hello"})])
               false)
  (conformance "disk" (disk-of fixture) true :reads)
  (conformance "layer" (layer-of fixture) true)
  (conformance "overlay: layer over index" (compose/overlay (layer-of {}) (index-of fixture)) true)
  (conformance "overlay: disk over index" (compose/overlay (disk/disk (temp-dir)) (index-of fixture)) true :writes)
  (conformance "overlay: disk over layered" (compose/overlay (disk/disk (temp-dir))
                                                             (compose/layered [(index-of {"a.txt" "hello"})
                                                                               (index-of {"sub/b.txt" "world!"})]))
               true :writes))

;; --- the overlay ---------------------------------------------------------------------

(defn- copy-on-write
  "The overlay's behaviour over a disk upper, the lower spelling its files
  `esm` and `nif` (data/skyrim.esm and data/meshes/a.nif, in any case); the
  caller always spells them folded."
  [esm nif]
  (let [upper-dir (temp-dir)
        lower     (index-of {esm "esm" nif "nif" "readme.txt" "r"})
        o         (compose/overlay (disk/disk upper-dir) lower)]
    (testing "a write copies the lower file up; the lower is untouched"
      (is (= p/ok (write! o "data/skyrim.esm" p/open-write [0 "ESM"])))
      (is (= "ESM" (read-all o "data/skyrim.esm")))
      (is (= "esm" (read-all lower "data/skyrim.esm")))
      (is (.isFile (io/file upper-dir esm)) "the copy keeps the lower's spelling"))
    (testing "a lower directory can't be opened for write"
      (is (= p/is-dir (prov/open o "data/meshes" p/open-write))))
    (testing "delete hides a lower file with a whiteout, and listings never show markers"
      (is (= p/ok (prov/delete! o "readme.txt")))
      (is (nil? (prov/stat o "readme.txt")))
      (is (= p/not-found (prov/open o "readme.txt" p/open-read)))
      (is (.isFile (io/file upper-dir ".wh.readme.txt")))
      (is (not-any? #(.startsWith ^String (first %) ".") (names o ""))))
    (testing "re-creating a whited-out name needs CREATE"
      (is (= p/not-found (prov/open o "readme.txt" p/open-write)))
      (is (= p/ok (write! o "readme.txt" wcreate [0 "new"])))
      (is (= "new" (read-all o "readme.txt"))))
    (testing "deleting a lower directory hides its subtree; MKDIR brings the lower's children back"
      (is (= p/ok (prov/delete! o "data/meshes")))
      (is (nil? (prov/stat o "data/meshes/a.nif")))
      (is (= p/not-found (prov/open o "data/meshes/x.nif" wcreate)) "nothing is created under a whited-out folder")
      (is (= p/ok (prov/mkdir! o "data/meshes")))
      (is (= "nif" (read-all o "data/meshes/a.nif"))))
    (testing "rename moves a lower file and hides the source"
      (is (= p/ok (prov/rename! o "data/meshes/a.nif" "data/meshes/b.nif")))
      (is (nil? (prov/stat o "data/meshes/a.nif")))
      (is (= "nif" (read-all o "data/meshes/b.nif"))))
    (testing "a rename into a folder only the lower has"
      (is (= p/ok (write! o "loose.txt" wcreate [0 "l"])))
      (is (= p/ok (prov/rename! o "loose.txt" "data/meshes/loose.txt"))))
    (testing "a folder holding only whiteout markers can be removed"
      (is (= p/ok (write! o "gone/x.txt" wcreate [0 "x"])))
      (is (= p/ok (prov/delete! o "gone/x.txt")))
      (is (= p/ok (prov/delete! o "gone"))))
    (testing "a new session over the same upper sees what the last one left"
      (let [o2 (compose/overlay (disk/disk upper-dir) lower)]
        (is (= "ESM" (read-all o2 "data/skyrim.esm")))
        (is (= "new" (read-all o2 "readme.txt")))
        (is (nil? (prov/stat o2 "data/meshes/a.nif")) "whiteouts persist")
        (is (= "nif" (read-all o2 "data/meshes/b.nif")))))))

(deftest overlay-copy-on-write
  (copy-on-write "data/skyrim.esm" "data/meshes/a.nif"))

(deftest overlay-copy-on-write-folds-the-lower-s-case
  ;; the lower spells them in mixed case and the caller folded: the copy-up
  ;; lands in the lower's spelling, and the disk upper must find it by the
  ;; folded one
  (ts/requires [(case-insensitive-disk)]
    (copy-on-write "Data/Skyrim.esm" "Data/Meshes/a.nif")))
