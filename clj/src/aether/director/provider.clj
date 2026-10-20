(ns aether.director.provider
  "The provider protocol: where a root's files come from. The director serves
  each root from one provider, usually composed (aether.director.provider.compose):
  indexed archives and folders under a writable overlay, say.

  Paths are vpaths: root-relative, `/`-separated, normalized and case-folded
  (aether.director.path); the root is \"\". A provider answers for folded
  paths and remembers how they are spelled.

  Failures are status codes (aether.director.proto), returned as negative
  longs, never thrown on the hot path: a game probes missing files
  constantly. A missing path is nil from `stat`, `list-dir` and `spelling`.

  Reads go straight into the ring's arena: `read-into` writes into a
  MemorySegment, so a provider backed by memory or a mapping is one copy."
  (:require [aether.director.path :as path]
            [aether.director.proto :as p])
  (:import (java.io ByteArrayOutputStream)
           (java.lang.foreign MemorySegment)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)))

;; What `stat` returns. Indexed providers keep one per path, so a stat allocates
;; nothing. `mtime` is unix seconds (0 when unknown: the shim synthesizes times).
(deftype Stat [^boolean dir? ^long size ^long mtime])

(def dir-stat (Stat. true 0 0))

;; A directory entry: the name as spelled, and its stat.
(deftype Entry [^String name ^Stat stat])

;; Lookups (stat, list-dir, spelling) take folded vpaths: the shim folds them.
;; Operations that may create a name (open, mkdir!, rename!) and delete! take
;; the path as the caller spelled it, normalized; providers fold for lookups
;; and keep the spelling for what they create.
(defprotocol Provider
  (stat [p vpath]
    "Stat of `vpath`, or nil when it doesn't exist.")
  (list-dir [p vpath]
    "The entries of directory `vpath` (a seq of Entry, names unique ignoring
    case), nil when it doesn't exist, or a negative status (not-a-directory).")
  (spelling [p vpath]
    "`vpath` as the provider spells it, or nil when it doesn't exist.")
  (open [p vpath flags]
    "A Handle on `vpath`, or a negative status. `flags` are the ring's OPEN
    flags (read, write, create, excl, trunc, append).")
  (mkdir! [p vpath] "Creates directory `vpath`. Returns a status.")
  (delete! [p vpath] "Deletes file or empty directory `vpath`. Returns a status.")
  (rename! [p from to] "Renames `from` to `to`, replacing a file at `to`. Returns a status."))

(defprotocol Handle
  (handle-stat [h] "The open file's Stat (its current size).")
  (immutable? [h] "Whether the content behind this handle can never change.")
  (read-into [h ^MemorySegment dst dst-off pos len]
    "Copies up to `len` bytes from `pos` into `dst` at `dst-off`. Returns the
    count: short only at end of file.")
  (write-from [h ^MemorySegment src src-off pos len]
    "Writes `len` bytes from `src` at `src-off` to `pos`. Returns the count or
    a negative status.")
  (truncate! [h size] "Sets the file's length. Returns a status.")
  (close! [h] "Releases the handle."))

;; --- READDIR replies ---------------------------------------------------------

(defn encode-listing
  "A READDIR reply: count:u32, then per entry name_len:u32 name is_dir:u8
  size:u64 mtime:i64 (little-endian)."
  ^bytes [entries]
  (let [out (ByteArrayOutputStream.)
        le  (fn [^long n ^long v]
              (let [b (.order (ByteBuffer/allocate n) ByteOrder/LITTLE_ENDIAN)]
                (if (= n 4) (.putInt b (int v)) (.putLong b v))
                (.write out (.array b))))]
    (le 4 (count entries))
    (doseq [^Entry e entries
            :let [nb (.getBytes ^String (.-name e) StandardCharsets/UTF_8)
                  ^Stat st (.-stat e)]]
      (le 4 (alength nb))
      (.write out nb)
      (.write out (int (if (.-dir? st) 1 0)))
      (le 8 (.-size st))
      (le 8 (.-mtime st)))
    (.toByteArray out)))

(defprotocol Listing
  "Optional: a provider that keeps READDIR replies encoded (an index that never
  changes) answers from them instead of encoding per request."
  (listing-bytes [p vpath] "The encoded READDIR reply for `vpath`, or nil."))

(extend-protocol Listing
  Object
  (listing-bytes [_ _] nil))

;; --- walking -----------------------------------------------------------------

(defn sample
  "Up to `n` file and `n` directory vpaths (folded), breadth-first from the
  root: for warm-up requests."
  [prov n]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY "") files [] dirs []]
    (if (or (empty? queue) (and (>= (count files) n) (>= (count dirs) n)))
      {:files files :dirs dirs}
      (let [d       (peek queue)
            entries (let [l (list-dir prov d)] (when (sequential? l) l))
            child   (fn [^Entry e] (let [nm (path/fold (.-name e))] (if (= "" d) nm (str d "/" nm))))
            subdirs (for [^Entry e entries :when (.-dir? ^Stat (.-stat e))] (child e))
            fs      (for [^Entry e entries :when (not (.-dir? ^Stat (.-stat e)))] (child e))]
        (recur (into (pop queue) subdirs)
               (into files (take (- n (count files)) fs))
               (if (< (count dirs) n) (conj dirs d) dirs))))))
