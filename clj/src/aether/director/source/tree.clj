(ns aether.director.source.tree
  "A read-only provider over a remote file listing: what the Nexus, GOG and
  Steam sources share. Built once from [{:path :size :key}] (paths split on
  / and \\); directories are implied by file paths; lookups fold case; the
  first file at a path wins (a later one, or one whose path runs through a
  file, is skipped). Every mtime is 2020-01-01, as upstream's.

  The source supplies (open-file key size) -> a Handle; content is fetched
  only when read."
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [clojure.string :as str])
  (:import (aether.director.provider Entry Stat)))

(set! *warn-on-reflection* true)

(def ^:const mtime 1577836800)

(defn build
  "{folded-path node}: a node is {:name :dir? :children [folded…]} or {:name
  :size :key}. A file entry with :dir? true is an (empty) directory.
  `order` :sorted lists children by folded name, :insertion in the order
  seen."
  [files order]
  (let [nodes (java.util.HashMap.)]
    (.put nodes "" {:name "" :dir? true :children []})
    (doseq [{:keys [^String path size] file-key :key dir-entry? :dir?} files]
      (let [parts (remove empty? (str/split path #"[/\\]"))]
        (when (seq parts)
          (loop [[part & more] parts parent ""]
            (let [k (if (= "" parent) (path/fold part) (str parent "/" (path/fold part)))
                  existing (.get nodes k)]
              (if (or more dir-entry?)
                (cond
                  (nil? existing)
                  (do (.put nodes k {:name part :dir? true :children []})
                      (.put nodes parent (update (.get nodes parent) :children conj k))
                      (when more (recur more k)))
                  (:dir? existing) (when more (recur more k))
                  :else nil)                                     ; runs through a file: skipped
                (when (nil? existing)
                  (.put nodes k {:name part :size (long size) :key file-key})
                  (.put nodes parent (update (.get nodes parent) :children conj k)))))))))
    (let [m (into {} nodes)]
      (if (= order :sorted)
        (into {} (map (fn [[k v]] [k (cond-> v (:dir? v) (update :children #(vec (sort %))))])) m)
        m))))

(def ^:private dir-stat (Stat. true 0 mtime))

(deftype DirHandle []
  prov/Handle
  (handle-stat [_] dir-stat)
  (immutable? [_] true)
  (read-into [_ _ _ _ _] p/is-dir)
  (write-from [_ _ _ _ _] p/read-only)
  (truncate! [_ _] p/read-only)
  (close! [_] nil))

(defn- stat-of ^Stat [n] (if (:dir? n) dir-stat (Stat. false (long (:size n)) mtime)))

(defrecord TreeProvider [nodes open-file]
  prov/Provider
  (stat [_ vp] (some-> (get nodes vp) stat-of))
  (list-dir [_ vp]
    (when-let [n (get nodes vp)]
      (if (:dir? n)
        (mapv (fn [k] (let [c (get nodes k)] (Entry. (:name c) (stat-of c)))) (:children n))
        p/not-a-directory)))
  (spelling [_ vp]
    (cond (= "" vp) ""
          (contains? nodes vp)
          (let [parts (str/split vp #"/")]
            (str/join "/" (for [i (range 1 (inc (count parts)))]
                            (:name (get nodes (str/join "/" (take i parts)))))))))
  (open [_ vp flags]
    (let [n (get nodes (path/fold vp))]
      (cond
        (nil? n) p/not-found
        (not (zero? (bit-and (long flags) (bit-or p/open-write p/open-create p/open-trunc p/open-append)))) p/read-only
        (:dir? n) (DirHandle.)
        :else (open-file (:key n) (:size n)))))
  (mkdir! [_ _] p/read-only)
  (delete! [_ _] p/read-only)
  (rename! [_ _ _] p/read-only))

(defn provider
  "A provider over `files` ([{:path :size :key}]), reading through
  (open-file key size) -> Handle."
  [files open-file & [{:keys [order] :or {order :sorted}}]]
  (->TreeProvider (build files order) open-file))
