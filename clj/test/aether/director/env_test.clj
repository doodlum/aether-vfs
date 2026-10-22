(ns aether.director.env-test
  "The handshake env as data, with vfs-env's tests ported
  (rust/crates/vfs-env/src/lib.rs:1115-1240, vfs-proton launch/mod.rs:753-788,
  launch/env.rs:314-339)."
  (:require [aether.director.env :as env]
            [aether.director.test-support :as ts]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util HashMap Map TreeMap)))

(deftest the-tables-name-each-handshake-name-once
  (let [all (env/handshake-names)]
    (is (= (count all) (count (distinct all))) "a name is in two handshake tables")
    (is (every? #(str/starts-with? % "VFS_") all))
    (is (= 13 (count env/transport)))
    (is (= [env/inject-cwd env/inject-steam-helper] env/inject))))

(defn- rust-table
  "The names in `pub const <table>: &[&str] = &[ … ];` of vfs-env's lib.rs,
  resolved through its `pub const NAME: &str = \"VFS_…\";` lines."
  [^String src table]
  (let [consts (into {} (for [[_ k v] (re-seq #"pub const ([A-Z0-9_]+): &str = \"(VFS_[A-Z0-9_]+)\";" src)] [k v]))
        body   (second (re-find (re-pattern (str "pub const " table ": &\\[&str\\] = &\\[([^\\]]*)\\];")) src))]
    (mapv consts (re-seq #"[A-Z0-9_]+" body))))

(deftest the-tables-match-vfs-env
  (let [f (io/file (ts/repo-root) "rust" "crates" "vfs-env" "src" "lib.rs")]
    (ts/requires [(ts/prerequisite (.isFile f) (str f " not found: the Rust workspace is part of the checkout"))]
      (let [src (slurp f)]
        (is (= (rust-table src "TRANSPORT") env/transport))
        (is (= (rust-table src "INJECT") env/inject))
        (is (= (rust-table src "CONFIG") env/config))))))

(deftest registry-and-fuse-cfg-are-launch-cleared-handshake-names
  (doseq [n [env/registry env/fuse-cfg env/ring-path env/inject-cwd]]
    (is (env/handshake? n) n)
    (is (some #{n} (env/stale (constantly false))) (str n " not cleared")))
  (is (env/handshake? "vfs_registry") "case-insensitive")
  (is (not (env/handshake? "VFS_BENCH"))))

(deftest stale-skips-what-the-launch-set
  (let [s (set (env/stale #{env/registry env/ring-path}))]
    (is (not (s env/registry)))
    (is (not (s env/ring-path)))
    (is (s env/ring-section)))
  (testing "a Windows section launch clears transport names only: its host may set the injector's"
    (is (= (remove #{env/ring-section} env/transport)
           (env/stale #{env/ring-section} env/transport)))))

(deftest roots-encode-as-id-equals-location-joined-by-semicolons
  (is (nil? (env/encode-roots [])))
  (is (nil? (env/encode-roots {})))
  (is (= "1=C:\\a;2=C:\\b" (env/encode-roots [[1 "C:\\a"] [2 "C:\\b"]])))
  (is (= "1=C:\\a;2=C:\\b" (env/encode-roots {2 "C:\\b" 1 "C:\\a"})) "a map is sorted by id")
  (is (= "2=C:\\b;1=C:\\a" (env/encode-roots [[2 "C:\\b"] [1 "C:\\a"]])) "pairs keep their order, as Rust's"))

(deftest the-handshake-tables-are-disjoint-and-cover-the-handshake
  (let [all (concat env/transport env/inject env/config)]
    (is (= (count all) (count (set all))) "a name is in two tables")
    (is (every? #(str/starts-with? % "VFS_") all))
    (is (= 23 (count all)) "vfs-env/src/lib.rs:1121-1154 (handshake::TRANSPORT, INJECT, CONFIG)")))

(deftest a-removed-name-is-cleared-in-every-inherited-spelling-unless-the-launch-sets-it
  ;; vfs-proton launch/mod.rs:765 (clear_env)
  (let [r (set (env/removals ["SteamAppId" "STEAMGAMEID" "HOME" "winedebug"]
                             ["steamappid" "SteamGameId" "WINEDEBUG"]
                             #{"WINEDEBUG"}))]
    (is (r "SteamAppId") "matched in any case")
    (is (r "STEAMGAMEID") "matched in any case")
    (is (not (r "HOME")) "not asked for")
    (is (not (r "winedebug")) "the launch sets WINEDEBUG itself, so a removal does not clear it")))

(def ^:private section-server
  {:transport :section :name "Local\\vfs_ring_clj_1" :server-event "Local\\vfs_ring_clj_1_srv"
   :client-event "Local\\vfs_ring_clj_1_cli" :map-bytes 67174400 :cap 1048576
   :arena-offset 33555504 :arena-len 33554432})

(def ^:private file-server
  (-> section-server (dissoc :name :server-event :client-event)
      (assoc :transport :file :ring-path "C:\\vfs-session\\state\\ring.bin")))

(deftest the-transport-env-is-the-backing-s
  (testing "a section: its name, both events and the geometry"
    (is (= {"VFS_RING_SECTION" "Local\\vfs_ring_clj_1" "VFS_RING_BYTES" "67174400"
            "VFS_RING_PAYLOAD_CAP" "1048576" "VFS_ARENA_OFFSET" "33555504" "VFS_ARENA_LEN" "33554432"
            "VFS_SERVER_EV" "Local\\vfs_ring_clj_1_srv" "VFS_CLIENT_EV" "Local\\vfs_ring_clj_1_cli"}
           (env/transport-env section-server))))
  (testing "a file: its path as the shim names it, and the geometry; no section, no events"
    (is (= {"VFS_RING_PATH" "C:\\vfs-session\\state\\ring.bin" "VFS_RING_BYTES" "67174400"
            "VFS_RING_PAYLOAD_CAP" "1048576" "VFS_ARENA_OFFSET" "33555504" "VFS_ARENA_LEN" "33554432"}
           (env/transport-env file-server)))
    (let [stale (set (env/stale (set (keys (env/transport-env file-server)))))]
      (is (every? stale [env/ring-section env/server-ev env/client-ev])
          "an inherited section or event can't reach the child"))))

(deftest every-transport-name-is-exactly-one-of-set-or-cleared
  ;; vfs-proton launch/env.rs:322
  (doseq [srv [section-server file-server]]
    (let [set-names (set (keys (env/transport-env srv)))
          stale     (set (env/stale set-names))]
      (doseq [n (concat env/transport env/inject)]
        (is (not= (contains? set-names n) (contains? stale n)) n)))))

(deftest apply-removes-every-spelling-then-sets-in-order
  (testing "a case-sensitive (Linux) environment"
    (let [m (doto (HashMap.) (.put "vfs_ring_path" "x") (.put "VFS_RING_PATH" "y") (.put "HOME" "/h"))]
      (env/apply! m {:remove ["VFS_RING_PATH"] :set [["A" "1"] ["A" "2"]]})
      (is (= {"HOME" "/h" "A" "2"} (into {} m)))))
  (testing "a case-insensitive (Windows) environment"
    (let [m (doto (TreeMap. String/CASE_INSENSITIVE_ORDER) (.put "Vfs_Registry" "1") (.put "Path" "p"))]
      (env/apply! m {:remove ["VFS_REGISTRY"] :set [["PATH" "q"]]})
      (is (= {"Path" "q"} (into {} m))))))
