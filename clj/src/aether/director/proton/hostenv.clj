(ns aether.director.proton.hostenv
  "The host environment as Proton, Wine and wineserver should see it
  (vfs-proton/src/hostenv.rs).

  An AppImage's AppRun points PYTHONHOME, PYTHONPATH, PATH, LD_LIBRARY_PATH,
  GTK's module paths and more into the AppImage ($APPDIR), and everything the
  app starts inherits them. GE-Proton's `proton` is a Python script, and with
  PYTHONHOME inside the AppImage the host's Python can't find its standard
  library; Wine would load the AppImage's libraries. So every host program a
  launch starts gets clean-env, and every inherited search path goes through
  inherited. Outside an AppImage (no APPDIR) both change nothing.

  Pure: an environment is a map of name to value, and values are Linux path
  lists, compared as strings.")

(set! *warn-on-reflection* true)

(def appimage-only
  "Variables AppRun (or its GTK hook) sets to values that don't name a path
  inside the AppImage, so clean can't tell them apart (hostenv.rs:20)."
  #{"PYTHONDONTWRITEBYTECODE" "GTK_THEME"})

(defn- components
  "A Linux path as Path::components sees it: [rooted? names], with empty and
  interior . components dropped."
  [^String p]
  (let [parts (vec (.split p "/" -1))
        lead  (first parts)]
    [(= "" lead)
     (into (if (= "." lead) ["."] [])
           (remove #(or (= "" %) (= "." %)))
           parts)]))

(defn appdir
  "The AppImage's mount point from `env`'s APPDIR, when it is absolute and not
  / (a relative path or / would match too much), else nil (hostenv.rs:65-68)."
  [env]
  (when-let [d (get env "APPDIR")]
    (let [[rooted? names] (components d)]
      (when (and rooted? (seq names) (not= "." (first names))) d))))

(defn- inside? [appdir ^String entry]
  (and (seq entry)
       (let [[ra na] (components appdir) [re ne] (components entry)]
         (and (= ra re) (<= (count na) (count ne)) (= na (subvec ne 0 (count na)))))))

(defn clean
  "`value`, a :-separated list, without its entries inside `appdir`: the same
  string when none is, nil when every entry is. Once one is taken out, empty
  entries go too: AppRun appends the original value after a :, which leaves a
  trailing empty entry when there was none (clean, hostenv.rs:82-101)."
  [appdir ^String value]
  (let [entries (vec (.split value ":" -1))]
    (if-not (some #(inside? appdir %) entries)
      value
      (let [kept (remove #(or (= "" %) (inside? appdir %)) entries)]
        (when (seq kept) (apply str (interpose ":" kept)))))))

(defn inherited
  "`env`'s value for `name` without what an AppImage put in it: what a host
  program inherits through clean-env (var_os, hostenv.rs:48-61)."
  [env name]
  (when-let [v (get env name)]
    (if-let [dir (appdir env)]
      (when-not (appimage-only name) (clean dir v))
      v)))

(defn clean-env
  "`env` as a host program inherits it: inside an AppImage, appimage-only
  removed and every value cleaned (a value left empty is removed); outside,
  `env` itself (command, hostenv.rs:24-44)."
  [env]
  (if-let [dir (appdir env)]
    (reduce-kv (fn [m k v]
                 (if-let [c (when-not (appimage-only k) (clean dir v))]
                   (assoc m k c)
                   m))
               {} env)
    env))
