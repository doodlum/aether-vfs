(ns aether.director.store-test
  "The block store, after aether-block-store's tests (lifecycle, read, write,
  compact, verify, crash, and a model test of random operations)."
  (:require [aether.director.store :as store]
            [aether.director.store.pack :as pack]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import (java.io RandomAccessFile)
           (java.lang.foreign MemorySegment)
           (java.nio.file Files)
           (java.util Arrays Random)
           (java.util.concurrent Executors Future)))

(def bs 4096)

(defn- tmp [] (.toFile (Files/createTempDirectory "aether-store" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- open [dir & [opts]] (store/open dir (merge {:block-size bs :threads 4} opts)))

(defmacro ^:private with-store [[s dir & [opts]] & body]
  `(let [~s (open ~dir ~opts)] (try ~@body (finally (store/close ~s)))))

(defn- id ^bytes [s] (.getBytes (str s) "UTF-8"))

(defn- noise
  "`n` incompressible bytes from `seed`."
  ^bytes [seed n]
  (let [b (byte-array n)] (.nextBytes (Random. seed) b) b))

(defn- read-all
  "[bytes missing] of a whole file."
  [s fid]
  (let [len (:len (store/stat s fid))
        dst (byte-array len)
        r   (store/read! s fid 0 (MemorySegment/ofArray dst))]
    [dst (:missing r)]))

(defn- error-of [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (store/error e))))

;; --- lifecycle --------------------------------------------------------------------------------

(deftest lifecycle
  (let [dir (tmp)]
    (with-store [s dir]
      (testing "set-len creates, stat reports, delete removes"
        (store/set-len! s (id "a") 10000)
        (is (= {:len 10000} (store/stat s (id "a"))))
        (is (store/delete! s (id "a")))
        (is (nil? (store/stat s (id "a"))))
        (is (not (store/delete! s (id "a")))))
      (testing "file ids are capped"
        (is (= :file-id-too-long (error-of #(store/set-len! s (byte-array 257) 1))))
        (store/set-len! s (byte-array 256) 1))
      (testing "a second open is locked"
        (is (= :locked (error-of #(open dir)))))
      (store/put-files! s [[(id "kept") (noise 1 10000)]]))
    (testing "metadata and data survive close and reopen"
      (with-store [s dir]
        (is (= {:len 10000} (store/stat s (id "kept"))))
        (is (Arrays/equals (noise 1 10000) ^bytes (first (read-all s (id "kept")))))
        (is (= #{"kept" (String. (byte-array 256) "UTF-8")} (set (map #(String. ^bytes % "UTF-8") (store/file-ids s)))))))
    (testing "the block size is fixed at creation"
      (is (= :config (error-of #(store/open dir {:block-size 8192})))))
    (testing "invalid config is rejected"
      (is (= :config (error-of #(store/open (tmp) {:block-size 1000})))))))

(deftest orphan-pack-files-are-deleted-on-open
  (let [dir (tmp)]
    (with-store [s dir] (store/put-files! s [[(id "f") (noise 2 5000)]]))
    (spit (pack/pack-file dir 999) "never committed")
    (with-store [s dir]
      (is (not (.exists (pack/pack-file dir 999))))
      (is (Arrays/equals (noise 2 5000) ^bytes (first (read-all s (id "f"))))))))

;; --- reads ---------------------------------------------------------------------------------------

(deftest reads
  (let [dir (tmp)]
    (with-store [s dir]
      (testing "a whole file reads back"
        (let [d (noise 3 (+ (* 5 bs) 100))]
          (store/put-files! s [[(id "w") d]])
          (is (= [true []] (let [[b m] (read-all s (id "w"))] [(Arrays/equals d ^bytes b) m])))))
      (testing "a sparse file reports its missing ranges"
        (store/set-len! s (id "sparse") (* 4 bs))
        (store/write-blocks! s (id "sparse") 1 (noise 4 bs))
        (let [[b m] (read-all s (id "sparse"))]
          (is (= [[0 bs] [(* 2 bs) (* 4 bs)]] m))
          (is (Arrays/equals (noise 4 bs) (Arrays/copyOfRange ^bytes b (int bs) (int (* 2 bs))))))
        (is (= [[bs (* 2 bs)]] (store/cached-ranges s (id "sparse")))))
      (testing "partial and clamped reads"
        (let [d   (noise 3 (+ (* 5 bs) 100))
              dst (byte-array 3000)
              r   (store/read! s (id "w") (- (* 2 bs) 1000) (MemorySegment/ofArray dst))]
          (is (= {:bytes 3000 :missing []} r))
          (is (Arrays/equals (Arrays/copyOfRange d (int (- (* 2 bs) 1000)) (int (+ (* 2 bs) 2000))) dst))
          (is (= 100 (:bytes (store/read! s (id "w") (* 5 bs) (MemorySegment/ofArray (byte-array 4096))))))
          (is (= {:bytes 0 :missing []} (store/read! s (id "w") (* 9 bs) (MemorySegment/ofArray (byte-array 10))))))
        (is (nil? (store/read! s (id "nope") 0 (MemorySegment/ofArray (byte-array 1)))))))
    (testing "data survives reopen and appending resumes in the same pack"
      (with-store [s dir]
        (let [before (map :id (:packs (store/stats s)))]
          (store/put-files! s [[(id "more") (noise 5 bs)]])
          (is (= before (map :id (:packs (store/stats s)))))
          (is (Arrays/equals (noise 5 bs) ^bytes (first (read-all s (id "more"))))))))))

(deftest reads-across-manifest-segments
  ;; 4096 slots a segment: 4100 one-byte-different blocks span two
  (with-store [s (tmp)]
    (let [n 4100
          d (byte-array (* n bs))]
      (dotimes [i n] (aset-byte d (* i bs) (unchecked-byte i)) (aset-byte d (inc (* i bs)) (unchecked-byte (bit-shift-right i 8))))
      (store/put-files! s [[(id "big") d]])
      (let [dst (byte-array (* 3 bs))]
        (store/read! s (id "big") (* 4095 bs) (MemorySegment/ofArray dst))
        (is (Arrays/equals (Arrays/copyOfRange d (int (* 4095 bs)) (int (* 4098 bs))) dst)))
      (store/set-len! s (id "big") (* 4090 bs))
      (is (= {:len (* 4090 bs)} (store/stat s (id "big"))))
      (is (empty? (:problems (store/verify s)))))))

(defn- corrupt!
  "Flips a byte in the payload of the record at `offset` of pack `pid`."
  [dir pid offset]
  (with-open [f (RandomAccessFile. (pack/pack-file dir pid) "rw")]
    (let [p (+ offset pack/header-len 1)]
      (.seek f p)
      (let [b (.read f)] (.seek f p) (.write f (bit-xor b 0xff))))))

(deftest corrupt-blocks-heal-to-missing
  (let [dir (tmp)]
    (with-store [s dir]
      (let [shared (noise 6 bs)]
        (store/put-files! s [[(id "a") (byte-array (concat shared (noise 7 bs)))]
                             [(id "b") shared]])
        (corrupt! dir (:id (first (:packs (store/stats s)))) 0)
        (let [[_ m] (read-all s (id "a"))]
          (is (= [[0 bs]] m)))
        (Thread/sleep 200)
        (testing "a shared block heals once, and every file sharing it sees it missing"
          (is (= [[0 bs]] (second (read-all s (id "b")))))
          (is (= 1 (:healed (store/stats s)))))
        (testing "writing it again repairs it"
          (store/write-blocks! s (id "b") 0 shared)
          (is (= [] (second (read-all s (id "b"))))))))))

(deftest concurrent-readers-and-writers
  (with-store [s (tmp)]
    (let [pool (Executors/newFixedThreadPool 8)
          fs   (vec (for [t (range 8)]
                      (.submit pool ^Callable
                               (fn []
                                 (dotimes [i 20]
                                   (let [fid (id (str t "-" i)) d (noise (+ (* t 100) i) (+ bs 17))]
                                     (store/put-files! s [[fid d]])
                                     (when-not (Arrays/equals d ^bytes (first (read-all s fid)))
                                       (throw (ex-info "mismatch" {})))))
                                 :ok))))]
      (is (every? #(= :ok (.get ^Future %)) fs))
      (.shutdown pool)
      (is (empty? (:problems (store/verify s)))))))

;; --- writes ---------------------------------------------------------------------------------------

(deftest write-errors
  (with-store [s (tmp)]
    (is (= :not-found (error-of #(store/write-blocks! s (id "x") 0 (noise 1 bs)))))
    (store/set-len! s (id "x") (* 2 bs))
    (is (= :out-of-range (error-of #(store/write-blocks! s (id "x") 2 (noise 1 bs)))))
    (is (= :unaligned (error-of #(store/write-blocks! s (id "x") 0 (noise 1 100)))))
    (store/set-len! s (id "y") (+ bs 100))
    (testing "a short last block is fine where the file ends"
      (store/write-blocks! s (id "y") 1 (noise 1 100))
      (is (= [[0 bs]] (second (read-all s (id "y"))))))))

(deftest dedup-and-reference-counts
  (with-store [s (tmp)]
    (let [a (noise 8 bs) b (noise 9 bs)]
      (store/put-files! s [[(id "f1") (byte-array (concat a a b))] [(id "f2") (byte-array (concat b a))]])
      (is (= 2 (:blocks (store/stats s))) "within and across files")
      (testing "compressible blocks are stored compressed"
        (store/put-files! s [[(id "zeros") (byte-array (* 4 bs))]])
        (let [live (reduce + (map :live (:packs (store/stats s))))]
          (is (< live (+ (* 2 (+ bs 40)) 200)))))
      (testing "overwriting frees the old block only when unshared"
        (store/put-files! s [[(id "f1") (noise 10 bs)]])
        (is (= 4 (:blocks (store/stats s))))           ; a, b (f2), zeros, new
        (store/delete! s (id "f2"))
        (is (= 2 (:blocks (store/stats s))))           ; zeros, new
        (store/set-len! s (id "zeros") 100)
        (is (= 1 (:blocks (store/stats s))) "a last block whose length changes is dropped"))
      (is (empty? (:problems (store/verify s)))))))

(deftest large-writes-span-transactions-segments-and-packs
  (with-store [s (tmp) {:max-pack-size (* 256 1024) :write-txn-bytes (* 64 1024)}]
    (let [d (noise 11 (* 300 bs))]
      (store/set-len! s (id "big") (alength d))
      (store/write-blocks! s (id "big") 0 d)
      (is (Arrays/equals d ^bytes (first (read-all s (id "big")))))
      (is (< 4 (count (:packs (store/stats s)))))
      (is (empty? (:problems (store/verify s)))))))

(deftest clones-and-digests
  (with-store [s (tmp)]
    (store/put-files! s [[(id "src") (noise 12 (* 3 bs))]])
    (store/clone-files! s [[(id "src") (id "dst")]])
    (is (Arrays/equals (noise 12 (* 3 bs)) ^bytes (first (read-all s (id "dst")))))
    (is (= :already-exists (error-of #(store/clone-files! s [[(id "src") (id "dst")]]))))
    (is (= :not-found (error-of #(store/clone-files! s [[(id "nope") (id "x")] ]))))
    (let [[d1 d2 d3] (store/content-digests s [(id "src") (id "dst") (id "nope")])]
      (is (= (first d1) (* 3 bs)))
      (is (Arrays/equals ^bytes (second d1) ^bytes (second d2)))
      (is (nil? d3)))
    (store/delete! s (id "src"))
    (is (Arrays/equals (noise 12 (* 3 bs)) ^bytes (first (read-all s (id "dst")))) "a clone keeps its blocks")
    (is (empty? (:problems (store/verify s))))))

;; --- compaction ---------------------------------------------------------------------------------------

(defn- fill
  "Files 0..n-1, one block each, in small packs."
  [s n]
  (doseq [i (range n)] (store/put-files! s [[(id i) (noise (+ 1000 i) bs)]])))

(deftest compaction
  (let [dir (tmp) opts {:max-pack-size (* 32 1024)}]
    (with-store [s dir opts]
      (fill s 40)
      (doseq [i (range 40) :when (odd? i)] (store/delete! s (id i)))
      (let [before (store/stats s)
            r      (store/compact! s)]
        (is (seq (:packs r)))
        (is (pos? (:freed r)))
        (testing "space is reclaimed and the data kept"
          (is (> (reduce + (map :bytes (:packs before))) (reduce + (map :bytes (:packs (store/stats s))))))
          (doseq [i (range 40) :when (even? i)]
            (is (Arrays/equals (noise (+ 1000 i) bs) ^bytes (first (read-all s (id i))))))
          (is (every? #(not (.exists (pack/pack-file dir %))) (:packs r))))
        (is (empty? (:problems (store/verify s))))
        (testing "the threshold is respected"
          (is (empty? (:packs (store/compact! s {:min-garbage-ratio 0.9})))))))
    (testing "a compacted store survives reopen"
      (with-store [s dir opts]
        (doseq [i (range 40) :when (even? i)]
          (is (Arrays/equals (noise (+ 1000 i) bs) ^bytes (first (read-all s (id i))))))
        (is (empty? (:problems (store/verify s))))))))

(deftest compaction-heals-a-corrupt-live-record
  (let [dir (tmp)]
    (with-store [s dir {:max-pack-size (* 16 1024)}]
      (fill s 12)
      (store/delete! s (id 1))
      (let [p (:id (first (:packs (store/stats s))))]
        (corrupt! dir p 0)
        (store/compact! s {:min-garbage-ratio 0.1})
        (Thread/sleep 200)
        (is (= [[0 bs]] (second (read-all s (id 0)))))
        (is (empty? (:problems (store/verify s))))))))

(deftest reads-and-writes-during-compaction
  (with-store [s (tmp) {:max-pack-size (* 32 1024)}]
    (fill s 60)
    (doseq [i (range 0 60 3)] (store/delete! s (id i)))
    (let [pool (Executors/newFixedThreadPool 4)
          stop (atom false)
          rd   (.submit pool ^Callable
                        (fn [] (loop [n 0]
                                 (if @stop n
                                     (do (doseq [i (range 60) :when (pos? (rem i 3))]
                                           (when-not (Arrays/equals (noise (+ 1000 i) bs) ^bytes (first (read-all s (id i))))
                                             (throw (ex-info "bad read" {:i i}))))
                                         (recur (inc n)))))))
          wr   (.submit pool ^Callable (fn [] (dotimes [i 30] (store/put-files! s [[(id (str "w" i)) (noise (+ 5000 i) bs)]])) :ok))]
      (dotimes [_ 3] (store/compact! s {:min-garbage-ratio 0.1}))
      (is (= :ok (.get wr)))
      (reset! stop true)
      (is (pos? (long (.get rd))))
      (.shutdown pool)
      (is (empty? (:problems (store/verify s)))))))

;; --- verify -----------------------------------------------------------------------------------------

(deftest verify-reports-problems
  (let [dir (tmp)]
    (with-store [s dir]
      (store/put-files! s [[(id "a") (noise 20 (* 2 bs))]])
      (is (= {:files 1 :blocks 2 :problems []} (store/verify s)))
      (corrupt! dir (:id (first (:packs (store/stats s)))) 0)
      (is (some #(re-find #"checksum" %) (:problems (store/verify s)))))))

;; --- crashes -----------------------------------------------------------------------------------------

(defn- child!
  "Runs aether.director.store-test/crash-child on `dir` in a new JVM, to halt at `point`;
  returns its exit code."
  [dir point]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        pb   (ProcessBuilder. ^java.util.List
                              [java "--enable-native-access=ALL-UNNAMED" (str "-Daether.store.crash=" point)
                               (str "-Daether.crash.dir=" dir)
                               "-cp" (System/getProperty "java.class.path")
                               "clojure.main" "-e"
                               "(require 'aether.director.store-test) (aether.director.store-test/crash-child)"])]
    (.inheritIO pb)
    (.waitFor (.start pb))))

(defn crash-child
  "In the child: writes, deletes and compacts in the store at
  -Daether.crash.dir until a crash point halts it."
  []
  (let [s (open (System/getProperty "aether.crash.dir") {:max-pack-size (* 32 1024)})]
    (doseq [i (range 20 30)] (store/put-files! s [[(id i) (noise (+ 1000 i) bs)]]))
    (doseq [i (range 0 30 2)] (store/delete! s (id i)))
    (store/compact! s {:min-garbage-ratio 0.1})
    (store/close s)
    (System/exit 0)))

(deftest crashes-leave-a-consistent-store
  (doseq [point ["write-after-append" "compact-after-copy" "compact-after-retire"]]
    (testing point
      (let [dir (tmp)]
        (with-store [s dir {:max-pack-size (* 32 1024)}] (fill s 20))
        (is (= 99 (child! dir point)))
        (with-store [s dir {:max-pack-size (* 32 1024)}]
          (is (empty? (:problems (store/verify s))))
          (doseq [i (range 20) :when (store/stat s (id i))]
            (is (Arrays/equals (noise (+ 1000 i) bs) ^bytes (first (read-all s (id i))))))
          (testing "and usable"
            (store/put-files! s [[(id "after") (noise 77 bs)]])
            (is (Arrays/equals (noise 77 bs) ^bytes (first (read-all s (id "after")))))))))))

;; --- the model --------------------------------------------------------------------------------------

(def ^:private gen-op
  (gen/one-of
   [(gen/tuple (gen/return :put) (gen/choose 0 4) (gen/choose 0 (* 3 bs)) (gen/choose 0 3))
    (gen/tuple (gen/return :set-len) (gen/choose 0 4) (gen/choose 0 (* 3 bs)))
    (gen/tuple (gen/return :write) (gen/choose 0 4) (gen/choose 0 2) (gen/choose 0 3))
    (gen/tuple (gen/return :delete) (gen/choose 0 4))
    (gen/tuple (gen/return :clone) (gen/choose 0 4) (gen/choose 0 4))
    (gen/tuple (gen/return :compact))]))

(defn- content
  "Block content from a small alphabet (so blocks dedup)."
  ^bytes [k n]
  (let [b (byte-array n)] (Arrays/fill b (unchecked-byte (* 31 (inc (long k))))) (when (pos? n) (aset-byte b 0 (unchecked-byte k))) b))

(defn- apply-op
  "Applies op to the store and the model ({id [bytes present-ranges]})."
  [s model [op a b c]]
  (let [fid (id a)]
    (case op
      :put (let [d (content c b)] (store/put-files! s [[fid d]]) (assoc model a d))
      :set-len (let [old ^bytes (get model a (byte-array 0))]
                 (store/set-len! s fid b)
                 ;; dropped blocks read as missing: model them as nil bytes
                 (assoc model a (let [n (Arrays/copyOf old (int b))] n) [:resized a] true))
      :write (if-let [^bytes old (get model a)]
               (let [len (alength old) start (* b bs)]
                 (if (< start len)
                   (let [n (min bs (- len start)) d (content c n)]
                     (store/write-blocks! s fid b d)
                     (assoc model a (doto (aclone old) (#(System/arraycopy d 0 % start n)))))
                   model))
               model)
      :delete (do (store/delete! s fid) (dissoc model a))
      :clone (if (and (get model a) (not (get model b)) (not= a b))
               (do (store/clone-files! s [[fid (id b)]]) (assoc model b (get model a) [:resized b] (get model [:resized a])))
               model)
      :compact (do (store/compact! s {:min-garbage-ratio 0.0}) model))))

(defn- check-model [s model]
  (every? true?
          (for [[k v] model :when (number? k)]
            (let [^bytes v v
                  [^bytes got missing] (read-all s (id k))]
              (and (= (alength v) (alength got))
                   ;; every byte not reported missing matches
                   (let [mask (boolean-array (alength v) true)]
                     (doseq [[st en] missing] (Arrays/fill mask (int st) (int en) false))
                     (every? #(or (not (aget mask %)) (= (aget v %) (aget got %))) (range (alength v)))))))))

(deftest model-of-random-operations
  (let [r (tc/quick-check
           25
           (prop/for-all [ops (gen/vector gen-op 1 40)]
             (with-store [s (tmp) {:max-pack-size (* 16 1024)}]
               (let [model (reduce (fn [m op] (apply-op s m op)) {} ops)]
                 (and (check-model s model)
                      (empty? (:problems (store/verify s))))))))]
    (is (:pass? r) (pr-str (:shrunk r)))))
