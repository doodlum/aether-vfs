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

  The ring is a named section, or a file (ring/start's :transport); the env
  that names it comes from aether.director.env.

  Starting the process stays native: rust/'s vfs-injector creates it with the
  shim, waits for the shim's ready file, and exits with the target's code.
  The target inherits the injector's environment, and the injector inherits
  ours, so the child's environment is built explicitly here, never by
  changing this process's own."
  (:require [aether.director.env :as env]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream File)
           (java.lang ProcessBuilder$Redirect)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)
           (java.util List Map)))

(set! *warn-on-reflection* true)

(defn shim-config
  "shim.cfg bytes, version 2 (vfs-protocol/src/shimcfg.rs):
  \"VFSC\" u32 2, u32 len root, u32 n, then n x (u32 len dll, u32 len backing).
  `static-imports` are [dll-name backing-path] pairs. Nothing may follow."
  (^bytes [^String root] (shim-config root []))
  (^bytes [^String root static-imports]
   (let [out  (ByteArrayOutputStream.)
         u32  (fn [n] (.write out (.array (.putInt (.order (ByteBuffer/allocate 4) ByteOrder/LITTLE_ENDIAN) (int n)))))
         str! (fn [^String s] (let [b (.getBytes s StandardCharsets/UTF_8)] (u32 (alength b)) (.write out b)))]
     (.write out (.getBytes "VFSC" StandardCharsets/US_ASCII))
     (u32 2)
     (str! root)
     (u32 (count static-imports))
     (doseq [[dll backing] static-imports] (str! dll) (str! backing))
     (.toByteArray out))))

(defn- canonical ^String [f] (.getCanonicalPath (io/file f)))

(defn fuse-cfg
  "fuse.cfg's text for `server` and the managed root `root`: a section names
  itself and its events, a file its path as the shim opens it."
  ^String [server ^String root]
  (let [{:keys [cap map-bytes arena-offset arena-len]} server
        geometry (str "
payload_cap=" cap "
ring_bytes=" map-bytes
                      "
arena_offset=" arena-offset "
arena_len=" arena-len)]
    (case (:transport server)
      :section (str "section=" (:name server) "
root=" root geometry
                    "
server_ev=" (:server-event server) "
client_ev=" (:client-event server) "
")
      :file    (str "ring_path=" (:ring-path server) "
root=" root geometry "
"))))

(defn session-env
  "The shim's env for `server`: the transport's (aether.director.env/transport-env),
  fuse.cfg's path, the managed root, the roots beyond root 0 ({id path}), and
  the registry switch when the server serves one."
  [server {:keys [root fuse roots]}]
  (cond-> (merge (env/transport-env server)
                 {env/fuse-cfg      fuse
                  env/virtual-dir   root
                  ;; set even when empty, as it always was
                  env/virtual-roots (or (env/encode-roots roots) "")})
    (:registry? server) (assoc env/registry "1")))

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
        fuse    (io/file state "fuse.cfg")]
    (io/copy (shim-config root-s) shim)
    (spit fuse (fuse-cfg server root-s))
    {:root root-s :state (canonical state)
     :shim-config (canonical shim) :ready (str (canonical state) File/separator "ready.flag")
     :env (session-env server {:root root-s :fuse (canonical fuse) :roots roots})}))

(defn launch-env
  "The child's env for a launch under `session`, as {:remove names :set [[k
  v] …]} for apply-env!: every transport name the launch doesn't set is
  removed (an earlier session's would otherwise reach the child), and so is
  each of `remove-env` (in any spelling); then the session's env, the
  injector's, `natives-dir` in front of `host-path` on PATH, the staged
  `launch-image`, and last the caller's `env`, which wins."
  [session {:keys [env cwd launch-image remove-env]} ^String natives-dir host-path]
  (let [sets (-> (vec (:env session))
                 (conj [env/inject-cwd (if cwd (str (io/file (:root session) (str cwd))) (:root session))]
                       ;; Remote-thread injection, always: the shim is loaded into the
                       ;; suspended process by full path. (The injector's alternative patches
                       ;; the image's import table in memory, which a self-verifying SteamStub
                       ;; wrapper such as SkyrimSE.exe refuses: "Application load error
                       ;; 3:0000065432".)
                       [env/force-injection "1"]
                       ;; The shim's own DLLs (vcruntime140 in a bundle) resolve from the
                       ;; natives directory: a staged EXE's folder doesn't hold them.
                       ["PATH" (str natives-dir File/pathSeparator host-path)])
                 (cond-> launch-image (conj [env/launch-image (str launch-image)]))
                 (into env))
        set-names (set (map first sets))]
    ;; transport names only: on Windows the host may set the injector's own
    {:remove (into (env/stale set-names env/transport) (remove set-names) remove-env)
     :set    sets}))

(defn apply-env!
  "Applies launch-env's result to `m`, a ProcessBuilder's environment."
  ^Map [^Map m delta]
  (env/apply! m delta))

(defn launch
  "Runs `exe` (a real file) with `args` under `session`, via the injector at
  `natives` (a directory holding vfs-injector.exe and vfs_shim_dll.dll), with
  the managed root as its working directory. `env` adds variables for the
  target; `launch-image` (a staged EXE) makes that EXE's folder an alias of
  the root. `remove-env` names inherited variables the target must not see
  (matched without case). Waits, and returns the exit code (the injector's
  own failures are 3, explained in <ready>.injector-error); output goes to
  ours."
  [session {:keys [natives exe args] :as opts}]
  (let [native (fn [n] (let [f (io/file natives n)]
                         (when-not (.isFile f) (throw (ex-info (str n " not found in " natives) {:file f})))
                         (canonical f)))
        cmd    (concat [(native "vfs-injector.exe") (canonical exe)
                        (native "vfs_shim_dll.dll")
                        (:shim-config session) (:ready session)]
                       (when (seq args) (cons "--" args)))
        pb     (doto (ProcessBuilder. ^List (vec cmd))
                 (.directory (io/file (:root session)))
                 (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                 (.redirectError ProcessBuilder$Redirect/INHERIT))]
    (io/delete-file (:ready session) true)
    (apply-env! (.environment pb) (launch-env session opts (canonical natives) (System/getenv "PATH")))
    (.waitFor (.start pb))))
