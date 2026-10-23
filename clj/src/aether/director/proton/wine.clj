(ns aether.director.proton.wine
  "The effectful half of a Linux launch (vfs-proton/src/prefix/*, launch/mod.rs,
  vfs-embed session/proton/handle.rs): setting a prefix up, locking it,
  linking the session and the roots into its drive_c, spawning wine, and
  following the launch to its end through wineserver. The decisions are
  proton.prefix's and proton.launch's; this namespace only carries them out.

  Every host program runs with the runtime's library env, the host's env
  cleaned of an AppImage's additions (proton.hostenv), and, through setsid,
  in a process group of its own, so a Ctrl-C meant for the host never reaches
  an unfinished prefix setup."
  (:require [aether.director.env :as henv]
            [aether.director.proton.hostenv :as hostenv]
            [aether.director.proton.launch :as plaunch]
            [aether.director.proton.layout :as layout :refer [join]]
            [aether.director.proton.prefix :as prefix]
            [aether.director.proton.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File IOException)
           (java.lang ProcessBuilder$Redirect)
           (java.nio.channels FileChannel FileLock)
           (java.nio.file Files LinkOption Path StandardOpenOption)
           (java.nio.file.attribute FileAttribute)
           (java.util List Map)
           (java.util.concurrent TimeUnit)))

(set! *warn-on-reflection* true)

(def proton-init-timeout-secs "How long Proton's prefix setup may take (PROTON_INIT_TIMEOUT, init.rs)." 300)
(def wineserver-timeout-secs "Each of wineserver -k and -w when stopping (wineserver.rs:26-52)." 30)

(defn- fail [kind msg & [data]] (throw (ex-info msg (assoc data ::error kind))))

(defn- path ^Path [p] (.toPath (io/file (str p))))
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(defn- symlink? [p] (Files/isSymbolicLink (path p)))
(defn- exists-nofollow? [p] (Files/exists (path p) no-follow))

(defn host-env
  "This process's environment as a host program should inherit it."
  []
  (hostenv/clean-env (into {} (System/getenv))))

(defn- command
  "A ProcessBuilder for `argv` in a process group of its own (setsid), with
  `env` ({name value}, applied over the cleaned host env) and the runtime's
  library env when `rt` is given; names in `remove` are dropped (any case)."
  ^ProcessBuilder [argv {:keys [rt env remove]}]
  (let [pb   (ProcessBuilder. ^List (vec (cons "setsid" argv)))
        ^Map m (.environment pb)]
    (.clear m)
    (.putAll m ^Map (host-env))
    (when rt
      (.putAll m ^Map (runtime/runtime-lib-env rt (hostenv/inherited (host-env) "LD_LIBRARY_PATH")
                                               (hostenv/inherited (host-env) "ORIG_LD_LIBRARY_PATH")
                                               (hostenv/inherited (host-env) "WINEDLLPATH"))))
    (henv/apply! m {:remove (vec remove) :set (vec env)})
    (.redirectInput pb ProcessBuilder$Redirect/PIPE)
    pb))

(defn- start!
  "Starts `pb`, retrying while the program's file is busy being written
  (ETXTBSY, error=26: a runtime extracted a moment ago; process.rs:65-79)."
  ^Process [^ProcessBuilder pb]
  (loop [tries 0]
    (let [r (try (.start pb)
                 (catch IOException e
                   (if (and (< tries 20) (str/includes? (str (ex-message e)) "error=26"))
                     ::busy
                     (throw e))))]
      (if (= ::busy r)
        (do (Thread/sleep 50) (recur (inc tries)))
        (doto ^Process r (-> .getOutputStream .close))))))

(defn- run-bounded
  "Runs `pb` to its end within `secs`; its exit code, or nil when it was
  killed for taking longer."
  [^ProcessBuilder pb secs]
  (let [p (start! pb)]
    (if (.waitFor p (long secs) TimeUnit/SECONDS)
      (.exitValue p)
      (do (.destroyForcibly p) (.waitFor p) nil))))

;; --- wineserver -----------------------------------------------------------------

(defn- wineserver-command ^ProcessBuilder [rt prefix-dir flag]
  (doto (command [(plaunch/wineserver-binary rt) flag] {:rt rt :env {"WINEPREFIX" prefix-dir}})
    (.redirectOutput ProcessBuilder$Redirect/DISCARD)
    (.redirectError ProcessBuilder$Redirect/DISCARD)))

(defn stop-wineserver!
  "Ends every Wine process in `prefix-dir`: wineserver -k, then -w, each
  bounded (wineserver.rs:26-52)."
  [rt prefix-dir]
  (run-bounded (wineserver-command rt prefix-dir "-k") wineserver-timeout-secs)
  (run-bounded (wineserver-command rt prefix-dir "-w") wineserver-timeout-secs)
  nil)

(defn wineserver-wait
  "A started `wineserver -w`: it ends when nothing runs in `prefix-dir` any
  more, which is when a launch has ended (wineserver.rs:61-69)."
  ^Process [rt prefix-dir]
  (start! (wineserver-command rt prefix-dir "-w")))

;; --- the prefix -----------------------------------------------------------------

(defn- dir? [p] (.isDirectory (io/file (str p))))
(defn- slurp-or-nil [p] (let [f (io/file (str p))] (when (.isFile f) (slurp f))))

(defn- run-wineboot! [rt prefix-dir]
  (.mkdirs (io/file prefix-dir))
  (let [out (File/createTempFile "aether-wineboot" ".log")
        pb  (doto (command [(plaunch/wine-binary rt) "wineboot" "-u"]
                           {:rt rt :env {"WINEPREFIX" prefix-dir "WINEDLLOVERRIDES" "mscoree=d;mshtml=d"
                                         "WINEDEBUG" "-all"}})
              (.redirectErrorStream true)
              (.redirectOutput out))]
    (try
      (let [code (run-bounded pb proton-init-timeout-secs)
            text (slurp out)]
        (cond
          (= 0 code) nil
          (prefix/missing-32-bit? text)
          (fail :missing-32-bit "wine needs a 32-bit runtime: install lib32-glibc and lib32-gcc-libs")
          :else (fail :wineboot (str "wineboot exited " (or code "(timed out)") ": " text))))
      (finally (.delete out)))))

(defn- run-proton-init!
  "`<rt>/proton run cmd /c exit` into compat/ (init.rs run_proton_init): DXVK,
  vkd3d-proton, the redistributables and Steam's bridge. Proton's knobs the
  host has set are left out, so the prefix is set up from the runtime alone."
  [rt compat steam-client app-id]
  (.mkdirs (io/file compat))
  (let [log  (io/file compat prefix/proton-init-log)
        app  (str (or app-id 0))
        pb   (doto (command [(join rt "proton") "run" "cmd" "/c" "exit"]
                            {:env {"STEAM_COMPAT_DATA_PATH" compat "STEAM_COMPAT_CLIENT_INSTALL_PATH" steam-client
                                   "SteamAppId" app "SteamGameId" app "PROTONFIXES_DISABLE" "1"
                                   "WINEDEBUG" "-all"}
                             :remove (cons "WINEPREFIX" (prefix/inherited-proton-env (keys (host-env))))})
               (.redirectErrorStream true)
               (.redirectOutput log))
        code (try (run-bounded pb proton-init-timeout-secs)
                  (catch IOException e
                    (fail :proton-init (str "could not run " (join rt "proton") " (a Python 3 script: is python3 "
                                            "installed?): " (ex-message e)))))]
    (when-not (= 0 code)
      (fail :proton-init (str (join rt "proton") " run "
                              (if code (str "exited " code) (str "did not finish within " proton-init-timeout-secs " s"))
                              (prefix/log-tail (str log) (or (slurp-or-nil log) "")))))))

(defn ensure!
  "Sets up the prefix `session` names under `home` the way `init` says, unless
  it already is (prefix/init.rs:98-160), and returns its directory. `rt` must
  be a verified GE-Proton runtime. :wineboot is set up once; {:proton
  {:steam-client :app-id}} again whenever another runtime, or this one from
  another path, set it up."
  [home rt session init]
  (let [tag        (runtime/verify-ge rt)
        prefix-dir (prefix/prefix-dir home session init)]
    (if (= :wineboot init)
      (when-not (prefix/initialised? prefix-dir dir?)
        (run-wineboot! rt prefix-dir))
      (let [compat (str (.getParent (io/file prefix-dir)))
            {:keys [steam-client app-id]} (:proton init)]
        (when (prefix/needs-setup? init {:initialised? (prefix/initialised? prefix-dir dir?)
                                         :version (slurp-or-nil (join compat "version"))
                                         :config-info (slurp-or-nil (join compat "config_info"))
                                         :tag tag :runtime rt})
          (when-not (dir? steam-client)
            (fail :proton-init (str "the Steam client directory " steam-client
                                    " (STEAM_COMPAT_CLIENT_INSTALL_PATH) does not exist")))
          (run-proton-init! rt compat steam-client app-id)
          (when-not (prefix/initialised? prefix-dir dir?)
            (fail :proton-init (str "proton exited cleanly but " prefix-dir " has no drive_c/windows/system32"))))))
    prefix-dir))

;; --- the lock -------------------------------------------------------------------

(defonce ^:private held
  ;; prefixes this JVM has locked: a FileChannel lock is per process, and a
  ;; second tryLock in one JVM throws rather than failing
  (atom #{}))

(defn lock!
  "An exclusive lock on `prefix-dir` for one launch (prefix/mod.rs:126-154),
  retried for 500 ms; {::error :busy} when another launch holds it. Returns a
  fn that releases it. (A FileChannel lock is fcntl, not flock: it doesn't
  exclude a Rust director on the same prefix.)"
  [prefix-dir]
  (let [k        (str prefix-dir)
        deadline (+ (System/currentTimeMillis) 500)
        busy     #(fail :busy (str "the Wine prefix " prefix-dir " is in use by another launch"))
        claim    #(let [[old] (swap-vals! held conj k)] (not (contains? old k)))]
    ;; this JVM's own launches first: a second tryLock here would throw
    (loop [] (when-not (claim)
               (if (< (System/currentTimeMillis) deadline) (do (Thread/sleep 20) (recur)) (busy))))
    (try
      (let [f  (doto (io/file prefix-dir prefix/lock-file) (-> .getParentFile .mkdirs))
            ch (FileChannel/open (.toPath f) (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                                            StandardOpenOption/WRITE]))
            lk (loop []
                 (or (.tryLock ch)
                     (if (< (System/currentTimeMillis) deadline)
                       (do (Thread/sleep 20) (recur))
                       (do (.close ch) (busy)))))]
        (fn release []
          (try (.release ^FileLock lk) (catch Exception _))
          (.close ch)
          (swap! held disj k)))
      (catch Throwable t (swap! held disj k) (throw t)))))

;; --- links ----------------------------------------------------------------------

(defn read-manifest
  "The link paths <prefix>/.aether-vfs-links lists."
  [prefix-dir]
  (set (remove str/blank? (some-> (slurp-or-nil (join prefix-dir prefix/link-manifest)) str/split-lines))))

(defn- write-manifest! [prefix-dir links]
  (let [f   (io/file prefix-dir prefix/link-manifest)
        tmp (io/file prefix-dir (str prefix/link-manifest ".tmp"))]
    (spit tmp (apply str (map #(str % "\n") (sort links))))
    (Files/move (.toPath tmp) (.toPath f)
                ^"[Ljava.nio.file.CopyOption;" (into-array java.nio.file.CopyOption
                                                            [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                                             java.nio.file.StandardCopyOption/ATOMIC_MOVE]))))

(defn- kind [^File f]
  (cond (Files/isSymbolicLink (.toPath f)) :symlink
        (.isDirectory f) :dir
        :else :file))

(defn listing
  "The directory listings link-plan reads, {host-dir {name kind}}, for every
  directory under `prefix-dir`'s drive_c that the C: `locations` pass
  through: each component matched as Wine does (ASCII case-insensitively),
  links followed by the filesystem itself."
  [prefix-dir locations]
  (let [list-dir (fn [d] (into {} (for [^File f (or (.listFiles (io/file d)) [])] [(.getName f) (kind f)])))
        lower    #(str/lower-case %)]
    (reduce (fn [acc loc]
              (loop [acc acc dir (join prefix-dir "drive_c") [c & more] (prefix/parse-location loc)]
                (if-not (and c (dir? dir))
                  acc
                  (let [entries (or (get acc dir) (list-dir dir))
                        acc     (assoc acc dir entries)
                        found   (or (when (contains? entries c) c)
                                    (first (filter #(= (lower c) (lower %)) (sort (keys entries)))))]
                    (if (and found more)
                      (recur acc (join dir found) more)
                      acc)))))
            {} locations)))

(defn link-session!
  "Links `session-dir` at <prefix>/drive_c/vfs-session, replacing a link an
  earlier launch left (session/proton/mod.rs link_into_prefix): the one link
  gives state/, stage/ and root/ their C:\\vfs-session\\… names."
  [{:keys [link target]}]
  (let [p (path link)]
    (.mkdirs (.getParentFile (io/file link)))
    (when (exists-nofollow? link)
      (when-not (symlink? link)
        (fail :bad-location (str link " exists in the prefix and is not a link aether-vfs made")))
      (Files/delete p))
    (Files/createSymbolicLink p (path target) (make-array FileAttribute 0))))

(defn link!
  "Carries out one link-plan step (prefix/links.rs:124-175): the missing
  parents, the backing dir, the replaced link gone, the symlink, then the
  manifest; the symlink is undone if the manifest can't be written."
  [prefix-dir {:keys [mkdirs backing link replace]}]
  (doseq [d mkdirs] (.mkdirs (io/file d)))
  (.mkdirs (io/file backing))
  (when replace (Files/deleteIfExists (path replace)))
  (Files/createSymbolicLink (path link) (path backing) (make-array FileAttribute 0))
  (try (write-manifest! prefix-dir (-> (read-manifest prefix-dir) (disj replace) (conj link)))
       (catch Exception e (Files/deleteIfExists (path link)) (throw e))))

(defn unlink!
  "Removes a link link! made, if it is still a listed link to `backing`
  (prefix/links.rs:187-208); a listed path that is no longer a link is
  delisted, a repointed one is left alone."
  [prefix-dir {:keys [link backing]}]
  (let [listed (read-manifest prefix-dir)]
    (when (contains? listed link)
      (cond (not (symlink? link))
            (write-manifest! prefix-dir (disj listed link))
            (= (str (Files/readSymbolicLink (path link))) (str backing))
            (do (Files/delete (path link)) (write-manifest! prefix-dir (disj listed link)))))))

;; --- the spawn ------------------------------------------------------------------

(defn spawn!
  "Starts wine for a proton.launch/plan: its program and argv in a process
  group of its own, with the plan's env applied over the cleaned host env
  and its stale names removed, output appended to `log-file` (or ours)."
  ^Process [{:keys [program argv env remove]} log-file]
  (let [pb (command (cons program argv) {:env env :remove remove})]
    (if log-file
      (do (.mkdirs (.getParentFile (io/file log-file)))
          (doto pb (.redirectErrorStream true) (.redirectOutput (ProcessBuilder$Redirect/appendTo (io/file log-file)))))
      (doto pb (.redirectOutput ProcessBuilder$Redirect/INHERIT) (.redirectError ProcessBuilder$Redirect/INHERIT)))
    (start! pb)))
