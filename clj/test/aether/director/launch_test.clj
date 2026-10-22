(ns aether.director.launch-test
  "The launch env, built through aether.director.env, against the env the
  launch built before it did (`legacy-*` below are launch.clj's code as of
  09f89535, verbatim but for taking their inputs as arguments)."
  (:require [aether.director.launch :as launch]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util Map TreeMap)))

(defn- temp-dir ^File [] (.toFile (Files/createTempDirectory "aether-launch" (make-array FileAttribute 0))))

(def ^:private section-server
  {:transport :section :name "Local\\vfs_ring_clj_42" :server-event "Local\\vfs_ring_clj_42_srv"
   :client-event "Local\\vfs_ring_clj_42_cli" :map-bytes 67174400 :cap 1048576
   :arena-offset 33555504 :arena-len 33554432})

(defn- legacy-session-env [server root-s fuse-path roots]
  (let [{section :name
         :keys [cap map-bytes arena-offset arena-len server-event client-event registry?]} server]
    (cond-> {"VFS_RING_SECTION"     section
             "VFS_RING_BYTES"       (str map-bytes)
             "VFS_RING_PAYLOAD_CAP" (str cap)
             "VFS_ARENA_OFFSET"     (str arena-offset)
             "VFS_ARENA_LEN"        (str arena-len)
             "VFS_SERVER_EV"        server-event
             "VFS_CLIENT_EV"        client-event
             "VFS_FUSE_CFG"         fuse-path
             "VFS_VIRTUAL_DIR"      root-s
             "VFS_VIRTUAL_ROOTS"    (str/join ";" (for [[id at] (sort roots)] (str id "=" at)))}
      registry? (assoc "VFS_REGISTRY" "1"))))

(defn- legacy-fuse-cfg [server root-s]
  (let [{section :name :keys [cap map-bytes arena-offset arena-len server-event client-event]} server]
    (str "section=" section "\nroot=" root-s "\npayload_cap=" cap "\nring_bytes=" map-bytes
         "\narena_offset=" arena-offset "\narena_len=" arena-len
         "\nserver_ev=" server-event "\nclient_ev=" client-event "\n")))

(defn- legacy-launch-env!
  "What launch did to the ProcessBuilder's environment `m`."
  [^Map m session {:keys [env launch-image cwd]} natives-dir host-path]
  (doto m
    (.remove "VFS_RING_PATH")
    (.remove "VFS_REGISTRY")
    (.putAll ^Map (:env session))
    (.put "VFS_INJECT_CWD" (if cwd (str (io/file (:root session) (str cwd))) (:root session)))
    (.put "VFS_FORCE_INJECTION" "1")
    (.put "PATH" (str natives-dir File/pathSeparator host-path))
    (cond-> launch-image (.put "VFS_LAUNCH_IMAGE" (str launch-image)))
    (.putAll ^Map (or env {}))))

(defn- windows-env
  "A Windows ProcessBuilder environment: names compare without case."
  ^Map [m]
  (doto (TreeMap. String/CASE_INSENSITIVE_ORDER) (.putAll ^Map m)))

(def ^:private inherited
  ;; what a host that served an earlier session (or a file ring) might carry
  {"Path" "C:\\Windows" "SystemRoot" "C:\\Windows" "USERPROFILE" "C:\\Users\\u"
   "vfs_ring_path" "C:\\stale\\ring.bin" "VFS_REGISTRY" "1"
   "VFS_RING_SECTION" "Local\\old" "VFS_VIRTUAL_ROOTS" "9=C:\\old" "VFS_INJECT_STEAM_HELPER" "off"
   "SteamAppId" "489830"})

(deftest the-windows-section-launch-env-is-unchanged
  (doseq [[what server sopts lopts]
          [["plain" section-server {} {}]
           ["a registry, more roots" (assoc section-server :registry? true)
            {:roots {2 "C:\\Users\\u\\Documents\\My Games" 1 "C:\\Data"}} {}]
           ["cwd, a staged image, the caller's env"
            section-server {}
            {:cwd "bin/x64" :launch-image "C:\\s\\stage\\game.exe"
             :env {"FOO" "bar" "VFS_REGISTRY" "1" "Path" "C:\\mine"}}]]]
    (testing what
      (let [dir     (temp-dir)
            s       (launch/session server dir sopts)
            fuse    (str (io/file (:state s) "fuse.cfg"))]
        (is (= (legacy-session-env server (:root s) fuse (:roots sopts)) (:env s)) "the session's env")
        (is (= (legacy-fuse-cfg server (:root s)) (slurp fuse)) "fuse.cfg")
        (let [delta  (launch/launch-env s lopts "C:\\natives" "C:\\Windows")
              before (into {} (legacy-launch-env! (windows-env inherited) s lopts "C:\\natives" "C:\\Windows"))
              after  (into {} (launch/apply-env! (windows-env inherited) delta))]
          (is (= before after) "the child's environment"))))))

(deftest the-only-name-a-section-launch-now-also-clears-is-an-inherited-root-backings
  (let [dir   (temp-dir)
        s     (launch/session section-server dir {})
        inh   (assoc inherited "VFS_ROOT_BACKINGS" "0=C:\\old")
        delta (launch/launch-env s {} "C:\\n" "C:\\W")
        before (into {} (legacy-launch-env! (windows-env inh) s {} "C:\\n" "C:\\W"))
        after  (into {} (launch/apply-env! (windows-env inh) delta))]
    (is (= (dissoc before "VFS_ROOT_BACKINGS") after))
    (is (contains? before "VFS_ROOT_BACKINGS"))))

(deftest a-file-ring-session-names-the-file-and-no-section-or-events
  (let [dir    (temp-dir)
        server (-> section-server (dissoc :name :server-event :client-event)
                   (assoc :transport :file :ring-path "C:\\r\\state\\ring.bin"))
        s      (launch/session server dir {})
        ;; looked up as Windows does, without case
        ^Map child (launch/apply-env! (windows-env inherited) (launch/launch-env s {} "C:\\n" "C:\\W"))]
    (is (= "C:\\r\\state\\ring.bin" (.get child "VFS_RING_PATH")) "replaces the inherited vfs_ring_path")
    (is (= "67174400" (.get child "VFS_RING_BYTES")))
    (is (not-any? #(.containsKey child %) ["VFS_RING_SECTION" "VFS_SERVER_EV" "VFS_CLIENT_EV" "VFS_REGISTRY"]))
    (is (= "off" (.get child "VFS_INJECT_STEAM_HELPER")) "injector names are the host's on Windows")
    (is (= (str "ring_path=C:\\r\\state\\ring.bin\nroot=" (:root s) "\npayload_cap=1048576\nring_bytes=67174400"
                "\narena_offset=33555504\narena_len=33554432\n")
           (slurp (io/file (:state s) "fuse.cfg"))))))

(deftest a-launch-can-remove-inherited-names-in-any-spelling
  (let [s     (launch/session section-server (temp-dir) {})
        child (into {} (launch/apply-env! (windows-env inherited)
                                          (launch/launch-env s {:remove-env ["STEAMAPPID"]} "C:\\n" "C:\\W")))]
    (is (not (contains? child "SteamAppId")))))
