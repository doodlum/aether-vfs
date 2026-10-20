(ns aether.director.provider.disk
  "A disk provider: a real directory, readable and writable. Reads and writes
  are positional FileChannel calls straight into or out of the ring's memory
  (no lock shared between handles, no intermediate buffer). Windows file
  systems are case-insensitive, so a folded vpath resolves as it is; the
  spelling comes from the file system.

  Semantics follow vfs-compose's DiskProvider: creating a file makes missing
  parents; MKDIR makes parents and is OK on an existing directory; deleting a
  non-empty directory, or renaming onto one, is an I/O error; rename replaces
  a file."
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.provider :as prov])
  (:import (aether.director.provider Entry Stat)
           (java.io IOException)
           (java.lang.foreign MemorySegment)
           (java.nio.channels FileChannel)
           (java.nio.file AccessDeniedException CopyOption DirectoryNotEmptyException
                          FileAlreadyExistsException Files LinkOption NoSuchFileException
                          NotDirectoryException OpenOption Path StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)))

(set! *warn-on-reflection* true)

(defn- status-of
  "A status for an I/O failure."
  ^long [^Throwable e]
  (condp instance? e
    NoSuchFileException         p/not-found
    FileAlreadyExistsException  p/exists
    DirectoryNotEmptyException  p/io-error
    NotDirectoryException       p/not-a-directory
    AccessDeniedException       p/read-only
    p/io-error))

(defmacro ^:private guarded
  "Runs body; an IOException becomes its status."
  [& body]
  `(try ~@body (catch IOException e# (status-of e#))))

(def ^:private no-links (make-array LinkOption 0))

(defn- stat-of ^Stat [^BasicFileAttributes a]
  (if (.isDirectory a)
    prov/dir-stat
    (Stat. false (.size a) (quot (.toMillis (.lastModifiedTime a)) 1000))))

(defn- attrs ^BasicFileAttributes [^Path f]
  (try (Files/readAttributes f BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-links)
       (catch IOException _ nil)))

(deftype DiskHandle [^FileChannel ch]
  prov/Handle
  (handle-stat [_] (Stat. false (.size ch) 0))
  (immutable? [_] false)
  (read-into [_ dst dst-off pos len]
    (let [buf (.asByteBuffer (.asSlice ^MemorySegment dst (long dst-off) (long len)))]
      (try
        (loop [at (long pos)]
          (if (.hasRemaining buf)
            (let [n (.read ch buf at)]
              (if (neg? n) (.position buf) (recur (+ at n))))
            (.position buf)))
        (catch IOException e (status-of e)))))
  (write-from [_ src src-off pos len]
    (let [buf (.asByteBuffer (.asSlice ^MemorySegment src (long src-off) (long len)))]
      (guarded
       (loop [at (long pos)]
         (if (.hasRemaining buf)
           (recur (+ at (.write ch buf at)))
           (.position buf))))))
  (truncate! [_ size]
    (guarded
     (let [size (long size) cur (.size ch)]
       (cond (< size cur) (.truncate ch size)
             (> size cur) (.write ch (java.nio.ByteBuffer/allocate 1) (dec size)))
       p/ok)))
  (close! [_] (.close ch)))

(deftype DirHandle [^Stat stat]
  prov/Handle
  (handle-stat [_] stat)
  (immutable? [_] false)
  (read-into [_ _ _ _ _] p/is-dir)
  (write-from [_ _ _ _ _] p/is-dir)
  (truncate! [_ _] p/is-dir)
  (close! [_] nil))

(defn- options
  "FileChannel options for the ring's OPEN flags."
  ^"[Ljava.nio.file.OpenOption;" [^long flags]
  (let [write? (not (zero? (bit-and flags (bit-or p/open-write p/open-append p/open-trunc))))]
    (into-array OpenOption
                (cond-> [StandardOpenOption/READ]
                  write?                                        (conj StandardOpenOption/WRITE)
                  (not (zero? (bit-and flags p/open-trunc)))    (conj StandardOpenOption/TRUNCATE_EXISTING)
                  (and (not (zero? (bit-and flags p/open-create)))
                       (not (zero? (bit-and flags p/open-excl)))) (conj StandardOpenOption/CREATE_NEW)
                  (and (not (zero? (bit-and flags p/open-create)))
                       (zero? (bit-and flags p/open-excl)))     (conj StandardOpenOption/CREATE)))))

(deftype Disk [^Path root]
  prov/Provider
  (stat [_ vp] (some-> (attrs (.resolve root ^String vp)) stat-of))
  (list-dir [_ vp]
    (let [d (.resolve root ^String vp)]
      (try
        (with-open [s (Files/newDirectoryStream d)]
          (vec (for [^Path f s :let [a (attrs f)] :when a]
                 (Entry. (str (.getFileName f)) (stat-of a)))))
        (catch NoSuchFileException _ nil)
        (catch NotDirectoryException _ p/not-a-directory)
        (catch IOException e (status-of e)))))
  (spelling [_ vp]
    (try (let [real (.toRealPath (.resolve root ^String vp) ^"[Ljava.nio.file.LinkOption;" no-links)]
           (.replace (str (.relativize (.toRealPath root ^"[Ljava.nio.file.LinkOption;" no-links) real)) \\ \/))
         (catch IOException _ nil)))
  (open [_ vp flags]
    (let [f (.resolve root ^String vp)
          a (attrs f)]
      (cond
        (and a (.isDirectory a))
        (if (zero? (bit-and (long flags) (bit-or p/open-write p/open-trunc p/open-append))) (DirHandle. prov/dir-stat) p/is-dir)
        (and a (not (zero? (bit-and (long flags) p/open-create))) (not (zero? (bit-and (long flags) p/open-excl))))
        p/exists
        :else
        (guarded
         ;; creating makes missing parents, as vfs-compose's DiskProvider does
         (when (and (nil? a) (not (zero? (bit-and (long flags) p/open-create))))
           (Files/createDirectories (.getParent f) (make-array FileAttribute 0)))
         (DiskHandle. (FileChannel/open f (options flags)))))))
  (mkdir! [_ vp]
    ;; with parents; an existing directory is fine, an existing file is not
    (let [d (.resolve root ^String vp)
          a (attrs d)]
      (cond (and a (.isDirectory a)) p/ok
            a                         p/io-error
            :else (guarded (Files/createDirectories d (make-array FileAttribute 0)) p/ok))))
  (delete! [_ vp]
    (guarded (Files/delete (.resolve root ^String vp)) p/ok))
  (rename! [_ from to]
    (guarded (Files/move (.resolve root ^String from) (.resolve root ^String to)
                         (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
             p/ok)))

(defn disk
  "A provider serving the directory `dir` (created if missing)."
  [dir]
  (let [root (.toAbsolutePath (.toPath (java.io.File. (str dir))))]
    (Files/createDirectories root (make-array FileAttribute 0))
    (Disk. root)))
