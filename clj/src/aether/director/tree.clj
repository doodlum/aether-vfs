(ns aether.director.tree
  "The content the director serves, indexed for the ring: folded vpath ->
  file or directory. This replaces the Rust director's provider stack (mount
  graph, overlay, layered): the layering has already happened (in Cairn, the
  projection) by the time a tree is built, so serving is one lookup.

  A directory's READDIR reply is encoded once, here, and copied per request."
  (:require [aether.director.path :as path])
  (:import (java.io ByteArrayOutputStream)
           (java.lang.foreign MemorySegment)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)
           (java.util HashMap)))

(deftype File [^long size ^MemorySegment data])
(deftype Dir [^bytes listing])              ; the encoded READDIR reply

(deftype Tree [^HashMap nodes])             ; folded vpath -> File | Dir; the root is ""

(defn lookup [^Tree t ^String vpath] (.get ^HashMap (.-nodes t) vpath))

(defn- encode-listing
  "count:u32, then per entry name_len:u32 name is_dir:u8 size:u64 mtime:i64."
  ^bytes [entries]
  (let [out (ByteArrayOutputStream.)
        le  (fn [^long n ^long v]
              (let [b (.order (ByteBuffer/allocate n) ByteOrder/LITTLE_ENDIAN)]
                (if (= n 4) (.putInt b (int v)) (.putLong b v))
                (.write out (.array b))))]
    (le 4 (count entries))
    (doseq [{:keys [^String name dir? size]} entries
            :let [nb (.getBytes name StandardCharsets/UTF_8)]]
      (le 4 (alength nb))
      (.write out nb)
      (.write out (int (if dir? 1 0)))
      (le 8 size)
      (le 8 0))
    (.toByteArray out)))

(defn build
  "A tree from `files`, a seq of [path ^MemorySegment data] with paths as the
  game spells them (`/`, any case). Parent directories are implied. Entries
  keep their spelling; lookups go by folded path."
  [files]
  (let [;; folded dir -> {folded child name -> entry}, in insertion order
        children (HashMap.)
        add!     (fn [^String dir entry]
                   (let [^java.util.Map m (or (.get children dir)
                                              (let [m (java.util.LinkedHashMap.)] (.put children dir m) m))]
                     (.putIfAbsent m (path/fold (:name entry)) entry)))
        nodes    (HashMap.)]
    (.put children "" (java.util.LinkedHashMap.))
    (doseq [[p ^MemorySegment data] files
            :let [p (path/normalize p)]]
      (.put nodes (path/fold p) (File. (.byteSize data) data))
      (add! (path/fold (path/parent p)) {:name (path/file-name p) :size (.byteSize data)})
      (loop [d (path/parent p)]
        (when-not (= "" d)
          (add! (path/fold (path/parent d)) {:name (path/file-name d) :dir? true :size 0})
          (recur (path/parent d)))))
    (doseq [[dir ^java.util.Map m] children]
      (.put nodes dir (Dir. (encode-listing (vals m)))))
    (Tree. nodes)))
