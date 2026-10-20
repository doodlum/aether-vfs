(ns aether.director.source.archive-test
  "aether-archive's tests: byte sources, seekable zstd, zip directories."
  (:require [aether.director.source.archive :as ar]
            [aether.director.source.net :as net]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director Codec)
           (java.io ByteArrayOutputStream)
           (java.lang.foreign MemorySegment)
           (java.nio ByteBuffer ByteOrder)
           (java.util Arrays)))

;; --- fixtures ---------------------------------------------------------------------------

(defn pattern "Compressible bytes." ^bytes [n] (byte-array (map #(unchecked-byte (rem (quot % 7) 251)) (range n))))

(defn lcg "Incompressible bytes." ^bytes [n]
  (let [b (byte-array n)]
    (loop [i 0 s 0x2545F4914F6CDD1D]
      (when (< i n)
        (let [s (unchecked-add (unchecked-multiply s 6364136223846793005) 1442695040888963407)]
          (aset-byte b i (unchecked-byte (unsigned-bit-shift-right s 56)))
          (recur (inc i) s))))
    b))

(defn- le-bytes [& fields]
  (let [n (reduce + (map (fn [[t]] (case t :u8 1 :u16 2 :u32 4 :u64 8 :bytes 0)) fields))
        out (ByteArrayOutputStream.)]
    (doseq [[t v] fields]
      (case t
        :bytes (.write out ^bytes v)
        (let [bb (.order (ByteBuffer/allocate (case t :u8 1 :u16 2 :u32 4 :u64 8)) ByteOrder/LITTLE_ENDIAN)]
          (case t :u8 (.put bb (unchecked-byte v)) :u16 (.putShort bb (unchecked-short v))
                :u32 (.putInt bb (unchecked-int v)) :u64 (.putLong bb (long v)))
          (.write out (.array bb)))))
    (.toByteArray out)))

(defn seekable-zstd
  "`data` as seekable zstd: frames of `frame` bytes and the table. Returns
  [stream table-bytes]."
  [^bytes data frame checksums?]
  (let [frames (for [a (range 0 (max 1 (alength data)) frame) :when (or (< a (alength data)) (zero? (alength data)))]
                 (Arrays/copyOfRange data (int a) (int (min (alength data) (+ a frame)))))
        frames (if (zero? (alength data)) [] frames)
        comp (mapv #(Codec/compress (MemorySegment/ofArray ^bytes %) 3) frames)
        entries (map (fn [^bytes c ^bytes d]
                       (apply le-bytes [:u32 (alength c)] [:u32 (alength d)]
                              (when checksums? [[:u32 (Codec/xxh64 (MemorySegment/ofArray d) 0)]])))
                     comp frames)
        body (apply le-bytes (map (fn [e] [:bytes e]) entries))
        table (le-bytes [:u32 0x184D2A5E] [:u32 (+ (alength body) 9)] [:bytes body]
                        [:u32 (count frames)] [:u8 (if checksums? 0x80 0)] [:u32 0x8F92EAB1])
        out (ByteArrayOutputStream.)]
    (doseq [^bytes c comp] (.write out c))
    (.write out table)
    [(.toByteArray out) table]))

(defn build-zip
  "A zip of entries {:name :method :data (stored bytes) :usize :extra (central only) :flags}."
  [entries & [{:keys [zip64]}]]
  (let [out (ByteArrayOutputStream.)
        cd  (ByteArrayOutputStream.)]
    (doseq [{:keys [^String name method ^bytes data usize extra flags] :or {flags 0}} entries]
      (let [nb (.getBytes name "UTF-8") off (.size out)
            z64 (when zip64 (le-bytes [:u16 1] [:u16 24] [:u64 usize] [:u64 (alength data)] [:u64 off]))
            cextra (or (some->> [z64 extra] (remove nil?) seq (apply concat) byte-array) (byte-array 0))]
        (.write out (le-bytes [:u32 0x04034b50] [:u16 20] [:u16 flags] [:u16 method] [:u32 0] [:u32 0]
                              [:u32 (if zip64 0xFFFFFFFF (alength data))] [:u32 (if zip64 0xFFFFFFFF usize)]
                              [:u16 (alength nb)] [:u16 0] [:bytes nb] [:bytes data]))
        (.write cd (le-bytes [:u32 0x02014b50] [:u16 20] [:u16 20] [:u16 flags] [:u16 method] [:u32 0] [:u32 0]
                             [:u32 (if zip64 0xFFFFFFFF (alength data))] [:u32 (if zip64 0xFFFFFFFF usize)]
                             [:u16 (alength nb)] [:u16 (alength cextra)] [:u16 0] [:u16 0] [:u16 0] [:u32 0]
                             [:u32 (if zip64 0xFFFFFFFF off)] [:bytes nb] [:bytes cextra]))))
    (let [cd-off (.size out) cd-bytes (.toByteArray cd) n (count entries)]
      (.write out cd-bytes)
      (if zip64
        (let [z64-off (.size out)]
          (.write out (le-bytes [:u32 0x06064b50] [:u64 44] [:u16 45] [:u16 45] [:u32 0] [:u32 0]
                                [:u64 n] [:u64 n] [:u64 (alength cd-bytes)] [:u64 cd-off]))
          (.write out (le-bytes [:u32 0x07064b50] [:u32 0] [:u64 z64-off] [:u32 1]))
          (.write out (le-bytes [:u32 0x06054b50] [:u16 0] [:u16 0] [:u16 0xFFFF] [:u16 0xFFFF]
                                [:u32 0xFFFFFFFF] [:u32 0xFFFFFFFF] [:u16 0])))
        (.write out (le-bytes [:u32 0x06054b50] [:u16 0] [:u16 0] [:u16 n] [:u16 n]
                              [:u32 (alength cd-bytes)] [:u32 cd-off] [:u16 0])))
      (.toByteArray out))))

(defn stored [name ^bytes data] {:name name :method 0 :data data :usize (alength data)})
(defn seekable [name ^bytes data frame]
  (let [[s] (seekable-zstd data frame true)] {:name name :method 93 :data s :usize (alength data)}))
(defn nexus [name ^bytes data frame]
  (let [[s t] (seekable-zstd data frame true)]
    {:name name :method 93 :data s :usize (alength data)
     :extra (le-bytes [:u16 0x4E58] [:u16 (alength ^bytes t)] [:bytes t])}))

(defmacro ^:private kind-of [form] `(try ~form nil (catch clojure.lang.ExceptionInfo e# (net/kind e#))))

;; --- byte sources ---------------------------------------------------------------------------

(deftest range-reads
  (let [b (pattern 100)]
    (is (Arrays/equals (Arrays/copyOfRange b 10 20) (ar/read-bytes b 10 10)))
    (is (thrown? java.io.EOFException (ar/read-bytes b 95 10)))
    (is (thrown? java.io.EOFException (ar/read-bytes b 0 Long/MAX_VALUE)) "bounds checked before allocating")
    (let [s (ar/sub-range b 10 50)]
      (is (= 40 (ar/source-len s)))
      (is (Arrays/equals (Arrays/copyOfRange b 15 20) (ar/read-bytes s 5 5)))
      (is (thrown? java.io.EOFException (ar/read-bytes s 35 10))))
    (let [f (java.io.File/createTempFile "archive" ".bin")]
      (java.nio.file.Files/write (.toPath f) b (make-array java.nio.file.OpenOption 0))
      (with-open [r (ar/file-range f)]
        (is (= 100 (ar/source-len r)))
        (is (Arrays/equals (Arrays/copyOfRange b 90 100) (ar/read-bytes r 90 10)))))))

;; --- seekable zstd --------------------------------------------------------------------------------

(deftest seekable-tables
  (let [data (pattern 10000)]
    (doseq [checks? [true false]]
      (testing (str "checksums " checks?)
        (let [[s] (seekable-zstd data 1000 checks?)
              t (ar/read-seek-table s)]
          (is (= 10 (ar/frame-count t)))
          (is (= 10000 (:d-len t)))
          (is (= [1 2 3] (ar/frames-for-range t 1500 3500)))
          (is (= [] (ar/frames-for-range t 5 5)))
          (doseq [[a b] [[0 10000] [0 1] [999 1001] [1234 8765] [9999 10000]]]
            (is (Arrays/equals (Arrays/copyOfRange data (int a) (int b)) (ar/read-range s t a b)) (str a ".." b)))
          (is (= :invalid (kind-of (ar/frames-for-range t 0 10001)))))))
    (testing "an empty stream has no frames"
      (let [[s] (seekable-zstd (byte-array 0) 1000 true)]
        (is (= 0 (ar/frame-count (ar/read-seek-table s))))))
    (testing "zero-size frames are skipped"
      (let [t (#'ar/build-table [[10 1000 nil] [9 0 nil] [10 1000 nil]] false 0)]
        (is (= [0 2] (ar/frames-for-range t 999 1001)))))
    (testing "a single plain frame"
      (let [c (Codec/compress (MemorySegment/ofArray data) 3)
            t (ar/single-frame (alength c) 10000)]
        (is (Arrays/equals (Arrays/copyOfRange data 100 30000 ) (Arrays/copyOfRange (ar/read-range c t 0 10000) 100 30000)))))))

(deftest corrupt-tables-are-rejected
  (let [[s ^bytes t] (seekable-zstd (pattern 5000) 1000 true)
        broken (fn [f] (let [c (aclone ^bytes s)] (f c) c))]
    (is (= :invalid (kind-of (ar/read-seek-table (broken #(aset-byte % (dec (alength ^bytes %)) 0))))) "magic")
    (is (= :unsupported (kind-of (ar/read-seek-table (broken #(aset-byte % (- (alength ^bytes %) 5) (unchecked-byte 0x84)))))) "reserved bits")
    (is (= :invalid (kind-of (ar/read-seek-table (byte-array (concat (repeat 7 0) s))))) "junk in front")
    (is (= :invalid (kind-of (ar/read-seek-table (Arrays/copyOf ^bytes s 5)))) "truncated")
    (testing "a bad frame checksum"
      (let [bad (broken #(let [o (- (alength ^bytes %) (alength t) -8 -8)] (aset-byte % o (unchecked-byte (inc (aget ^bytes % o))))))]
        (is (= :checksum (kind-of (ar/read-range bad (ar/read-seek-table bad) 0 1000))))))
    (testing "a frame over 1 GiB"
      (is (= :unsupported (kind-of (#'ar/build-table [[10 (inc (* 1024 1024 1024)) nil]] false 0)))))))

;; --- zip --------------------------------------------------------------------------------------------

(deftest zip-directories
  (let [small (pattern 3000) big (lcg 20000) nx (pattern 50000)
        z (build-zip [(stored "Readme.TXT" small)
                      (seekable "Data\\Big.bin" big 4096)
                      (nexus "data/nexus.bsa" nx 8192)
                      (stored "dir/" (byte-array 0))])]
    (testing "lists and finds entries, ignoring case and separators"
      (let [idx (ar/read-index z)]
        (is (= ["Readme.TXT" "Data\\Big.bin" "data/nexus.bsa" "dir/"] (map :name (:entries idx))))
        (is (= "Data\\Big.bin" (:name (ar/find-entry idx "data/big.BIN"))))
        (is (ar/dir? (ar/find-entry idx "DIR/")))
        (is (nil? (ar/find-entry idx "nope")))))
    (testing "reads stored and zstd entries"
      (let [idx (ar/read-index z)]
        (is (Arrays/equals small (ar/read-entry z (ar/find-entry idx "readme.txt"))))
        (is (Arrays/equals big (ar/read-entry z (ar/find-entry idx "data/big.bin"))))
        (is (Arrays/equals nx (ar/read-entry z (ar/find-entry idx "data/nexus.bsa"))))))
    (testing "the Nexus extra field carries the seek table; small entries are one plain frame"
      (let [idx (ar/read-index z)
            e (ar/find-entry idx "data/nexus.bsa")
            t (ar/frame-map e)
            [s end] (ar/entry-data-range z e)]
        (is (some? (:seek-table e)))
        (is (= 50000 (:d-len t)))
        (is (Arrays/equals (Arrays/copyOfRange nx 12345 23456)
                           (ar/read-range (ar/sub-range z s end) t 12345 23456)))
        (is (= 0 (:table-len (ar/frame-map (ar/find-entry idx "data/big.bin")))) "4 MiB or less, no extra: one plain frame")
        (is (nil? (ar/frame-map (ar/->ZipEntry "huge" 93 0 0 1 (inc ar/plain-frame-max) 0 nil 4)))
            "bigger, no extra: the table is at the end of the data")))
    (testing "ZIP64"
      (let [z64 (build-zip [(stored "a.txt" small) (seekable "b.bin" big 4096)] {:zip64 true})
            idx (ar/read-index z64)]
        (is (= 2 (count (:entries idx))))
        (is (Arrays/equals big (ar/read-entry z64 (ar/find-entry idx "b.bin"))))))))

(deftest zip-names-and-refusals
  (testing "CP437 and UTF-8 names"
    (let [cp (build-zip [(assoc (stored "x" (byte-array 1)) :name "x")])
          ;; a name with CP437's 0x81 (ü), not valid UTF-8
          raw (let [b (aclone ^bytes cp) i (.indexOf (String. b "ISO-8859-1") "x" 30)] b)
          idx (ar/read-index (build-zip [{:name "Über.txt" :method 0 :data (byte-array 1) :usize 1 :flags 0x800}]))]
      (is (= "Über.txt" (:name (first (:entries idx)))))
      (is (= "ü" (#'ar/decode-name (byte-array [(unchecked-byte 0x81)]) 0)))))
  (testing "garbage and truncation"
    (is (= :invalid (kind-of (ar/read-index (pattern 100)))))
    (is (= :invalid (kind-of (ar/read-index (Arrays/copyOf ^bytes (build-zip [(stored "a" (pattern 10))]) 40))))))
  (testing "encryption and unknown methods"
    (let [z (build-zip [{:name "e" :method 0 :data (pattern 10) :usize 10 :flags 1}
                        {:name "w" :method 14 :data (pattern 10) :usize 10}])
          idx (ar/read-index z)]
      (is (= :unsupported (kind-of (ar/read-entry z (ar/find-entry idx "e")))))
      (is (= :unsupported (kind-of (ar/read-entry z (ar/find-entry idx "w")))))))
  (testing "a corrupt Nexus extra field"
    (let [[s ^bytes t] (seekable-zstd (pattern 9000) 4096 true)
          e {:name "n" :method 93 :data s :usize 9001
             :extra (le-bytes [:u16 0x4E58] [:u16 (alength t)] [:bytes t])}]
      (is (= :invalid (kind-of (ar/read-index (build-zip [e]))))))))
