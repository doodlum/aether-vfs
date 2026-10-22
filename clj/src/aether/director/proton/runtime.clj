(ns aether.director.proton.runtime
  "GE-Proton runtimes under the home (vfs-proton/src/runtime.rs): the GE gate,
  the library environment a direct `files/bin/wine` run needs, tag ordering
  and discovery.

  The gate is a hard error, never a fallback: PROTONPATH silently means stock
  UMU-Proton when it is unset or wrong, so every runtime handed back has passed
  verify-ge. Errors are ex-info with ::error :missing, :unreadable, :not-ge or
  :missing-lib."
  (:require [aether.director.proton.layout :as layout]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io FileNotFoundException IOException)
           (java.nio.file NoSuchFileException)))

(set! *warn-on-reflection* true)

(defn- fail [kind msg & [data]] (throw (ex-info msg (assoc data ::error kind))))

(defn version-tag
  "The GE-Proton tag in a `version` file's text: the first whitespace-separated
  token that starts with GE-Proton, or nil (runtime.rs:60-77)."
  [text]
  (first (filter #(str/starts-with? % "GE-Proton") (str/split (str/trim (str text)) #"\s+"))))

(defn check-ffmpeg
  "nil, or an ex-info (::error :missing-lib) when `dir` has Wine's FFmpeg
  demuxer, files/lib/wine/x86_64-unix/winedmo.so, but no libavformat.so.* in
  files/lib/x86_64-linux-gnu: winedmo.so links it with no RPATH, and without it
  Media Foundation fails every mp4 with 0xc000007a (check_ffmpeg,
  runtime.rs:82-100)."
  [dir]
  (when (.exists (io/file dir "files/lib/wine/x86_64-unix/winedmo.so"))
    (when-not (some #(str/starts-with? % "libavformat.so.")
                    (.list (io/file dir "files/lib/x86_64-linux-gnu")))
      (fail :missing-lib (str "incomplete runtime: files/lib/wine/x86_64-unix/winedmo.so is present but "
                              "libavformat.so.* is missing from files/lib/x86_64-linux-gnu")
            {:lib "libavformat.so.*" :dir (str dir)}))))

(defn verify-ge
  "The tag of the GE-Proton runtime at `dir`, read from dir/version, after
  check-ffmpeg; or an ex-info: ::error :missing (no version file), :unreadable,
  or :not-ge with :version, the file's trimmed text (verify_ge,
  runtime.rs:60-77)."
  [dir]
  (let [f    (io/file dir "version")
        text (try (slurp f)
                  (catch FileNotFoundException e
                    (if (.exists f)
                      (fail :unreadable (str "version file unreadable: " (ex-message e)) {:dir (str dir)})
                      (fail :missing "no version file: not an installed runtime" {:dir (str dir)})))
                  (catch NoSuchFileException _
                    (fail :missing "no version file: not an installed runtime" {:dir (str dir)}))
                  (catch IOException e
                    (fail :unreadable (str "version file unreadable: " (ex-message e)) {:dir (str dir)})))
        tag  (or (version-tag text)
                 (fail :not-ge (str "not a GE-Proton runtime: version says " (pr-str (str/trim text)))
                       {:version (str/trim text) :dir (str dir)}))]
    (check-ffmpeg dir)
    tag))

(defn runtime-lib-env
  "What the `proton` script's init_wine gives Wine, as a map: LD_LIBRARY_PATH =
  <rt>/files/lib/{x86_64,i386}-linux-gnu[:ld], ORIG_LD_LIBRARY_PATH = ld (or
  \"\") unless orig-ld is set, and WINEDLLPATH = <rt>/files/lib/{vkd3d,wine}
  [:dllpath]. The inherited values are arguments, nil when unset; `runtime`
  is an absolute Linux path string (runtime_lib_env, runtime.rs:109-139)."
  [runtime ld orig-ld dllpath]
  (let [lib    (fn [sub] (layout/join runtime "files" "lib" sub))
        append (fn [s v] (if (seq v) (str s ":" v) s))]
    (cond-> {"LD_LIBRARY_PATH" (append (str (lib "x86_64-linux-gnu") ":" (lib "i386-linux-gnu")) ld)
             "WINEDLLPATH"     (append (str (lib "vkd3d") ":" (lib "wine")) dllpath)}
      (nil? orig-ld) (assoc "ORIG_LD_LIBRARY_PATH" (or ld "")))))

(defn parse-tag
  "[N M] for a GE-ProtonN-M tag, or nil (parse_tag, runtime.rs:164-168)."
  [tag]
  (when-let [[_ n m] (and (string? tag) (re-matches #"GE-Proton(\d+)-(\d+)" tag))]
    (let [n (parse-long n) m (parse-long m)] (when (and n m) [n m]))))

(defn cmp-tags
  "A comparator for GE-ProtonN-M tags, numeric by [N M], not lexical:
  GE-Proton11-6 is after GE-Proton9-1. A tag that doesn't parse sorts below
  every one that does, so junk never wins \"newest\" (cmp_tags,
  runtime.rs:155-162)."
  [a b]
  (let [a (parse-tag a) b (parse-tag b)]
    (cond (and a b) (compare a b)
          a 1
          b -1
          :else 0)))

(defn installed-dirs
  "[tag dir] for every verified runtime under <home>/runtimes, newest first.
  The dir is the one found, never re-joined from the tag (the two needn't
  agree); a directory that fails verify-ge is skipped, and a symlinked one is
  followed. No runtimes dir is none; one that can't be read is an IOException (installed_dirs, runtime.rs:190-211)."
  [home]
  (let [d     (io/file (layout/runtimes home))
        names (when (.exists d)
                (or (.list d) (throw (java.io.IOException. (str "can't read " d)))))]
    (->> (for [n (sort names)
               :let [dir (layout/join (layout/runtimes home) n)]
               :when (.isDirectory (io/file dir))
               :let [tag (try (verify-ge dir) (catch clojure.lang.ExceptionInfo _ nil))]
               :when tag]
           [tag dir])
         (sort (fn [[a] [b]] (cmp-tags b a)))
         vec)))

(defn installed
  "The tags of installed-dirs, newest first (installed, runtime.rs:175-180)."
  [home]
  (mapv first (installed-dirs home)))

(defn newest
  "The directory of the newest verified runtime under the home, or nil: the
  runtime a launch uses (newest_installed, runtime.rs:218-223)."
  [home]
  (second (first (installed-dirs home))))
