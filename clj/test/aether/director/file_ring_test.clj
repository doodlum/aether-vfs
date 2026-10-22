(ns aether.director.file-ring-test
  "End to end over a file ring: a real program, started by rust/'s injector
  with the shim, reads through the VFS while the director serves a ring held
  in a file (VFS_RING_PATH, ParkWaker): the Linux transport, minus Wine.

  Needs Windows and the release natives (cd rust; cargo build --release -p
  vfs-inject -p vfs-shim-dll -p vfs-fixture-read), found in $VFS_NATIVES or
  <repo>/rust/target/release; skips itself otherwise, and fails without the
  natives under VFS_TEST_REQUIRE_ALL=1."
  (:require [aether.director.fixture :as fixture]
            [aether.director.launch :as launch]
            [aether.director.provider.tree :as tree]
            [aether.director.ring :as ring]
            [aether.director.test-support :as ts]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def natives (ts/natives))

(defn requirements
  "What the end-to-end tests need: Windows, then the three natives."
  []
  [(ts/windows)
   (some (fn [n] (ts/prerequisite (.isFile (io/file natives n))
                                  (str (io/file natives n) " not built (cd rust; cargo build --release -p vfs-inject "
                                       "-p vfs-shim-dll -p vfs-fixture-read), or set VFS_NATIVES")))
         ["vfs-injector.exe" "vfs_shim_dll.dll" "vfs-fixture-read.exe"])])

(defn- temp-dir ^File [] (.toFile (Files/createTempDirectory "aether-file-ring" (make-array FileAttribute 0))))

(defn run-fixture
  "Serves the benchmark fixture over a `transport` ring and runs
  vfs-fixture-read under it with `env` (fn of the managed root's path).
  Returns {:exit :served :ms :ring-file}."
  [transport env-of & [{:keys [workers] :or {workers 4}}]]
  (let [dir    (temp-dir)
        t      (tree/build (fixture/files))
        srv    (ring/start t {:transport transport
                              :name (str "Local\\vfs_ring_clj_e2e_" (System/nanoTime))
                              :path (io/file dir "state" "ring.bin")
                              :workers workers :warm-up 300})
        s      (launch/session srv dir)]
    (try
      (let [t0   (System/nanoTime)
            code (launch/launch s {:natives natives :exe (io/file natives "vfs-fixture-read.exe")
                                   :env (env-of (:root s) (:state s))})]
        {:exit code :served (:served (ring/stats srv)) :ms (/ (- (System/nanoTime) t0) 1e6)
         :ring-file (:host-path srv) :transport (:transport srv)})
      (finally (ring/stop srv)))))

(defn read-env [root _state]
  {"VFS_FIXTURE_PATH" (str root File/separator "big.bin") "VFS_FIXTURE_EXPECT" "70000"
   "VFS_FIXTURE_FILL" (str (int \X))})

(defn ops-env
  "The fixture runs `n` reads of hello.txt through its ops phase (ops.rs),
  then the read of big.bin."
  [n]
  (fn [root state]
    (let [script (io/file state "ops.txt")]
      (spit script (str/join (repeat n (str "read\t" root File/separator "hello.txt\n"))))
      (merge (read-env root state)
             {"VFS_FIXTURE_OPS" (str script) "VFS_FIXTURE_OPS_OUT" (str (io/file state "ops.out"))}))))

(deftest a-program-reads-through-the-vfs-over-a-file-ring
  (ts/requires (requirements)
    (let [{:keys [exit served ring-file transport]} (run-fixture :file read-env)]
      (is (= :file transport))
      (is (= 0 exit) "the fixture read big.bin's 70000 bytes through the shim")
      (is (pos? (long served)) "over this director's ring")
      (is (.isFile (io/file ring-file)) "a ring file in the session's state"))))

(deftest the-ops-phase-reads-through-a-file-ring-too
  (ts/requires (requirements)
    (let [{:keys [exit served]} (run-fixture :file (ops-env 50))]
      (is (= 0 exit))
      (is (> (long served) 50)))))

(defn latency
  "Per-request latency over `transport`, from the difference between a run
  of `n` reads and a run of none: [{:exit :served :ms} …] and µs per request.
  Run by hand for numbers: (latency :file 5000 3)."
  [transport n rounds]
  (let [runs (vec (for [_ (range rounds)]
                    [(run-fixture transport (ops-env 0)) (run-fixture transport (ops-env n))]))
        per  (for [[a b] runs]
               (/ (* 1000.0 (- (double (:ms b)) (double (:ms a))))
                  (- (long (:served b)) (long (:served a)))))]
    {:transport transport :n n
     :runs (mapv (fn [[a b]] [(select-keys a [:exit :served :ms]) (select-keys b [:exit :served :ms])]) runs)
     :us-per-request (vec per)}))
