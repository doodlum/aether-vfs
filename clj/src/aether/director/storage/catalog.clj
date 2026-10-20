(ns aether.director.storage.catalog
  "The storage catalog: what's in each layer, and the live and cache files,
  as rows in the block store's LMDB environment, so a row and the blocks it
  names commit together.

    layers       name (UTF-8) -> layer id u64
    entries      [layer id u64][folded path] -> v1 | kind u8 | guid 16 | len u64 | mtime i64 | name
    live         live key 16 -> len u64 | mtime i64
    cache_files  cache key 16 -> last access (minutes) u64 | logical bytes u64
    smeta        name -> u64 (next_layer_id)

  (keys big-endian, values little-endian). Paths are vpaths: `/`-separated,
  folded (aether.director.path); the root is the empty path, and an entry's
  name is its last component as it was spelled. Store file ids: a layer
  file's is \"L\" + its GUID, a live file's \"F\" + its key, a cache file's
  \"C\" + a hash of its source."
  (:require [aether.director.store :as store]
            [aether.director.store.lmdb :as l])
  (:import (aether.director Codec)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)
           (java.security SecureRandom)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def dbs ["layers" "entries" "live" "cache_files" "smeta"])

(def ^:const kind-file 1)
(def ^:const kind-dir 2)

(defn- dbi ^long [store name] (l/dbi (:env store) name))
(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))
(defn- le ^ByteBuffer [n] (.order (ByteBuffer/allocate n) ByteOrder/LITTLE_ENDIAN))

;; --- ids ---------------------------------------------------------------------------

(def ^:private rng (SecureRandom.))

(defn new-guid ^bytes [] (let [b (byte-array 16)] (.nextBytes ^SecureRandom rng b) b))

(defn- prefixed ^bytes [^long tag ^bytes b]
  (let [out (byte-array (inc (alength b)))]
    (aset-byte out 0 (unchecked-byte tag))
    (System/arraycopy b 0 out 1 (alength b))
    out))

(defn layer-file-id ^bytes [^bytes guid] (prefixed (int \L) guid))
(defn live-file-id ^bytes [^bytes key] (prefixed (int \F) key))
(defn cache-file-id ^bytes [^bytes key] (prefixed (int \C) key))

(defn cache-key
  "The 16-byte key of a cache file: a hash of where its content comes from."
  ^bytes [^String source root ^String vpath size mtime]
  (let [s (utf8 source) p (utf8 vpath)
        bb (le (+ 4 (alength s) 8 4 (alength p) 16))]
    (.putInt bb (alength s)) (.put bb s) (.putLong bb (long root))
    (.putInt bb (alength p)) (.put bb p) (.putLong bb (long size)) (.putLong bb (long mtime))
    (Codec/blake3 (MemorySegment/ofArray (.array bb)) 16)))

;; --- entries --------------------------------------------------------------------------

(defn entry-key ^bytes [layer ^String folded]
  (let [p (utf8 folded) bb (ByteBuffer/allocate (+ 8 (alength p)))]
    (.putLong bb (long layer)) (.put bb p) (.array bb)))

(defn- encode-entry ^bytes [{:keys [dir? ^bytes guid len mtime ^String name]}]
  (let [n (utf8 name) bb (le (+ 34 (alength n)))]
    (.put bb (byte 1))
    (.put bb (byte (if dir? kind-dir kind-file)))
    (.put bb (or guid (byte-array 16)))
    (.putLong bb (long (or len 0)))
    (.putLong bb (long (or mtime 0)))
    (.put bb n)
    (.array bb)))

(defn- decode-entry [^MemorySegment v]
  (let [kind (.get v ValueLayout/JAVA_BYTE 1)
        dir? (= kind-dir kind)]
    {:dir?  dir?
     :guid  (when-not dir? (.toArray (.asSlice v 2 16) ValueLayout/JAVA_BYTE))
     :len   (l/le-u64 v 18)
     :mtime (l/le-u64 v 26)
     :name  (String. ^bytes (.toArray (.asSlice v 34) ValueLayout/JAVA_BYTE) StandardCharsets/UTF_8)}))

(defn get-entry
  "Layer `layer`'s entry at folded path `folded`, or nil."
  [store txn layer ^String folded]
  (when-let [v (l/get-seg txn (dbi store "entries") (entry-key layer folded))]
    (decode-entry v)))

(defn put-entry! [store txn layer ^String folded entry]
  (l/put! txn (dbi store "entries") (entry-key layer folded) (encode-entry entry)))

(defn del-entry! [store txn layer ^String folded]
  (l/del! txn (dbi store "entries") (entry-key layer folded)))

(defn children
  "[[folded-path entry] …] directly under folded directory `dir`, skipping
  over subtrees (a path after its child's name and a /, seek past them)."
  [store txn layer ^String dir]
  (let [base   (if (= "" dir) "" (str dir "/"))
        prefix (entry-key layer base)
        more?  (l/prefix? prefix)
        np     (alength prefix)]
    (with-open [c (aether.director.Lmdb/cursor (long txn) (int (dbi store "entries")))]
      (loop [ok (.seek c prefix) out []]
        (if (and ok (more? (.key c)))
          (let [^MemorySegment k (.key c)
                rest (String. ^bytes (.toArray (.asSlice k np) ValueLayout/JAVA_BYTE) StandardCharsets/UTF_8)
                slash (.indexOf rest "/")]
            (cond
              (= "" rest) (recur (.next c) out)                      ; the directory itself
              (neg? slash) (let [out (conj out [(str base rest) (decode-entry (.val c))])]  ; before the cursor moves
                             (recur (.next c) out))
              ;; deeper: jump past the child's subtree ('0' sorts right after '/')
              :else (recur (.seek c (entry-key layer (str base (subs rest 0 slash) "0"))) out)))
          out)))))

(defn subtree
  "[[folded-path entry] …] of `dir` and everything under it."
  [store txn layer ^String dir]
  (let [self (when-let [e (get-entry store txn layer dir)] [[dir e]])
        prefix (entry-key layer (if (= "" dir) "" (str dir "/")))
        np (alength (entry-key layer ""))]
    (into (vec self)
          (l/reduce-range txn (dbi store "entries") prefix (l/prefix? prefix)
                          (fn [acc ^MemorySegment k v]
                            (let [p (String. ^bytes (.toArray (.asSlice k np) ValueLayout/JAVA_BYTE) StandardCharsets/UTF_8)]
                              (if (= p dir) acc (conj acc [p (decode-entry v)]))))
                          []))))

;; --- layers ------------------------------------------------------------------------------

(defn- get-smeta [store txn ^String k]
  (when-let [v (l/get-seg txn (dbi store "smeta") (utf8 k))] (l/le-u64 v 0)))

(defn layer-id
  "The id of layer `name`, or nil."
  [store txn ^String name]
  (when-let [v (l/get-seg txn (dbi store "layers") (utf8 name))] (l/le-u64 v 0)))

(defn create-layer!
  "In a write txn: layer `name`'s id, creating it (with its root) if needed."
  [store txn ^String name]
  (or (layer-id store txn name)
      (let [id (long (or (get-smeta store txn "next_layer_id") 1))]
        (l/put! txn (dbi store "smeta") (utf8 "next_layer_id") (.array (.putLong (le 8) (inc id))))
        (l/put! txn (dbi store "layers") (utf8 name) (.array (.putLong (le 8) id)))
        (put-entry! store txn id "" {:dir? true :name ""})
        id)))

(defn layer-names [store txn]
  (l/reduce-range txn (dbi store "layers") nil (constantly true)
                  (fn [acc ^MemorySegment k _] (conj acc (String. ^bytes (.toArray k ValueLayout/JAVA_BYTE) StandardCharsets/UTF_8)))
                  []))

(defn drop-layer!
  "In a write txn: removes layer `name`'s row (its entries are the caller's)."
  [store txn ^String name]
  (l/del! txn (dbi store "layers") (utf8 name)))

;; --- live files and cache files -------------------------------------------------------------

(defn get-live [store txn ^bytes key]
  (when-let [v (l/get-seg txn (dbi store "live") key)]
    {:len (l/le-u64 v 0) :mtime (l/le-u64 v 8)}))

(defn put-live! [store txn ^bytes key {:keys [len mtime]}]
  (l/put! txn (dbi store "live") key (.array (doto (le 16) (.putLong (long len)) (.putLong (long mtime))))))

(defn del-live! [store txn ^bytes key] (l/del! txn (dbi store "live") key))

(defn live-keys [store txn]
  (l/reduce-range txn (dbi store "live") nil (constantly true)
                  (fn [acc ^MemorySegment k _] (conj acc (.toArray k ValueLayout/JAVA_BYTE))) []))

(defn get-cache-file [store txn ^bytes key]
  (when-let [v (l/get-seg txn (dbi store "cache_files") key)]
    {:access (l/le-u64 v 0) :bytes (l/le-u64 v 8)}))

(defn put-cache-file! [store txn ^bytes key {:keys [access bytes]}]
  (l/put! txn (dbi store "cache_files") key (.array (doto (le 16) (.putLong (long access)) (.putLong (long bytes))))))

(defn del-cache-file! [store txn ^bytes key] (l/del! txn (dbi store "cache_files") key))

(defn cache-files
  "[[key {:access :bytes}] …], every cache file."
  [store txn]
  (l/reduce-range txn (dbi store "cache_files") nil (constantly true)
                  (fn [acc ^MemorySegment k ^MemorySegment v]
                    (conj acc [(.toArray k ValueLayout/JAVA_BYTE) {:access (l/le-u64 v 0) :bytes (l/le-u64 v 8)}]))
                  []))
