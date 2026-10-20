(ns aether.director.provider.compose
  "Providers built from providers.

    layered   read-only stack, later layers win
    overlay   a writable upper provider over a read-only lower one
              (copy-on-write; see `overlay`)"
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.provider :as prov])
  (:import (aether.director.provider Entry Stat)
           (java.util LinkedHashMap TreeMap)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.concurrent.atomic AtomicLong)
           (java.util.function Function)))

(set! *warn-on-reflection* true)

(def ^:private ^:const write-flags (bit-or p/open-write p/open-create p/open-trunc p/open-append))

(defn- top-with
  "The last of `layers` that has `vp`, with its stat: [layer stat] or nil."
  [layers vp]
  (loop [i (dec (count layers))]
    (when (>= i 0)
      (let [l (nth layers i)]
        (if-let [st (prov/stat l vp)] [l st] (recur (dec i)))))))

(defn- union-listing
  "The entries of directory `vp` across `layers` (bottom to top): a later
  layer's entry replaces an earlier one with the same folded name, keeping
  the first spelling's position."
  [layers vp]
  (let [m (LinkedHashMap.)]
    (doseq [l layers
            :let [es (prov/list-dir l vp)]
            :when (sequential? es)
            ^Entry e es]
      (.put m (path/fold (.-name e)) e))
    (vec (.values m))))

(deftype Layered [layers]
  prov/Provider
  (stat [_ vp] (second (top-with layers vp)))
  (list-dir [_ vp]
    (when-let [[_ ^Stat st] (top-with layers vp)]
      (if (.-dir? st) (union-listing layers vp) p/not-a-directory)))
  (spelling [_ vp] (some #(prov/spelling % vp) (rseq layers)))
  (open [_ vp flags]
    (let [fp (path/fold vp)]
      (if-let [[l] (top-with layers fp)]
        (if (zero? (bit-and (long flags) write-flags)) (prov/open l fp flags) p/read-only)
        (if (zero? (bit-and (long flags) p/open-create)) p/not-found p/read-only))))
  (mkdir! [_ vp] (if (top-with layers (path/fold vp)) p/exists p/read-only))
  (delete! [_ vp] (if (top-with layers (path/fold vp)) p/read-only p/not-found))
  (rename! [_ from _] (if (top-with layers (path/fold from)) p/read-only p/not-found)))

(defn layered
  "A read-only provider over `layers`, bottom to top: later layers win."
  [layers]
  (if (= 1 (count layers)) (first layers) (Layered. (vec layers))))

;; --- overlay: a writable upper over a read-only lower --------------------------
;;
;; vfs-compose's OverlayProvider, in its production shape (a disk upper):
;;
;;   reads      the upper if it has the path, else the lower; a whiteout on the
;;              path or any ancestor hides it
;;   write open copies a lower file up first (via `.cu.<n>.<name>` and a
;;              rename, so the upper never holds half a file); a lower
;;              directory can't be opened for write (IS_DIR)
;;   delete     removes the upper's copy; if the lower has the path, writes a
;;              whiteout `.wh.<name>` beside it in the upper
;;   mkdir      clears the path's whiteout (the lower's children reappear: no
;;              opaque directories) and makes it in the upper
;;   rename     copies a lower file up, renames in the upper, whites out the
;;              lower source
;;   listing    lower and upper merged by folded name: the upper's stat wins,
;;              the lower's spelling is kept; markers are never listed
;;
;; Unlike the Rust overlay, this one keeps an index of what the upper holds and
;; of its whiteouts (it is the upper's only writer), so a read of lower content
;; costs no stat of the upper. And it deliberately differs in three places the
;; Rust overlay arguably gets wrong: a directory left holding only whiteout
;; markers can be removed; a rename into a folder only the lower has makes
;; that folder in the upper first; the upper's marker files are cleaned of
;; stale `.cu.` temps at start.

(def ^:private wh ".wh.")
(def ^:private cu ".cu.")

(defn- child ^String [^String dir ^String nm] (if (= "" dir) nm (str dir "/" nm)))

(defn- marker? [^String nm]
  (let [n (path/fold nm)] (or (.startsWith n wh) (.startsWith n cu))))

(deftype Overlay [upper lower
                  ^ConcurrentHashMap present        ; folded vpaths the upper holds (files and dirs)
                  ^ConcurrentHashMap whiteouts      ; folded vpaths whited out
                  ^AtomicLong cu-seq
                  ^ConcurrentHashMap locks])        ; folded vpath -> lock, for copy-up

(defn- present? [^Overlay o fp] (.containsKey ^ConcurrentHashMap (.-present o) fp))

(defn- whited-from?
  "Is `fp` or any ancestor of it (starting at `fp`) whited out?"
  [^Overlay o ^String fp]
  (let [^ConcurrentHashMap w (.-whiteouts o)]
    (loop [q fp]
      (cond (= "" q)            false
            (.containsKey w q)  true
            :else               (recur (path/parent q))))))

(defn- hidden? [o fp] (whited-from? o fp))
(defn- ancestor-whited? [o fp] (whited-from? o (path/parent fp)))

(defn- mark-present!
  "The upper holds `fp`, and so all its ancestors."
  [^Overlay o ^String fp]
  (loop [q fp]
    (when-not (= "" q)
      (.put ^ConcurrentHashMap (.-present o) q Boolean/TRUE)
      (recur (path/parent q)))))

(defn- unmark-tree!
  "The upper no longer holds `fp` or anything under it."
  [^Overlay o ^String fp]
  (let [prefix (str fp "/")]
    (doseq [^ConcurrentHashMap m [(.-present o) (.-whiteouts o)]
            ^String k (vec (.keySet m))
            :when (or (= k fp) (.startsWith k prefix))]
      (.remove m k))))

(defn- index!
  "Records what the upper holds under directory `dir` (a folded vpath),
  recursively: content, whiteouts, and stale copy-up temps (deleted)."
  [^Overlay o ^String dir]
  (let [es (prov/list-dir (.-upper o) dir)]
    (when (sequential? es)
      (doseq [^Entry e es
              :let [nm (.-name e) fname (path/fold nm)]]
        (cond
          (.startsWith fname wh) (.put ^ConcurrentHashMap (.-whiteouts o) (child dir (subs fname (count wh))) Boolean/TRUE)
          (.startsWith fname cu) (prov/delete! (.-upper o) (child dir nm))
          :else (let [fp (child dir fname)]
                  (mark-present! o fp)
                  (when (.-dir? ^Stat (.-stat e)) (index! o fp))))))))

(defn- touch!
  "An empty file at `vp` in the upper (a whiteout marker). Returns a status."
  ^long [^Overlay o vp]
  (let [h (prov/open (.-upper o) vp (bit-or p/open-write p/open-create p/open-trunc))]
    (if (number? h) h (do (prov/close! h) p/ok))))

(defn- whiteout!
  "Hides `vp` (a lower path) with a `.wh.` marker beside it in the upper."
  ^long [^Overlay o ^String vp ^String fp]
  (let [st (touch! o (child (path/parent vp) (str wh (path/file-name vp))))]
    (when (zero? st)
      (mark-present! o (path/fold (path/parent vp)))
      (.put ^ConcurrentHashMap (.-whiteouts o) fp Boolean/TRUE))
    st))

(defn- clear-whiteout!
  "Removes `fp`'s whiteout, if it has one."
  [^Overlay o ^String fp]
  (when (.remove ^ConcurrentHashMap (.-whiteouts o) fp)
    (prov/delete! (.-upper o) (child (path/parent fp) (str wh (path/file-name fp))))))

(defn- copy-up!
  "Copies lower file `fp` into the upper, once, whatever the concurrency:
  through a `.cu.` temp and a rename, so the upper never holds half a file.
  The copy keeps the lower's spelling. Returns a status."
  ^long [^Overlay o ^String fp]
  (locking (.computeIfAbsent ^ConcurrentHashMap (.-locks o) fp
                             (reify Function (apply [_ _] (Object.))))
    (if (present? o fp)
      p/ok
      (let [spelled (or (prov/spelling (.-lower o) fp) fp)
            tmp     (child (path/parent spelled) (str cu (.incrementAndGet ^AtomicLong (.-cu-seq o)) "." (path/file-name spelled)))
            src     (prov/open (.-lower o) fp p/open-read)]
        (if (number? src)
          src
          (let [dst (prov/open (.-upper o) tmp (bit-or p/open-write p/open-create p/open-trunc))]
            (if (number? dst)
              (do (prov/close! src) dst)
              (let [st (prov/copy! src dst (.-size ^Stat (prov/handle-stat src)))]
                (prov/close! src)
                (prov/close! dst)
                (let [st (if (zero? st) (long (prov/rename! (.-upper o) tmp spelled)) st)]
                  (if (zero? st)
                    (mark-present! o fp)
                    (prov/delete! (.-upper o) tmp))
                  st)))))))))

(defn- prepare-write
  "Readies `fp` for a write open the upper doesn't hold yet. Returns a status."
  ^long [^Overlay o ^String fp ^long flags]
  (cond
    (ancestor-whited? o fp) p/not-found
    (.containsKey ^ConcurrentHashMap (.-whiteouts o) fp)
    (if (zero? (bit-and flags p/open-create)) p/not-found (do (clear-whiteout! o fp) p/ok))
    :else
    (let [^Stat st (prov/stat (.-lower o) fp)]
      (cond (nil? st)    p/ok
            (.-dir? st)  p/is-dir
            :else        (copy-up! o fp)))))

(defn- delete-upper!
  "Removes `vp` from the upper: for a directory, first the whiteout markers it
  may hold (a directory whose lower children were all deleted is otherwise
  never empty again)."
  ^long [^Overlay o ^String vp ^String fp]
  (let [up (.-upper o)
        ^Stat st (prov/stat up fp)]
    (when (and st (.-dir? st))
      (let [es (prov/list-dir up fp)]
        (when (and (sequential? es) (every? #(marker? (.-name ^Entry %)) es))
          (doseq [^Entry e es] (prov/delete! up (child vp (.-name e)))))))
    (let [r (long (prov/delete! up vp))]
      (when (zero? r) (unmark-tree! o fp))
      r)))

(extend-type Overlay
  prov/Provider
  (stat [o fp]
    (cond (= "" fp)       prov/dir-stat
          (hidden? o fp)  nil
          (present? o fp) (or (prov/stat (.-upper o) fp) (prov/stat (.-lower o) fp))
          :else           (prov/stat (.-lower o) fp)))

  (list-dir [o fp]
    (when-not (and (not= "" fp) (hidden? o fp))
      (let [b  (prov/list-dir (.-lower o) fp)
            u  (when (or (= "" fp) (present? o fp)) (prov/list-dir (.-upper o) fp))
            ^ConcurrentHashMap w (.-whiteouts o)]
        (if-not (or (sequential? b) (sequential? u))
          (when (or (number? b) (number? u)) p/not-a-directory)
          (let [m (TreeMap.)]
            (when (sequential? b)
              (doseq [^Entry e b
                      :let [k (path/fold (.-name e))]
                      :when (not (.containsKey w (child fp k)))]
                (.put m k e)))
            (when (sequential? u)
              (doseq [^Entry e u
                      :let [k (path/fold (.-name e))]
                      :when (not (marker? (.-name e)))]
                (.put m k (if-let [^Entry base (.get m k)] (Entry. (.-name base) (.-stat e)) e))))
            (vec (.values m)))))))

  (spelling [o fp]
    (cond (= "" fp)      ""
          (hidden? o fp) nil
          :else          (or (prov/spelling (.-lower o) fp)
                             (when (present? o fp) (prov/spelling (.-upper o) fp)))))

  (open [o vp flags]
    (let [fp (path/fold vp)]
      (if (zero? (bit-and (long flags) write-flags))
        (cond (and (not= "" fp) (hidden? o fp)) p/not-found
              (present? o fp) (let [h (prov/open (.-upper o) fp flags)]
                                (if (= h p/not-found) (prov/open (.-lower o) fp flags) h))
              :else (prov/open (.-lower o) fp flags))
        (let [st (if (present? o fp) p/ok (prepare-write o fp flags))]
          (if (neg? (long st))
            st
            (let [h (prov/open (.-upper o) vp flags)]
              (when-not (number? h) (mark-present! o fp))
              h))))))

  (mkdir! [o vp]
    (let [fp (path/fold vp)]
      (if (ancestor-whited? o fp)
        p/not-found
        (do (clear-whiteout! o fp)
            (let [st (long (prov/mkdir! (.-upper o) vp))]
              (when (zero? st) (mark-present! o fp))
              st)))))

  (delete! [o vp]
    (let [fp (path/fold vp)
          in-upper (present? o fp)
          in-lower (some? (prov/stat (.-lower o) fp))]
      (cond
        (hidden? o fp)                          p/not-found
        (not (or in-upper in-lower))            p/not-found
        :else
        (let [st (if in-upper (delete-upper! o vp fp) p/ok)]
          (if (and (zero? (long st)) in-lower) (whiteout! o vp fp) st)))))

  (rename! [o from to]
    (let [ffp (path/fold from) tfp (path/fold to)
          ^Stat lst (prov/stat (.-lower o) ffp)
          in-upper (present? o ffp)]
      (cond
        (hidden? o ffp)                     p/not-found
        (= ffp tfp)                         (if in-upper (prov/rename! (.-upper o) from to) p/ok)
        (not (or in-upper lst))             p/not-found
        (and (not in-upper) (.-dir? lst))   p/io-error        ; as upstream: lower directories don't move
        :else
        (let [st (if in-upper p/ok (copy-up! o ffp))]
          (if (neg? (long st))
            st
            (do
              (when-not (= "" (path/parent tfp)) (prov/mkdir! (.-upper o) (path/parent to)))
              (let [r (long (prov/rename! (.-upper o) from to))]
                (when (zero? r)
                  (unmark-tree! o ffp)
                  (clear-whiteout! o tfp)
                  (mark-present! o tfp)
                  (let [^Stat moved (prov/stat (.-upper o) tfp)]
                    (when (and moved (.-dir? moved)) (index! o tfp)))
                  (when lst (whiteout! o from ffp)))
                r)))))))

  prov/Listing
  (listing-bytes [_ _] nil))

(defn overlay
  "A writable provider: `upper` (a writable provider, usually
  aether.director.provider.disk) over `lower` (read-only). The upper's contents,
  including whiteouts left by an earlier session, are indexed now."
  [upper lower]
  (doto (Overlay. upper lower (ConcurrentHashMap.) (ConcurrentHashMap.) (AtomicLong.) (ConcurrentHashMap.))
    (index! "")))
