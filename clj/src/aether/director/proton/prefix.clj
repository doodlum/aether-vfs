(ns aether.director.proton.prefix
  "A session's Wine prefix, the pure half (vfs-proton/src/prefix/*): where it
  lives, whether it needs setting up, and how the session's directories get
  C:\\ names inside it.

  A Wine process can only name what is under one of its drives, so the
  session's directories are symlinked into the prefix's drive_c rather than
  given drive letters (Z: stays mapped to /). Locations are matched ASCII
  case-insensitively, as Wine resolves them. Every path here is a string:
  Linux paths are never java.nio.file.Path, which on Windows would not even
  call /srv/x absolute.

  The effectful half comes later, over these functions:

    (ensure! home runtime session init)
      ;; prefix/init.rs:98-136. verify-ge first; :wineboot runs
      ;; `<rt>/files/bin/wine wineboot -u` (WINEPREFIX, mscoree=d;mshtml=d,
      ;; WINEDEBUG=-all, the runtime lib env, own process group; output naming
      ;; ld-linux.so.2 is missing-32-bit?) unless initialised?;
      ;; {:proton {:steam-client :app-id}} runs `<rt>/proton run cmd /c exit`
      ;; with STEAM_COMPAT_DATA_PATH=<session>/compat, the client path,
      ;; SteamAppId/SteamGameId (0 when none), PROTONFIXES_DISABLE=1,
      ;; WINEDEBUG=-all, without WINEPREFIX or inherited-proton-env, bounded at
      ;; 300 s, logged to compat/aether-proton-init.log; only when needs-setup?.
      ;; => the prefix dir
    (lock! prefix-dir)
      ;; prefix/mod.rs:126-154. An exclusive lock on <prefix>/.aether-vfs.lock,
      ;; retried for 500 ms, then {:prefix/error :prefix/busy}. FileChannel
      ;; locks are fcntl, not flock, and per JVM: keep an in-JVM registry too.
    (link! prefix-dir step)
      ;; prefix/links.rs:124-175, one link-plan step: mkdirs, create the
      ;; backing dir, remove :replace, symlink :link -> :backing, rewrite the
      ;; manifest (temp file + rename); undo the link if the manifest write fails.
    (unlink! prefix-dir link expected-target)
      ;; prefix/links.rs:187-208. Only a listed link still pointing at
      ;; expected-target is removed; a listed path that is no longer a symlink
      ;; is delisted; a repointed one stays listed.
    (stop-wineserver! runtime prefix-dir)
      ;; prefix/wineserver.rs:26-52. `wineserver -k` then `-w`, WINEPREFIX set,
      ;; each bounded at 30 s; needed before deleting a prefix.
    (wineserver-wait runtime prefix-dir)
      ;; prefix/wineserver.rs:61-69. `wineserver -w`, unbounded: a Process that
      ;; ends when nothing runs in the prefix any more."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def link-manifest
  "The file in a prefix listing every root link aether-vfs made there, one
  host path per line (prefix/links.rs:51). A symlink at a root location that
  is not listed is someone else's, and is refused like a real directory."
  ".aether-vfs-links")

(def lock-file "The per-launch lock file in a prefix (prefix/mod.rs:132)." ".aether-vfs.lock")

(def proton-init-log
  "The log Proton's prefix setup writes, inside <session>/compat (prefix/init.rs:76)."
  "aether-proton-init.log")

(defn- fail [kind msg data]
  (throw (ex-info msg (assoc data :prefix/error kind))))

(defn- join
  "`base` and `parts` joined with /, `base`'s trailing slashes dropped."
  [base & parts]
  (str/join "/" (cons (str/replace base #"/+$" "") parts)))

(defn- plain-component?
  "One path component that cannot leave its parent: not empty, absolute, or
  holding a separator or `..` (layout.rs:107-117)."
  [^String s]
  (not (or (str/blank? s) (str/includes? s "/") (str/includes? s "\\") (str/includes? s "..")
           (re-find #"^[A-Za-z]:" s))))

(defn prefix-dir
  "Where `session`'s prefix lives under `sessions` (the home's sessions/ dir)
  for `init`, without creating anything: <session>/prefix for :wineboot,
  <session>/compat/pfx for {:proton …} (prefix/init.rs:81-89). `session`
  must be one plain component."
  [sessions session init]
  (when-not (plain-component? session)
    (fail :prefix/bad-session (str "session id " (pr-str session) " must be one plain path component")
          {:session session}))
  (if (= :wineboot init)
    (join sessions session "prefix")
    (join sessions session "compat" "pfx")))

(defn initialised?
  "Whether `prefix` has been set up: drive_c/windows/system32 is a directory
  (prefix/init.rs:241-247). `dir?` answers for a path; a set works in tests."
  [prefix dir?]
  (boolean (dir? (join prefix "drive_c" "windows" "system32"))))

(defn prefix-version
  "The runtime tag Proton recorded in compat/version, trimmed; nil when
  empty or absent (prefix/init.rs:157-160)."
  [raw]
  (when raw (not-empty (str/trim raw))))

(defn needs-setup?
  "Whether `init`'s setup must run (prefix/init.rs:98-153). A :wineboot prefix
  is set up once. A Proton prefix again when its compat/version is not `tag`,
  or its config_info (when there is one) does not name `runtime`'s files dir:
  a home moved elsewhere leaves the prefix's DLL links pointing at the old one.
  `state`: :initialised? :version (raw text or nil) :config-info (raw or nil)
  :tag (the runtime's verified tag) :runtime (its absolute path)."
  [init {:keys [initialised? version config-info tag runtime]}]
  (cond
    (not initialised?) true
    (= :wineboot init) false
    :else (not (and (= tag (prefix-version version))
                    (or (nil? config-info) (str/includes? config-info (join runtime "files")))))))

(defn inherited-proton-env
  "The inherited names Proton's prefix setup must not see: WINEDLLOVERRIDES and
  every PROTON_* (prefix/init.rs:222-231)."
  [names]
  (filterv #(or (= "WINEDLLOVERRIDES" %) (str/starts-with? % "PROTON_")) names))

(defn missing-32-bit?
  "Whether wineboot's output says no 32-bit runtime is installed: the wine
  launcher probes for ld-linux.so.2 even under win64 (prefix/init.rs:274)."
  [output]
  (str/includes? output "ld-linux.so.2"))

(defn log-tail
  "The last 20 lines of `text`, the log at `path`, to follow an error message
  (prefix/init.rs:234-239)."
  [path text]
  (let [lines (str/split-lines text)]
    (str "; log " path ":\n" (str/join "\n" (take-last 20 lines)))))

;; --- path forms -------------------------------------------------------------

(defn- bad-location [location why & [data]]
  (fail :prefix/bad-location (str "bad root location " location ": " why) (assoc data :location location)))

(defn parse-location
  "A root location (C:\\…, either separator, either case of c) as the
  components under drive_c it names (prefix/links.rs:62-81). Empty and `.`
  components are dropped. Refused, naming `location`: another drive, a host,
  relative or UNC path, the drive root, and any `..`."
  [location]
  (let [norm (str/replace location "/" "\\")
        rest (cond (str/starts-with? norm "C:\\") (subs norm 3)
                   (str/starts-with? norm "c:\\") (subs norm 3)
                   :else (bad-location location "a root location must be on drive C: (C:\\...)"))
        comps (into [] (remove #{"" "."}) (str/split rest #"\\"))]
    (cond (empty? comps)           (bad-location location "the drive root itself cannot be a root location")
          (some #{".."} comps)     (bad-location location "'..' is not allowed")
          :else comps)))

(defn windows-path
  "The C:\\ form of `host`, a path under `prefix`'s drive_c; nil for anything
  else, which has no C: form and must not be given one (prefix/mod.rs:164-177).
  Repeated separators and `.` are dropped; `..` is nil."
  [prefix host]
  (let [drive-c (join prefix "drive_c")
        host    (str/replace host #"/+" "/")]
    (when (or (= host drive-c) (str/starts-with? host (str drive-c "/")))
      (let [comps (remove #{"" "."} (str/split (subs host (count drive-c)) #"/"))]
        (when-not (some #{".."} comps)
          (apply str "C:" (map #(str "\\" %) comps)))))))

(defn join-wine
  "`rel`'s components appended to `base`, a C:\\… path, with Wine's separator
  (session/proton/mod.rs:1046-1064). Anything but a plain component is refused,
  not normalized: `..`, a rooted or drive-lettered `rel`, a leading `.`. This is
  the name the child opens, and normalizing would point it somewhere nobody
  asked for. Interior `.` and empty components are dropped, as Path does."
  [base rel]
  (let [refuse (fn [what] (fail :prefix/bad-join (str rel " cannot be named under " base ": " what
                                                      " is not a plain path component")
                                {:base base :rel rel}))
        parts  (str/split rel #"[/\\]")]
    (cond (re-find #"^[/\\]" rel)  (refuse "a root")
          (= "." (first parts))   (refuse "'.'"))
    (reduce (fn [out c]
              (cond (#{"" "."} c)                (str out)
                    (= ".." c)                   (refuse "'..'")
                    (re-find #"^[A-Za-z]:" c)    (refuse (str "drive " c))
                    :else                        (str out "\\" c)))
            base parts)))

(defn windows-absolute?
  "Whether `s` is an absolute Windows path: X:\\ or X:/, or UNC (vfs-embed image.rs:24-29)."
  [s]
  (boolean (or (re-find #"^[A-Za-z]:[\\/]" s) (str/starts-with? s "\\\\") (str/starts-with? s "//"))))

(defn join-location
  "`vpath` (either separator) under `location`, with Wine's separator (vfs-embed image.rs:126-129)."
  [location vpath]
  (str (str/replace location #"[\\/]+$" "") "\\" (str/replace vpath "/" "\\")))

(defn wine-cwd
  "The working directory a launch starts `target` (C:\\ form) in
  (session/proton/mod.rs:1003-1020): `requested` as given when it is a Windows
  absolute path, joined onto root 0's location `root0` when relative, and
  target's own directory when nil (the drive root keeps its backslash). An
  empty or `..`-bearing `requested` is refused."
  [requested root0 target]
  (cond
    (nil? requested)
    (let [i (max (or (str/last-index-of target "\\") -1) (or (str/last-index-of target "/") -1))]
      (cond (neg? i)                             root0
            (str/ends-with? (subs target 0 i) ":") (str (subs target 0 i) "\\")
            :else                                (subs target 0 i)))

    (str/blank? requested)
    (fail :prefix/bad-cwd "cwd is empty: leave it unset for the image's own directory" {:cwd requested})

    (some #{".."} (str/split requested #"[\\/]"))
    (fail :prefix/bad-cwd (str "cwd " (pr-str requested) " contains '..'; name the directory directly")
          {:cwd requested})

    (windows-absolute? requested) requested
    :else (join-location root0 (str/replace requested #"^[\\/]+|[\\/]+$" ""))))

;; --- root links ---------------------------------------------------------------

(defn- ascii-lower ^String [^String s]
  (let [sb (StringBuilder. s)]
    (dotimes [i (.length sb)]
      (let [c (.charAt sb i)] (when (<= (int \A) (int c) (int \Z)) (.setCharAt sb i (char (+ (int c) 32))))))
    (str sb)))

(defn- find-entry
  "The name in `entries` ({name kind}) that is `name` under Wine's rule,
  preferring an exact match (prefix/links.rs:26-44). Ties among inexact ones
  go to the first in sorted order: a directory listing promises none."
  [entries name]
  (if (contains? entries name)
    name
    (let [want (ascii-lower name)]
      (first (filter #(= want (ascii-lower %)) (sort (keys entries)))))))

(defn- resolve-links
  "`path` with the links this plan made replaced by their targets, so a root
  nested in another's location is matched against the outer backing dir."
  [links path]
  (if-let [[l t] (first (filter (fn [[l _]] (or (= path l) (str/starts-with? path (str l "/"))))
                                (sort-by (comp - count key) links)))]
    (recur (dissoc links l) (str t (subs path (count l))))
    path))

(defn- plan-one
  "One root's step (prefix/links.rs:124-175) against the simulated `state`:
  [step state']."
  [prefix {:keys [entries links manifest]} {:keys [id location backing] :as root}]
  (let [comps     (try (parse-location location)
                       (catch clojure.lang.ExceptionInfo e
                         (throw (ex-info (str "root " id ": " (ex-message e)) (assoc (ex-data e) :root id)))))
        listed    (fn [entries dir] (get entries (resolve-links links dir) {}))
        refuse    (fn [why] (fail :prefix/bad-location (str "root " id ": bad root location " location ": " why)
                                  {:location location :root id}))
        [parent mkdirs entries]
        (reduce (fn [[parent mkdirs entries] c]
                  (if-let [found (find-entry (listed entries parent) c)]
                    [(join parent found) mkdirs entries]
                    (let [dir (join parent c)]
                      [dir (conj mkdirs dir) (assoc-in entries [(resolve-links links parent) c] :dir)])))
                [(join prefix "drive_c") [] entries]
                (pop comps))
        last-c    (peek comps)
        existing  (find-entry (listed entries parent) last-c)
        at        (when existing (join parent existing))
        _         (when existing
                    (cond (not= :symlink (get (listed entries parent) existing))
                          (refuse (str at " already exists in the prefix as a real file or directory; a root "
                                       "cannot be placed over it"))
                          (not (contains? manifest at))
                          (refuse (str at " already exists in the prefix as a symlink aether-vfs did not create "
                                       "(it is not listed in " (join prefix link-manifest) "); a root cannot "
                                       "replace it: remove it yourself if it is stale"))))
        link      (join parent last-c)
        real      (resolve-links links parent)]
    [(assoc (select-keys root [:id :location :backing]) :mkdirs mkdirs :link link :replace at)
     {:entries  (-> entries (update real #(dissoc (or % {}) existing)) (assoc-in [real last-c] :symlink))
      :links    (assoc links link backing)
      :manifest (-> manifest (disj at) (conj link))}]))

(defn link-plan
  "The links that give each of `roots` ({:id :location :backing}) its C:\\
  location inside `prefix`, as ordered steps for link!:
    {:id :location :backing :mkdirs [dir…] :link path :replace path-or-nil}
  Shallowest location first, stably (session/proton/mod.rs:431-454), so a root
  nested in another's location lands inside the outer root's backing dir.

  `listing` is the directory contents as data, {host-dir {name kind}} with kind
  :dir, :file or :symlink, for drive_c and anything below it (and for backing
  dirs a nested root goes through); a dir missing from it is empty. Each
  component is matched ASCII case-insensitively against it (prefix/links.rs:
  26-44), and only missing parents are created, in the declared spelling.
  `manifest` is the set of link paths link-manifest lists: only those are
  replaced. A real file or directory, or a symlink not listed, at a location
  is refused ({:prefix/error :prefix/bad-location :root id})."
  [prefix roots listing manifest]
  (let [depth #(count (remove str/blank? (str/split % #"[\\/]")))]
    (first (reduce (fn [[steps state] root]
                     (let [[step state] (plan-one prefix state root)] [(conj steps step) state]))
                   [[] {:entries listing :links {} :manifest (set manifest)}]
                   (sort-by (comp depth :location) roots)))))
