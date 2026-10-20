(ns aether.director.provider.tree
  "An index provider: content that never changes while served (Stored zip
  entries, memory-mapped files), indexed once by folded vpath. Serving is a
  lookup; a directory's READDIR reply is encoded once, here; a read is one
  copy from the content's MemorySegment into the arena.

  Read-only: every mutation answers ST_READ_ONLY. Put a writable layer over
  it (aether.director.provider.compose/overlay) for writes."
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.provider :as prov])
  (:import (aether.director.provider Entry Stat)
           (java.lang.foreign Arena MemorySegment)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.file Files LinkOption OpenOption Path StandardOpenOption)
           (java.util HashMap)))

(deftype File [^Stat stat ^MemorySegment data])
(deftype Dir [^bytes listing entries])          ; the encoded READDIR reply, and its Entries

(deftype SegmentHandle [^Stat stat ^MemorySegment data]
  prov/Handle
  (handle-stat [_] stat)
  (immutable? [_] true)
  (read-into [_ dst dst-off pos len]
    (let [n (max 0 (min (long len) (- (.-size stat) (long pos))))]
      (when (pos? n) (MemorySegment/copy data (long pos) ^MemorySegment dst (long dst-off) n))
      n))
  (write-from [_ _ _ _ _] p/read-only)
  (truncate! [_ _] p/read-only)
  (close! [_] nil))

(deftype DirHandle []
  prov/Handle
  (handle-stat [_] prov/dir-stat)
  (immutable? [_] true)
  (read-into [_ _ _ _ _] p/is-dir)
  (write-from [_ _ _ _ _] p/is-dir)
  (truncate! [_ _] p/is-dir)
  (close! [_] nil))

(def dir-handle (DirHandle.))

(deftype Tree [^HashMap nodes                   ; folded vpath -> File | Dir; the root is ""
               ^HashMap spellings]              ; folded vpath -> the path as the content spells it
  prov/Provider
  (stat [_ vp]
    (let [n (.get nodes vp)]
      (cond (instance? File n) (.-stat ^File n)
            (instance? Dir n)  prov/dir-stat)))
  (list-dir [_ vp]
    (let [n (.get nodes vp)]
      (cond (instance? Dir n) (.-entries ^Dir n)
            (nil? n)          nil
            :else             p/not-a-directory)))
  (spelling [_ vp] (.get spellings vp))
  (open [_ vp flags]
    (let [n (.get nodes (path/fold vp))]
      (cond
        (not (zero? (bit-and (long flags) (bit-or p/open-write p/open-create p/open-trunc p/open-append))))
        (if (and (nil? n) (zero? (bit-and (long flags) p/open-create))) p/not-found p/read-only)
        (instance? File n) (SegmentHandle. (.-stat ^File n) (.-data ^File n))
        (instance? Dir n)  dir-handle
        :else              p/not-found)))
  (mkdir! [_ vp] (if (.containsKey nodes (path/fold vp)) p/exists p/read-only))
  (delete! [_ vp] (if (.containsKey nodes (path/fold vp)) p/read-only p/not-found))
  (rename! [_ from _] (if (.containsKey nodes (path/fold from)) p/read-only p/not-found))

  prov/Listing
  (listing-bytes [_ vp]
    (let [n (.get nodes vp)] (when (instance? Dir n) (.-listing ^Dir n)))))

(defn lookup
  "The File or Dir at folded `vpath`, or nil."
  [^Tree t ^String vpath] (.get ^HashMap (.-nodes t) vpath))

(defn build
  "An index of `files`, a seq of [path ^MemorySegment data] with paths as the
  content spells them (`/`, any case). Parent directories are implied. A later
  file at the same path (ignoring case) wins; the first spelling of a path is
  the one kept."
  [files]
  (let [children (HashMap.)                     ; folded dir -> {folded name -> Entry}, in order
        add!     (fn [^String dir ^String folded ^Entry e replace?]
                   (let [^java.util.Map m (or (.get children dir)
                                              (let [m (java.util.LinkedHashMap.)] (.put children dir m) m))]
                     (if-let [^Entry old (.get m folded)]
                       (when replace? (.put m folded (Entry. (.-name old) (.-stat e))))
                       (.put m folded e))))
        nodes    (HashMap.)
        spelled  (HashMap.)]
    (.put children "" (java.util.LinkedHashMap.))
    (.put spelled "" "")
    (doseq [[p ^MemorySegment data] files
            :let [p  (path/normalize p)
                  fp (path/fold p)
                  st (Stat. false (.byteSize data) 0)]]
      (.put nodes fp (File. st data))
      (loop [q p]
        (when-not (= "" q)
          (.putIfAbsent spelled (path/fold q) q)
          (recur (path/parent q))))
      (add! (path/fold (path/parent p)) (path/fold (path/file-name p)) (Entry. (path/file-name p) st) true)
      (loop [d (path/parent p)]
        (when-not (= "" d)
          (add! (path/fold (path/parent d)) (path/fold (path/file-name d)) (Entry. (path/file-name d) prov/dir-stat) false)
          (recur (path/parent d)))))
    (doseq [[dir ^java.util.Map m] children
            :let [entries (vec (vals m))]]
      (.put nodes dir (Dir. (prov/encode-listing entries) entries)))
    (Tree. nodes spelled)))

;; --- content sources -----------------------------------------------------------

(defn- map-file
  "The file's bytes, memory-mapped read-only in `arena`."
  ^MemorySegment [^Arena arena ^Path p]
  (with-open [ch (FileChannel/open p (into-array OpenOption [StandardOpenOption/READ]))]
    (let [n (.size ch)]
      (if (zero? n)
        (MemorySegment/ofArray (byte-array 0))
        (.map ch FileChannel$MapMode/READ_ONLY 0 n arena)))))

(defn dir-files
  "[[path data] …] for every file under `dir`, each memory-mapped in `arena`,
  so a read is one copy from the mapping into the arena with no system call.
  Mappings live as long as `arena` (Arena/ofShared, closed when serving ends).
  Empty directories aren't served (only paths implied by files)."
  [^Arena arena dir]
  (let [root (.toAbsolutePath (.toPath (java.io.File. (str dir))))]
    (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (vec (for [^Path p (iterator-seq (.iterator paths))
                 :when (Files/isRegularFile p (make-array LinkOption 0))]
             [(.replace (str (.relativize root p)) \\ \/) (map-file arena p)])))))

(defn from-dir
  "An index of the directory `dir` (see dir-files)."
  [^Arena arena dir]
  (build (dir-files arena dir)))
