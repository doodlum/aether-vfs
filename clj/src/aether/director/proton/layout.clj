(ns aether.director.proton.layout
  "The home everything Proton-side lives under (vfs-proton/src/layout.rs):

    <home>/runtimes/<tag>/    one extracted GE-Proton runtime each
    <home>/downloads/         tarballs not yet extracted
    <home>/sessions/<id>/     one session's private Wine prefix each

  The home is a string, and so is every path built from it: these are Linux
  paths, joined with /, and never java.nio.file.Path (a Linux path isn't
  absolute on Windows). Tags and session ids reach here from a GitHub release
  name or a caller, so the try- functions refuse one that could leave its
  parent directory.")

(set! *warn-on-reflection* true)

(defn home
  "The home from the environment `env` (a map of name to value), as
  Root::from_env (layout.rs:32-51): $VFS_HOME, else $XDG_DATA_HOME/aether-vfs,
  else $HOME/.local/share/aether-vfs, else (Windows, for tests and
  development) %LOCALAPPDATA%\\aether-vfs. nil when none is set."
  [env]
  (let [v #(get env %)]
    (cond (v "VFS_HOME")      (v "VFS_HOME")
          (v "XDG_DATA_HOME") (str (v "XDG_DATA_HOME") "/aether-vfs")
          (v "HOME")          (str (v "HOME") "/.local/share/aether-vfs")
          (v "LOCALAPPDATA")  (str (v "LOCALAPPDATA") "\\aether-vfs"))))

(defn join
  "`base` and `parts` joined with /, without doubling a / that ends `base`."
  [base & parts]
  (reduce (fn [^String a p] (if (.endsWith a "/") (str a p) (str a "/" p))) (str base) parts))

(defn runtimes  "<home>/runtimes (layout.rs:60-62)."  [home] (join home "runtimes"))
(defn downloads "<home>/downloads (layout.rs:65-67)." [home] (join home "downloads"))
(defn sessions  "<home>/sessions (layout.rs:71-73)."  [home] (join home "sessions"))

(defn runtime-dir
  "<home>/runtimes/<tag>, unchecked: for a tag already validated, or a literal
  (layout.rs:79-81). A tag from outside goes through try-runtime-dir."
  [home tag]
  (join (runtimes home) tag))

(defn valid-component?
  "Whether `s` is one plain path component: not empty, no / or \\, no .., not
  absolute (validate_component, layout.rs:107-117)."
  [s]
  (and (string? s) (seq s)
       (not (re-find #"[/\\]|\.\." s))))

(defn- check [s]
  (when-not (valid-component? s)
    (throw (ex-info (str "invalid tag: " (pr-str s)) {::error :invalid-tag :tag s})))
  s)

(defn try-runtime-dir
  "<home>/runtimes/<tag>, or an ex-info (::error :invalid-tag) for a tag that
  could leave runtimes/ (Root::try_runtime_dir, layout.rs:88-91)."
  [home tag]
  (runtime-dir home (check tag)))

(defn try-session-dir
  "<home>/sessions/<id>, or an ex-info (::error :invalid-tag) for an id that
  could leave sessions/ (Root::try_session_dir, layout.rs:98-101)."
  [home session]
  (join (sessions home) (check session)))
