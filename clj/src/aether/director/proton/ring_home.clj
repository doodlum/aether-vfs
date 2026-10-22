(ns aether.director.proton.ring-home
  "The file-backed ring's home on Linux (vfs-embed session/proton/ring.rs): a
  tmpfs under $XDG_RUNTIME_DIR when there is a private one, so the ring's
  64 MiB of constantly written shared pages never reach disk. Otherwise the
  ring is a plain file in the state dir.

  The directory is checked, not trusted: XDG_RUNTIME_DIR is only an
  environment variable, and set to /tmp it is a place where another user can
  make aether-vfs first and swap the file. So the runtime dir, aether-vfs and
  the ring's own dir must each be this user's with no group or other access.
  /dev/shm is not considered.

  This namespace decides, over given data; the effects come later:

    (ring-in-memory! state-dir named)
      ;; ring.rs:101-136. Gathers the inputs (System/getenv, slurp of
      ;; /proc/self/mountinfo, the canonical runtime and state dirs, the uid as
      ;; the owner of /proc/self, stat with NOFOLLOW_LINKS), takes
      ;; ring-in-memory's plan, makes each of :dirs 0700 and re-checks it with
      ;; private-dir?, removes any old :file, creates it CREATE_NEW 0600, and
      ;; symlinks `named` to it. Any failure undoes and returns nil: the ring
      ;; then lives at `named` itself.
    (remove-memory-ring! file named)
      ;; ring.rs:141-152. Best effort: the file, its dir, and `named` if it
      ;; still links to the file.

  The file is sparse, so a nearly full runtime dir can fault on page touch;
  reserving pages needs posix_fallocate (ring.rs:94-99), not done."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(defn- under?
  "Whether `dir` is `point` or below it, component-wise (Path::starts_with)."
  [^String dir ^String point]
  (let [point (if (= "/" point) point (str/replace point #"/+$" ""))]
    (or (= dir point)
        (str/starts-with? dir (if (str/ends-with? point "/") point (str point "/"))))))

(defn memory-fs?
  "Whether `dir` (canonical) is on a filesystem whose pages are never written
  to disk, by `mountinfo` (the text of /proc/self/mountinfo): the mount whose
  mount point is the longest prefix of `dir` decides, a later line winning a
  tie, and only tmpfs and ramfs count (ring.rs:25-49). A dir no line places is
  not in memory."
  [mountinfo dir]
  (let [best (reduce (fn [best line]
                       ;; id parent maj:min root MOUNTPOINT opts [optional…] - FSTYPE source superopts
                       (let [[left right] (str/split line #" - " 2)
                             point        (some-> left (str/split #" ") (get 4) (str/replace "\\040" " "))]
                         (if (and right point (under? dir point) (or (nil? best) (>= (count point) (first best))))
                           [(count point) (contains? #{"tmpfs" "ramfs"} (first (str/split right #" ")))]
                           best)))
                     nil
                     (str/split-lines mountinfo))]
    (boolean (second best))))

(defn private-dir?
  "Whether `stat` (of a path, not following links: {:dir? :symlink? :uid :mode})
  says a directory owned by `uid` with no access for group or others
  (ring.rs:57-66). nil, a link or a file is not."
  [{:keys [dir? symlink? uid mode]} my-uid]
  (boolean (and dir? (not symlink?) (= uid my-uid) (zero? (bit-and (long mode) 077)))))

(defn ring-dir
  "The ring's private dir under `runtime`: aether-vfs/ring-<16 hex>, named for
  the canonical `state-dir`, so two spellings of one state dir share a ring
  name and a session that died without stopping is replaced, not accumulated
  (ring.rs:87-92). The hash is the first 8 bytes of SHA-256; Rust's
  DefaultHasher differs, and only has to agree with itself."
  [runtime ^String state-dir]
  (let [h (.digest (MessageDigest/getInstance "SHA-256") (.getBytes state-dir StandardCharsets/UTF_8))]
    (str (str/replace runtime #"/+$" "") "/aether-vfs/ring-"
         (apply str (for [i (range 8)] (format "%02x" (bit-and (aget h i) 0xff)))))))

(defn ring-in-memory
  "Where the ring should live, given:
    :xdg-runtime-dir  $XDG_RUNTIME_DIR, canonical (nil when unset)
    :mountinfo        /proc/self/mountinfo's text
    :uid              this process's uid
    :stat             path -> stat data (see private-dir?), or nil when absent
    :state-dir        the canonical state dir
    :file-name        the ring's name in the state dir (ring.bin)
  {:dirs [dir…] :file path} when the runtime dir is absolute, in memory and
  private (ring.rs:105-108): each of :dirs is to be made 0700 and must pass
  private-dir? once made, and :file is to be created exclusively, 0600. nil
  when the ring belongs in the state dir."
  [{:keys [xdg-runtime-dir mountinfo uid stat state-dir file-name]}]
  (when (and (not (str/blank? xdg-runtime-dir))
             (str/starts-with? xdg-runtime-dir "/")
             (memory-fs? (or mountinfo "") xdg-runtime-dir)
             (private-dir? (stat xdg-runtime-dir) uid))
    (let [dir (ring-dir xdg-runtime-dir state-dir)]
      {:dirs [(str (str/replace xdg-runtime-dir #"/+$" "") "/aether-vfs") dir]
       :file (str dir "/" file-name)})))
