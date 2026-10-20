(ns aether.director.launch
  "Launching a program under the director: the host half of vfs-embed's
  Session::serve + Session::launch.

  The game sees an empty directory, the managed root, as its folder; the shim
  inside it turns every path under that root into a ring request. The shim
  finds the ring through its environment and the session's state directory:

    shim.cfg   VFSC v2: the root and a static-import table
               (vfs-protocol/src/shimcfg.rs)
    fuse.cfg   the ring's coordinates as key=value lines (written for tools;
               the shim reads its environment instead)

  Starting the process stays native: rust/'s vfs-injector creates it with the
  shim, waits for the shim's ready file, and exits with the target's code.
  The target inherits the injector's environment, and the injector inherits
  ours, so the child's environment is built explicitly here, never by
  changing this process's own."
  (:require [aether.director.ring :as ring]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream File)
           (java.lang ProcessBuilder$Redirect)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)))

;; vfs-embed's EMPTY_TREE_SNAPSHOT_HEX: a single empty root directory (128 bytes)
(defn shim-config
  "shim.cfg bytes, version 2 (vfs-protocol/src/shimcfg.rs):
  \"VFSC\" u32 2, u32 len root, u32 n, then n x (u32 len dll, u32 len backing).
  `static-imports` are [dll-name backing-path] pairs. Nothing may follow."
  (^bytes [^String root] (shim-config root []))
  (^bytes [^String root static-imports]
   (let [out (ByteArrayOutputStream.)
         u32 (fn [n] (.write out (.array (.putInt (.order (ByteBuffer/allocate 4) ByteOrder/LITTLE_ENDIAN) (int n)))))
         str! (fn [^String s] (let [b (.getBytes s StandardCharsets/UTF_8)] (u32 (alength b)) (.write out b)))]
     (.write out (.getBytes "VFSC" StandardCharsets/US_ASCII))
     (u32 2)
     (str! root)
     (u32 (count static-imports))
     (doseq [[dll backing] static-imports] (str! dll) (str! backing))
     (.toByteArray out))))

(defn- canonical ^String [f] (.getCanonicalPath (io/file f)))

(defn session
  "Creates the session's directories under `dir`: root/ (the managed root,
  empty), overlay/ and state/, and writes shim.cfg and fuse.cfg for `server`
  (from ring/start). Returns the session map launch takes. `roots` declares
  the roots beyond root 0 to the shim: {id path-the-program-uses}. When the
  server serves a registry, the shim is told to install its registry hooks."
  [server dir & [{:keys [roots]}]]
  (let [[root state] (for [d ["root" "state"]] (doto (io/file dir d) (.mkdirs)))
        root-s  (canonical root)
        shim    (io/file state "shim.cfg")
        fuse    (io/file state "fuse.cfg")
        {:keys [name cap map-bytes arena-offset arena-len server-event client-event registry?]} server]
    (io/copy (shim-config root-s) shim)
    (spit fuse (str "section=" name "\nroot=" root-s "\npayload_cap=" cap "\nring_bytes=" map-bytes
                    "\narena_offset=" arena-offset "\narena_len=" arena-len
                    "\nserver_ev=" server-event "\nclient_ev=" client-event "\n"))
    {:root root-s :state (canonical state)
     :shim-config (canonical shim) :ready (str (canonical state) File/separator "ready.flag")
     :env (cond-> {"VFS_RING_SECTION"     name
                  "VFS_RING_BYTES"       (str map-bytes)
                  "VFS_RING_PAYLOAD_CAP" (str cap)
                  "VFS_ARENA_OFFSET"     (str arena-offset)
                  "VFS_ARENA_LEN"        (str arena-len)
                  "VFS_SERVER_EV"        server-event
                  "VFS_CLIENT_EV"        client-event
                  "VFS_FUSE_CFG"         (canonical fuse)
                  "VFS_VIRTUAL_DIR"      root-s
                  "VFS_VIRTUAL_ROOTS"    (str/join ";" (for [[id at] (sort roots)] (str id "=" at)))}
             registry? (assoc "VFS_REGISTRY" "1"))}))

(defn launch
  "Runs `exe` (a real file) with `args` under `session`, via the injector at
  `natives` (a directory holding vfs-injector.exe and vfs_shim_dll.dll), with
  the managed root as its working directory. `env` adds variables for the
  target; `launch-image` (a staged EXE) makes that EXE's folder an alias of
  the root. Waits, and returns the exit code (the injector's own failures are
  3, explained in <ready>.injector-error); output goes to ours."
  [session {:keys [natives exe args env launch-image cwd]}]
  (let [native (fn [n] (let [f (io/file natives n)]
                         (when-not (.isFile f) (throw (ex-info (str n " not found in " natives) {:file f})))
                         (canonical f)))
        cmd    (concat [(native "vfs-injector.exe") (canonical exe)
                        (native "vfs_shim_dll.dll")
                        (:shim-config session) (:ready session)]
                       (when (seq args) (cons "--" args)))
        pb     (doto (ProcessBuilder. ^java.util.List (vec cmd))
                 (.directory (io/file (:root session)))
                 (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                 (.redirectError ProcessBuilder$Redirect/INHERIT))]
    (io/delete-file (:ready session) true)
    (doto (.environment pb)
      ;; a file-backed ring would take priority over our section; VFS_REGISTRY
      ;; comes back with the session's env only when a registry is served
      (.remove "VFS_RING_PATH")
      (.remove "VFS_REGISTRY")
      (.putAll ^java.util.Map (:env session))
      (.put "VFS_INJECT_CWD" (if cwd (str (io/file (:root session) (str cwd))) (:root session)))
      ;; Remote-thread injection, always: the shim is loaded into the
      ;; suspended process by full path. (The injector's alternative patches
      ;; the image's import table in memory, which a self-verifying SteamStub
      ;; wrapper such as SkyrimSE.exe refuses: "Application load error
      ;; 3:0000065432".)
      (.put "VFS_FORCE_INJECTION" "1")
      ;; The shim is activated by patching the target's imports in memory, by
      ;; DLL name: the loader must find vfs_shim_dll.dll, and a staged EXE's
      ;; folder doesn't hold it.
      (.put "PATH" (str (canonical natives) java.io.File/pathSeparator (System/getenv "PATH")))
      (cond-> launch-image (.put "VFS_LAUNCH_IMAGE" (str launch-image)))
      (.putAll ^java.util.Map (or env {})))
    (.waitFor (.start pb))))
