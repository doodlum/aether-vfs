(ns aether.director.proton.install
  "Installing a GE-Proton release (vfs-proton/src/install.rs), without the
  network: the sha512sum and digest checks, a tar extractor with traversal
  guards, the GE gate on the extracted tree, and the rename as a plan.

  Three orderings carry an install, and the plan keeps them:

  1. the digest is fetched and parsed before the 533 MB body, and the body is
     streamed, never buffered;
  2. a digest mismatch deletes the partial file;
  3. the rename into runtimes/<tag> is the last step: extraction happens in a
     .tmp- sibling, so a half-extracted tree is never visible as an install.

  And one gate: verify-ge runs on the extracted tree, because a tarball can
  match its publisher's digest and still be stock Valve Proton.

  The JDK has no tar reader, so read-tar is one: ustar (with its prefix
  field), GNU long names and long links (L, K), PAX path, linkpath and size
  (x), base-256 sizes, checked header checksums. That covers what GNU tar
  writes for GE-Proton's .tar.gz and what the tar crate reads.

  Errors are ex-info with ::error :bad-sha512-line, :digest, :archive,
  :traversal, :not-ge or :io; filesystem failures are IOExceptions."
  (:require [aether.director.proton.layout :as layout]
            [aether.director.proton.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io BufferedInputStream BufferedOutputStream File FileOutputStream InputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file CopyOption Files LinkOption Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute PosixFilePermission)
           (java.security MessageDigest)
           (java.util HexFormat)
           (java.util.zip GZIPInputStream)))

(set! *warn-on-reflection* true)

(defn- fail [kind msg & [data]] (throw (ex-info msg (assoc data ::error kind))))

(def ^:private chunk-size (* 256 1024))

;; --- the digest ---------------------------------------------------------------------------

(defn parse-sha512sum
  "The lower-case digest in a publisher .sha512sum body, \"<128 hex>  <file>\":
  the first token of the first non-blank line, which must be exactly 128 hex
  digits, so that a truncated body or a 404 page never becomes an expected
  digest (parse_sha512sum, install.rs:122-133). An ex-info (::error
  :bad-sha512-line, :body) otherwise."
  [body]
  (let [bad   #(fail :bad-sha512-line (str "malformed sha512sum line: " (pr-str (apply str (take 120 (str body)))))
                     {:body (apply str (take 120 (str body)))})
        line  (or (first (remove str/blank? (str/split-lines (str body)))) (bad))
        token (first (str/split (str/trim line) #"\s+"))]
    (if (re-matches #"[0-9a-fA-F]{128}" token) (str/lower-case token) (bad))))

(defn sha512-hex
  "The lower-case SHA-512 of everything left in `in`, read in 256 KiB chunks:
  this runs over a 533 MB tarball (verify_digest, install.rs:139-157)."
  [^InputStream in]
  (let [md (MessageDigest/getInstance "SHA-512") buf (byte-array chunk-size)]
    (loop []
      (let [n (.read in buf)]
        (when (pos? n) (.update md buf 0 n))
        (when-not (neg? n) (recur))))
    (.formatHex (HexFormat/of) (.digest md))))

(defn verify-digest
  "nil when `in`'s bytes hash to `expected` (hex, any case), else an ex-info
  (::error :digest, :expected, :actual) (verify_digest, install.rs:139-157)."
  [in expected]
  (let [actual (sha512-hex in) expected (str/lower-case expected)]
    (when-not (= actual expected)
      (fail :digest (str "sha512 mismatch: expected " expected ", got " actual) {:expected expected :actual actual}))))

;; --- member paths and link targets ------------------------------------------------------

(defn- drive? [s] (boolean (re-find #"^[A-Za-z]:" s)))
(defn- climbs? [component] (some #{".."} (str/split component #"\\")))

(defn member-path
  "A tar member's name as a relative path of plain components joined with /
  (\"\" for the archive root), with . and empty components dropped; an
  ex-info (::error :traversal) for one with a .. component, a root or a
  drive (safe_member_path, install.rs:314-329). \\ is not a separator in a
  Linux name, but a .. between them is refused too, for a Windows host."
  [raw]
  (let [refuse #(fail :traversal (str "member " raw " escapes the target directory") {:member raw})]
    (when (or (str/starts-with? raw "/") (str/starts-with? raw "\\") (drive? raw)) (refuse))
    (->> (str/split raw #"/")
         (remove #(or (= "" %) (= "." %)))
         (map #(if (climbs? %) (refuse) %))
         (str/join "/"))))

(defn resolve-link
  "`target` resolved lexically against `anchor` (both relative to the
  extraction root), as a /-joined relative path, or nil when it is absolute or
  climbs above the root (resolve_lexically, install.rs:334-355). Lexical,
  because the tree is still being created; containment, not \"no ..\", because
  GE-Proton's tree has relative links that climb before they descend."
  [anchor target]
  (when-not (or (str/starts-with? target "/") (str/starts-with? target "\\") (drive? target))
    (loop [stack (vec (remove #(or (= "" %) (= "." %)) (str/split anchor #"/")))
           [c & more :as cs] (str/split target #"/")]
      (cond (empty? cs)                 (str/join "/" stack)
            (or (= "" c) (= "." c))     (recur stack more)
            (= ".." c)                  (when (seq stack) (recur (pop stack) more))
            (climbs? c)                 nil
            :else                       (recur (conj stack c) more)))))

;; --- reading tar --------------------------------------------------------------------------

(defn- read-fully
  "Fills b[off, off+len) from `in`; the count read, short only at EOF."
  ^long [^InputStream in ^bytes b ^long off ^long len]
  (loop [n 0]
    (if (< n len)
      (let [r (.read in b (+ off n) (- len n))]
        (if (neg? r) n (recur (+ n r))))
      n)))

(defn- truncated [] (fail :archive "bad archive: truncated"))

(defn- skip-fully [^InputStream in ^long n]
  (let [buf (byte-array (min n chunk-size))]
    (loop [left n]
      (when (pos? left)
        (let [r (.read in buf 0 (int (min left (alength buf))))]
          (when (neg? r) (truncated))
          (recur (- left r)))))))

(defn- drain [^InputStream in]
  (let [buf (byte-array chunk-size)] (while (not (neg? (.read in buf))))))

(defn- cstr
  "The NUL-terminated UTF-8 string in b[off, off+len)."
  [^bytes b ^long off ^long len]
  (let [end (loop [i off] (if (and (< i (+ off len)) (not (zero? (aget b i)))) (recur (inc i)) i))]
    (String. b (int off) (int (- end off)) StandardCharsets/UTF_8)))

(defn- octal ^long [^bytes h ^long off ^long len]
  (let [s (str/trim (str/replace (String. h (int off) (int len) StandardCharsets/ISO_8859_1) "\u0000" " "))]
    (cond (= "" s) 0
          (re-matches #"[0-7]+" s) (Long/parseLong s 8)
          :else (fail :archive (str "bad archive: bad octal field " (pr-str s))))))

(defn- numeric
  "A numeric header field: octal, or GNU base-256 when the top bit is set."
  ^long [^bytes h ^long off ^long len]
  (if (bit-test (aget h off) 7)
    (try (reduce (fn [^long acc ^long i] (Math/addExact (Math/multiplyExact acc 256) (bit-and 0xff (aget h i))))
                 (bit-and 0x7f (aget h off)) (range (inc off) (+ off len)))
         (catch ArithmeticException _ (fail :archive "bad archive: a base-256 size past 2^63")))
    (octal h off len)))

(defn- check-sum [^bytes h]
  (let [want (octal h 148 8)
        [u s] (loop [i 0 u 0 s 0]
                (if (< i 512)
                  (let [b (if (< 147 i 156) 32 (long (aget h i)))]
                    (recur (inc i) (+ u (bit-and 0xff b)) (+ s b)))
                  [u s]))]
    (when-not (or (= want u) (= want s)) (fail :archive "bad archive: header checksum mismatch"))))

(defn- read-meta
  "The data of a metadata member (a long name, PAX records): at most 1 MiB."
  ^bytes [^InputStream in ^long size]
  (when (> size (* 1024 1024)) (fail :archive "bad archive: metadata member too large"))
  (let [b (byte-array size)]
    (when (< (read-fully in b 0 size) size) (truncated))
    (skip-fully in (mod (- size) 512))
    b))

(defn- parse-pax
  "PAX extended-header records, \"<len> <key>=<value>\\n\", as a map."
  [^bytes d]
  (loop [pos 0 m {}]
    (if (>= pos (alength d))
      m
      (let [sp  (loop [i pos] (cond (>= i (alength d)) (fail :archive "bad archive: bad pax record")
                                    (= 32 (aget d i)) i
                                    :else (recur (inc i))))
            len (long (or (parse-long (String. d (int pos) (int (- sp pos)) StandardCharsets/US_ASCII))
                          (fail :archive "bad archive: bad pax record length")))
            end (+ pos len)]
        (when (or (<= len 0) (> end (alength d)) (not= 10 (aget d (dec end))))
          (fail :archive "bad archive: bad pax record"))
        (let [rec (String. d (int (inc sp)) (int (- end sp 2)) StandardCharsets/UTF_8)
              [k v] (str/split rec #"=" 2)]
          (recur end (assoc m k v)))))))

(defn- bounded
  "A view of the next `n` bytes of `in`; EOF before them is :archive."
  ^InputStream [^InputStream in ^long n]
  (let [left (long-array [n])]
    (proxy [InputStream] []
      (read
        ([]
         (if (pos? (aget left 0))
           (let [b (.read in)]
             (when (neg? b) (truncated))
             (aset left 0 (dec (aget left 0)))
             b)
           -1))
        ([^bytes b] (.read ^InputStream this b 0 (alength b)))
        ([^bytes b off len]
         (let [want (min (long len) (aget left 0))]
           (cond (zero? (long len)) 0
                 (zero? want) -1
                 :else (let [r (.read in b (int off) (int want))]
                         (when (neg? r) (truncated))
                         (aset left 0 (- (aget left 0) r))
                         r))))))))

(defn- entry-kind [type ^String name ^long size]
  (case type
    (\0 \u0000 \7) (if (and (.endsWith name "/") (zero? size)) :dir :file)
    \5 :dir
    \2 :symlink
    \1 :hardlink
    :other))

(defn read-tar
  "Calls (f entry data) for each member of the tar stream `in`, in order, with
  `data` an InputStream of the member's bytes (drained afterwards if `f`
  leaves any). An entry is {:name :link :kind :mode :size}, :kind one of :file
  :dir :symlink :hardlink :other; metadata members (L, K, x, g) are folded into
  the member they describe. Ends at a zero block or at EOF between members."
  [^InputStream in f]
  (let [h (byte-array 512)]
    (loop [long-name nil long-link nil pax {}]
      (let [n (read-fully in h 0 512)]
        (cond
          (zero? n) nil
          (< n 512) (truncated)
          (every? zero? h) nil
          :else
          (do (check-sum h)
              (let [type   (char (aget h 156))
                    size   (numeric h 124 12)
                    ustar? (= "ustar\u0000" (String. h 257 6 StandardCharsets/ISO_8859_1))]
                (case type
                  \L (recur (cstr (read-meta in size) 0 size) long-link pax)
                  \K (recur long-name (cstr (read-meta in size) 0 size) pax)
                  \x (recur long-name long-link (merge pax (parse-pax (read-meta in size))))
                  \g (do (read-meta in size) (recur long-name long-link pax))
                  (let [nm    (cstr h 0 100)
                        pre   (when ustar? (cstr h 345 155))
                        name  (or (pax "path") long-name (if (seq pre) (str pre "/" nm) nm))
                        size  (if-let [s (pax "size")]
                                (let [n (parse-long s)]
                                  (if (and n (not (neg? n))) n (fail :archive (str "bad archive: bad pax size " (pr-str s)))))
                                size)
                        data  (bounded in size)]
                    (f {:name name
                        :link (or (pax "linkpath") long-link (cstr h 157 100))
                        :kind (entry-kind type name size)
                        :mode (octal h 100 8)
                        :size size}
                       data)
                    (drain data)
                    (skip-fully in (mod (- size) 512))
                    (recur nil nil {})))))))))
  nil)

;; --- extracting ---------------------------------------------------------------------------

(defn gunzip
  "`in` (a .tar.gz) decompressed, buffered for read-tar."
  ^InputStream [^InputStream in]
  (GZIPInputStream. (BufferedInputStream. in chunk-size) 65536))

(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- within!
  "Refuses `f` (a hard link's source) unless it exists and really resolves
  inside `root` (canonical), as fs::canonicalize does in within (install.rs).
  Not getCanonicalFile, which resolves only the part of a path that exists: a
  source missing below a link that leaves the tree would pass, and
  createLink then fail as an I/O error, not a refusal."
  [^File root ^File f raw]
  (let [real (try (.toRealPath (.toPath f) (make-array LinkOption 0))
                  (catch java.io.IOException _ nil))]
    (when-not (and real (.startsWith ^Path real (.toPath root)))
      (fail :traversal (str "member " raw " reaches outside the archive tree") {:member raw}))))

(defn- contained!
  "Refuses to write `dst` when its nearest existing ancestor really resolves
  outside `root` (canonical). Beyond install.rs: the lexical link checks
  can't see that a link's target climbs through another link."
  [^File root ^File dst raw]
  (let [anc (loop [^File f (.getParentFile dst)]
              (if (or (nil? f) (Files/exists (.toPath f) nofollow)) f (recur (.getParentFile f))))
        c   (when anc (.toPath (.getCanonicalFile ^File anc)))]
    (when-not (and c (.startsWith ^Path c (.toPath root)))
      (fail :traversal (str "member " raw " would be written outside the archive tree") {:member raw}))))

(defn- replace!
  "Removes a non-directory at `dst`, so a member replaces a link rather than
  writing through it, as GNU tar does."
  [^File dst]
  (let [p (.toPath dst)]
    (when (and (Files/exists p nofollow) (not (Files/isDirectory p nofollow)))
      (Files/delete p))))

(def ^:private posix? (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix"))

(defn- set-mode!
  "The member's permission bits, with the owner's rw floored so a mode-0
  header can't make a file its owner can't read (apply_mode, install.rs:364-372)."
  [^File f ^long mode]
  (when posix?
    (let [m     (bit-or (bit-and mode 0777) 0600)
          perms [PosixFilePermission/OTHERS_EXECUTE PosixFilePermission/OTHERS_WRITE PosixFilePermission/OTHERS_READ
                 PosixFilePermission/GROUP_EXECUTE PosixFilePermission/GROUP_WRITE PosixFilePermission/GROUP_READ
                 PosixFilePermission/OWNER_EXECUTE PosixFilePermission/OWNER_WRITE PosixFilePermission/OWNER_READ]]
      (Files/setPosixFilePermissions (.toPath f) (set (keep-indexed (fn [i p] (when (bit-test m i) p)) perms))))))

(defn extract!
  "Unpacks the tar stream `in` (see gunzip) into the directory `into` (made
  if missing) and returns the one top-level directory it made, as a path string
  (extract_tar_gz, install.rs:191-309). Every member is checked before it is
  written: its path by member-path; a symlink's target resolved against the
  link's directory, and a hard link's against the archive root, must stay
  inside the tree (resolve-link); and the directory it lands in must really
  be inside `into`. Device nodes and fifos are skipped. An ex-info
  (::error :archive) for an empty archive, more than one top-level entry, or
  a top-level entry that isn't a directory; :traversal for an escape."
  [in into]
  (let [root (.getCanonicalFile (doto (io/file into) (.mkdirs)))
        top  (volatile! nil)]
    (read-tar in
      (fn [{:keys [name link kind mode]} ^InputStream data]
        (let [rel (member-path name)]
          (when (and (seq rel) (not= :other kind))
            (let [first-c (first (str/split rel #"/"))]
              (cond (nil? @top)         (vreset! top first-c)
                    (not= @top first-c) (fail :archive (str "bad archive: more than one top-level entry: "
                                                            @top " and " first-c))))
            (let [dst (io/file root rel)]
              (contained! root dst name)
              (case kind
                :dir      (do (.mkdirs dst)
                              (when-not (.isDirectory dst) (throw (java.io.IOException. (str "can't create " dst)))))
                (:symlink :hardlink)
                (let [anchor   (if (= :symlink kind) (str/join "/" (butlast (str/split rel #"/"))) "")
                      resolved (or (resolve-link anchor link)
                                   (fail :traversal (str "link " name " targets " link " outside the archive tree")
                                         {:member name :link link}))]
                  (.mkdirs (.getParentFile dst))
                  (replace! dst)
                  (if (= :symlink kind)
                    (Files/createSymbolicLink (.toPath dst) (.toPath (io/file link)) (make-array FileAttribute 0))
                    (let [src (io/file root resolved)]
                      (within! root src name)
                      (Files/createLink (.toPath dst) (.toPath src)))))
                :file     (do (.mkdirs (.getParentFile dst))
                              (replace! dst)
                              (with-open [out (BufferedOutputStream. (FileOutputStream. dst) chunk-size)]
                                (io/copy data out :buffer-size chunk-size))
                              (set-mode! dst mode))))))))
    (let [t (or @top (fail :archive "bad archive: archive is empty"))
          dir (io/file into t)]
      (when-not (.isDirectory dir) (fail :archive (str "bad archive: top-level entry " t " is not a directory")))
      (str dir))))

;; --- the gate, the plan, the rename -------------------------------------------------------

(defn gate
  "The tag of the extracted tree `dir`, or an ex-info (::error :not-ge) when it
  isn't GE-Proton, has no version file, or lacks its FFmpeg; :io when the
  version file can't be read (install_from_partial, install.rs:489-503)."
  [dir]
  (try (runtime/verify-ge dir)
       (catch clojure.lang.ExceptionInfo e
         (case (::runtime/error (ex-data e))
           :missing    (fail :not-ge (str "not a GE-Proton runtime: " dir " has no version file"))
           :unreadable (fail :io (ex-message e))
           (:not-ge :missing-lib) (fail :not-ge (ex-message e) (dissoc (ex-data e) ::runtime/error))
           (throw e)))))

(defn- runtime-dir [home tag]
  (try (layout/try-runtime-dir home tag)
       (catch clojure.lang.ExceptionInfo e (fail :traversal (str "refused path traversal: " (ex-message e)) {:tag tag}))))

(defn partial-path
  "Where the tarball for `tag` is streamed before it is verified:
  <home>/downloads/<tag>.tar.gz.partial. Its length is the download's
  progress (partial_path, install.rs:401-403)."
  [home tag]
  (layout/join (layout/downloads home) (str tag ".tar.gz.partial")))

(defn plan
  "Every path an install of `tag` touches, for process `pid`: :final
  (runtimes/<tag>), :partial, :tmp (the .tmp- sibling extracted into), :aside
  (where an existing runtime is moved), :runtimes and :downloads. The tag is
  validated before anything else, so a bad one (::error :traversal) touches
  nothing (install_release, install.rs:420-465)."
  [home tag pid]
  (let [final (runtime-dir home tag)]
    {:runtimes  (layout/runtimes home)
     :downloads (layout/downloads home)
     :final     final
     :partial   (partial-path home tag)
     :tmp       (layout/join (layout/runtimes home) (str ".tmp-" tag "-" pid))
     :aside     (layout/join (layout/runtimes home) (str ".old-" tag "-" pid))}))

(defn rename-plan
  "The last step as data: {:before :steps :undo :after}, each a vector of
  [:move from to] or [:delete path]. With a runtime already at :final it is
  moved aside first and restored if the move of `top` fails, so a forced
  install can't trade a working runtime for nothing; :before and :after
  failures are ignored (install_from_partial, install.rs:505-532)."
  [{:keys [final aside]} top final-exists?]
  (if final-exists?
    {:before [[:delete aside]]
     :steps  [[:move final aside] [:move top final]]
     :undo   [[:move aside final]]
     :after  [[:delete aside]]}
    {:before [] :steps [[:move top final]] :undo [] :after []}))

(defn- delete-tree! [^File f]
  (when (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f))))
    (run! delete-tree! (.listFiles f)))
  (.delete f))

(defn- run-step! [[op a b]]
  (case op
    :move   (Files/move (.toPath (io/file a)) (.toPath (io/file b))
                        ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    :delete (delete-tree! (io/file a))))

(defn rename!
  "Carries out a rename-plan: :before, then :steps in order; when a step after
  the first fails, :undo, then the failure is rethrown; :after on success."
  [{:keys [before steps undo after]}]
  (doseq [s before] (try (run-step! s) (catch Exception _)))
  (loop [[s & more] steps i 0]
    (when s
      (try (run-step! s)
           (catch Exception e
             (when (pos? i) (doseq [u undo] (try (run-step! u) (catch Exception _))))
             (throw e)))
      (recur more (inc i))))
  (doseq [s after] (try (run-step! s) (catch Exception _))))

(defn installed
  "{:tag :dir :fresh false} when `tag` is already installed and passes
  verify-ge, so an install is a no-op, not a re-download; else nil. An
  ex-info (::error :traversal) for a bad tag (install_release,
  install.rs:427-437)."
  [home tag]
  (let [dir (runtime-dir home tag)]
    (when-let [t (try (runtime/verify-ge dir) (catch clojure.lang.ExceptionInfo _ nil))]
      {:tag t :dir dir :fresh false})))

(defn install-archive!
  "Installs the verified tarball `archive` (a local .tar.gz path) as `tag`
  under `home`: extracts it into the plan's .tmp- dir, gates the tree on
  GE-Proton, renames it into runtimes/<tag> last, and removes the .tmp- dir
  whatever happens. Returns {:tag :dir :fresh true}. Deleting `archive` is
  the caller's (install_release steps 5-8, install.rs:460-538). Options:
  :pid (default this process's)."
  [home tag archive {:keys [pid] :or {pid (.pid (ProcessHandle/current))}}]
  (let [{:keys [final tmp runtimes] :as p} (plan home tag pid)]
    (.mkdirs (io/file runtimes))
    (delete-tree! (io/file tmp))
    (.mkdirs (io/file tmp))
    (try
      (let [top (with-open [in (io/input-stream archive)] (extract! (gunzip in) tmp))]
        (gate top)
        (rename! (rename-plan p top (.exists (io/file final))))
        {:tag tag :dir final :fresh true})
      (finally (delete-tree! (io/file tmp))))))
