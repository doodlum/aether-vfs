(ns aether.director.registry
  "Registry virtualization, the director's half (after rust/'s vfs-registry and
  vfs-director/src/registry.rs). One copy-on-write overlay over the real
  registry; the shim reads the real registry itself and merges this overlay
  over it, so the director only stores the overlay, answers ring ops 15-22,
  persists it, and publishes `reg_gen` so the shim's caches stay valid.

  The overlay is one immutable value: reads take a snapshot (an answer and
  its version always come from the same state), writes are pure functions
  applied under one lock. Paths are canonical NT key paths
  (`\\Registry\\Machine\\...`); everything is keyed by the folded path and keeps
  the spelling it was first written with.

  A node:
    {:values      [{:name :ty :data} …]       ; insertion order; lookup by folded name
     :value-tombs [folded-name …]             ; values deleted here (hide real ones too)
     :children    {folded [spelling :present|:tomb]}  ; sorted by UTF-8 bytes
     :created     bool                        ; created here: no real counterpart shows through
     :volatile    bool                        ; never persisted
     :last-write  FILETIME}"
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [clojure.java.io :as io])
  (:import (java.io ByteArrayOutputStream File)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.util ArrayList Arrays Comparator)
           (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong)))

(set! *warn-on-reflection* true)

(def ^:const max-key-name 255)
(def ^:const max-value-name 16383)
(def ^:const max-data (bit-shift-left 1 20))
(def ^:const max-deleted-at 16384)
(def ^:const max-write-depth 512)
(def ^:const max-decode-depth 1024)

;; --- paths -------------------------------------------------------------------------

(defn fold ^String [s] (path/fold s))

(defn- parts [^String p] (vec (.split p "\\\\" -1)))

(defn parent
  "The parent key path, nil for \\Registry (or no separator)."
  [^String p]
  (let [i (.lastIndexOf p "\\")] (when (pos? i) (subs p 0 i))))

(defn leaf ^String [^String p] (subs p (inc (.lastIndexOf p "\\"))))

(defn- overlay-path?
  "`\\Registry` then non-empty components (the overlay's own check)."
  [^String p]
  (let [ps (parts p)]
    (and (>= (count ps) 2) (= "" (ps 0)) (= "registry" (fold (ps 1))) (every? seq (subvec ps 2)))))

(defn valid-path?
  "The director's check on a request's path: canonical (`\\Registry`, single
  separators, nothing trailing) with no `.` or `..` component."
  [^String p]
  (and (.startsWith p "\\")
       (overlay-path? p)
       (not-any? #{"." ".."} (parts p))))

(defn- utf16-len ^long [^String s] (.length s))

(defn- fail [kind] (throw (ex-info (name kind) {::error kind})))

(defn- check-key-name [p]
  (when (some #(> (utf16-len %) max-key-name) (rest (parts p))) (fail :name-too-long)))

(defn- check-depth [p limit]
  (when (> (- (count (parts p)) 2) limit) (fail :too-deep)))

(defn filetime-now
  "Now, as a Windows FILETIME (100 ns ticks since 1601)."
  ^long []
  (+ (* (System/currentTimeMillis) 10000) 116444736000000000))

;; --- the overlay ---------------------------------------------------------------------

(def ^:private utf8-order
  "Rust's BTreeMap<String> order: by UTF-8 bytes."
  (reify Comparator
    (compare [_ a b]
      (Arrays/compareUnsigned (.getBytes ^String a StandardCharsets/UTF_8)
                              (.getBytes ^String b StandardCharsets/UTF_8)))))

(defn- new-node [created now]
  {:values [] :value-tombs [] :children (sorted-map-by utf8-order)
   :created (boolean created) :volatile false :last-write now})

(def empty-overlay
  {:entries (sorted-map)          ; folded path -> {:path spelled :node :changed :subtree}
   :tombs (sorted-map)            ; folded path of a tombstoned key -> version
   :deleted-at {}                 ; folded path -> newest deletion version (survives revival)
   :floor 0                       ; deletions at or below this were forgotten
   :version 0})

(defn version ^long [o] (:version o))

(defn- under [m ^String prefix]
  (take-while #(.startsWith ^String (key %) prefix) (subseq m >= prefix)))

(defn- below? [o f]
  (let [pre (str f "\\")]
    (boolean (or (seq (under (:entries o) pre)) (seq (under (:tombs o) pre))))))

(defn- tombstoned?
  "Tombstoned itself, or below a tombstoned ancestor."
  [o f]
  (loop [cur f]
    (or (contains? (:tombs o) cur)
        (if-let [p (parent cur)] (recur p) false))))

(defn lookup
  "[state below?]: state is :absent, :present, :present-created or :tombstoned;
  below? is whether a node or tombstone exists strictly under the path."
  [o p]
  (if-not (overlay-path? p)
    [:absent false]
    (let [f (fold p)]
      (if-let [e (get-in o [:entries f])]
        [(if (get-in e [:node :created]) :present-created :present) (below? o f)]
        (if (tombstoned? o f) [:tombstoned false] [:absent (below? o f)])))))

(defn node
  "The overlay's node at `p`, or nil."
  [o p]
  (get-in o [:entries (fold p) :node]))

(defn- bump [o] (let [v (inc (:version o))] [(assoc o :version v) v]))

(defn- touch-ancestors [o p v]
  (loop [o o cur (parent p)]
    (if-not cur
      o
      (recur (let [f (fold cur)] (if (get-in o [:entries f]) (assoc-in o [:entries f :subtree] v) o))
             (parent cur)))))

(defn- mark [o p v]
  (let [f (fold p)]
    (touch-ancestors (if (get-in o [:entries f]) (assoc-in o [:entries f :changed] v) o) p v)))

(defn- tomb [o f v]
  (let [o (-> o
              (update :deleted-at update f (fnil max 0) v)
              (assoc-in [:tombs f] v))]
    (if (> (count (:deleted-at o)) max-deleted-at)
      (assoc o :floor (max (:floor o) (reduce max 0 (vals (:deleted-at o)))) :deleted-at {})
      o)))

(defn- subtree-keys [o f]
  (concat (when (get-in o [:entries f]) [f]) (map key (under (:entries o) (str f "\\")))))

(defn- remove-subtree [o f]
  (let [ks (subtree-keys o f)
        ts (map key (under (:tombs o) (str f "\\")))]
    (-> o
        (update :entries #(apply dissoc % ks))
        (update :tombs #(apply dissoc % f ts)))))

(defn- ensure-chain
  "Makes every key on the way to `p` (inclusive) a node. A missing key under a
  created-here parent, or one being revived from a tombstone, is created-here;
  otherwise it is a plain presence, except the target itself when
  `target-real-exists` is false."
  [o p target-real-exists v now]
  (let [comps (vec (rest (parts p)))]
    (loop [o o i 0 stored "" parent-key nil]
      (if (= i (count comps))
        o
        (let [c      (comps i)
              target (= i (dec (count comps)))
              stored (str stored "\\" c)
              k      (fold stored)]
          (if-let [e (get-in o [:entries k])]
            (recur o (inc i) (:path e) k)
            (let [revived (or (contains? (:tombs o) k)
                              (and parent-key
                                   (= :tomb (second (get-in o [:entries parent-key :node :children (fold c)])))))
                  o       (update o :tombs dissoc k)
                  pcreated (boolean (and parent-key (get-in o [:entries parent-key :node :created])))
                  created (or revived pcreated (and target (not target-real-exists)))
                  o       (assoc-in o [:entries k] {:path stored :node (new-node created now) :changed v :subtree 0})
                  o       (if parent-key
                            (update-in o [:entries parent-key]
                                       #(-> % (assoc-in [:node :children (fold c)] [c :present])
                                            (assoc-in [:node :last-write] now)
                                            (assoc :changed v)))
                            o)]
              (recur o (inc i) stored k))))))))

(defn set-value
  "Sets value `vname` of key `p` (making the key, and its ancestors, plain
  presences if missing). Returns [overlay version]."
  [o p ^String vname ty ^bytes data now]
  (when-not (overlay-path? p) (fail :invalid-path))
  (check-key-name p)
  (check-depth p max-write-depth)
  (when (> (utf16-len vname) max-value-name) (fail :name-too-long))
  (when (> (alength data) max-data) (fail :data-too-large))
  (let [[o v] (bump o)
        o     (ensure-chain o p true v now)
        f     (fold p)
        fname (fold vname)
        o     (update-in o [:entries f :node]
                         (fn [n]
                           (-> n
                               (update :value-tombs (fn [ts] (vec (remove #{fname} ts))))
                               (update :values (fn [vs]
                                                 (if (some #(= fname (fold (:name %))) vs)
                                                   (mapv #(if (= fname (fold (:name %))) (assoc % :ty ty :data data) %) vs)
                                                   (conj vs {:name vname :ty ty :data data}))))
                               (assoc :last-write now))))]
    [(mark o p v) v]))

(defn delete-value
  "Deletes value `vname` of key `p`; the tombstone also hides a real value of
  that name, so this succeeds whether or not the overlay had it."
  [o p ^String vname now]
  (when-not (overlay-path? p) (fail :invalid-path))
  (check-key-name p)
  (when (> (utf16-len vname) max-value-name) (fail :name-too-long))
  (when (= :tombstoned (first (lookup o p))) (fail :not-found))
  (let [[o v] (bump o)
        o     (ensure-chain o p true v now)
        fname (fold vname)
        o     (update-in o [:entries (fold p) :node]
                         (fn [n]
                           (-> n
                               (update :values (fn [vs] (vec (remove #(= fname (fold (:name %))) vs))))
                               (update :value-tombs (fn [ts] (if (some #{fname} ts) ts (conj ts fname))))
                               (assoc :last-write now))))]
    [(mark o p v) v]))

(defn create-key
  "Creates key `p`. `real-exists`: a real key of that name is visible, so the
  node is a plain presence (the director always passes false: the shim only
  asks for keys with no real counterpart)."
  [o p volatile real-exists now]
  (when-not (overlay-path? p) (fail :invalid-path))
  (check-key-name p)
  (check-depth p max-write-depth)
  (when (get-in o [:entries (fold p)]) (fail :already-exists))
  (let [[o v] (bump o)
        o     (ensure-chain o p real-exists v now)
        o     (assoc-in o [:entries (fold p) :node :volatile] (boolean volatile))]
    [(mark o p v) v]))

(defn delete-key
  "Tombstones key `p` and drops its subtree."
  [o p now]
  (when-not (overlay-path? p) (fail :invalid-path))
  (let [par (or (parent p) (fail :invalid-path))]
    (when (= :tombstoned (first (lookup o p))) (fail :not-found))
    (let [[o v] (bump o)
          o     (ensure-chain o par true v now)
          f     (fold p)
          o     (-> o (remove-subtree f) (tomb f v))
          pf    (fold par)
          o     (update-in o [:entries pf]
                           #(-> % (assoc-in [:node :children (fold (leaf p))] [(leaf p) :tomb])
                                (assoc-in [:node :last-write] now)
                                (assoc :changed v)))]
      [(touch-ancestors o par v) v])))

(defn rename-key
  "Renames key `p` (which must be in the overlay) to `new-leaf` under the same
  parent, moving its subtree; the old name is tombstoned (unless only its case
  changes), and the moved keys are created-here."
  [o p ^String new-leaf now]
  (when-not (overlay-path? p) (fail :invalid-path))
  (when (or (= "" new-leaf) (.contains new-leaf "\\")) (fail :invalid-path))
  (when (> (utf16-len new-leaf) max-key-name) (fail :name-too-long))
  (let [par        (or (parent p) (fail :invalid-path))
        old-f      (fold p)
        root       (or (get-in o [:entries old-f]) (fail :not-found))
        old-stored (:path root)
        new-path   (str (parent old-stored) "\\" new-leaf)
        new-f      (fold new-path)
        case-only  (= new-f old-f)]
    (when (and (not case-only) (get-in o [:entries new-f])) (fail :already-exists))
    (let [[o v]       (bump o)
          moved-keys  (subtree-keys o old-f)
          moved       (mapv #(get-in o [:entries %]) moved-keys)
          moved-tombs (map key (under (:tombs o) (str old-f "\\")))
          o           (-> o (remove-subtree old-f) (update :tombs dissoc new-f))
          o           (reduce (fn [o e]
                                (let [suffix (subs (:path e) (count old-stored))]
                                  (assoc-in o [:entries (str new-f (fold suffix))]
                                            (-> e (assoc :path (str new-path suffix) :changed v :subtree v)
                                                (assoc-in [:node :created] true)))))
                              o moved)
          o           (reduce (fn [o k] (tomb o (str new-f (subs k (count old-f))) v)) o moved-tombs)
          o           (assoc-in o [:entries new-f :node :last-write] now)
          pf          (fold par)
          o           (if case-only
                        o
                        (-> o
                            (assoc-in [:entries pf :node :children (fold (leaf p))] [(leaf p) :tomb])
                            (tomb old-f v)))
          o           (update-in o [:entries pf]
                                 #(-> % (assoc-in [:node :children (fold new-leaf)] [new-leaf :present])
                                      (assoc-in [:node :last-write] now)
                                      (assoc :changed v)))]
      [(touch-ancestors o par v) v])))

(defn changed-since?
  "Whether `p` changed after `since`: its own values, existence or child list,
  and with `subtree?` anything below it. A deleted key counts as changed by
  the deletion of it or of any ancestor."
  [o p subtree? since]
  (let [f (fold p) since (long since)]
    (if-let [e (get-in o [:entries f])]
      (or (> (long (:changed e)) since) (and subtree? (> (long (:subtree e)) since)))
      (loop [cur f]
        (or (> (long (get-in o [:deleted-at cur] 0)) since)
            (if-let [par (parent cur)] (recur par) (> (long (:floor o)) since)))))))

(defn- insert-node
  "Rebuilding from a saved overlay: `node` at `p`, changed at `changed`."
  [o p n changed]
  (when-not (overlay-path? p) (fail :invalid-path))
  (check-depth p max-decode-depth)
  (let [f (fold p)
        o (reduce (fn [o [cf [_ st]]] (if (= st :tomb) (tomb o (str f "\\" cf) changed) o)) o (:children n))]
    (-> o (update :tombs dissoc f)
        (assoc-in [:entries f] {:path p :node n :changed changed :subtree changed}))))

;; --- bytes ------------------------------------------------------------------------------

(defn- out-bytes [f]
  (let [out (ByteArrayOutputStream.)
        le  (fn [n v] (let [b (.order (ByteBuffer/allocate n) ByteOrder/LITTLE_ENDIAN)]
                        (case (long n) 1 (.put b (unchecked-byte v)) 4 (.putInt b (unchecked-int v)) 8 (.putLong b (long v)))
                        (.write out (.array b))))
        api {:u8 #(le 1 %) :u32 #(le 4 %) :u64 #(le 8 %)
             :raw (fn [^bytes b] (.write out b))
             :put-str (fn [^String s] (let [b (.getBytes s StandardCharsets/UTF_8)] (le 4 (alength b)) (.write out b)))}]
    (f api)
    (.toByteArray out)))

(defn- reader
  "A strict little-endian reader over `b`: every read throws when the input
  runs out (or a bool isn't 0/1, or a string is too long)."
  [^bytes b]
  (let [buf (.order (ByteBuffer/wrap b) ByteOrder/LITTLE_ENDIAN)
        need (fn [n] (when (> (long n) (.remaining buf)) (fail :truncated)))
        get-str (fn [max-utf16]
                  (need 4)
                  (let [n (Integer/toUnsignedLong (.getInt buf))]
                    (need n)
                    (let [a (byte-array n) _ (.get buf a)
                          s (String. a StandardCharsets/UTF_8)]
                      (when (> (utf16-len s) (long max-utf16)) (fail :name-too-long))
                      s)))]
    {:u8   (fn [] (need 1) (Byte/toUnsignedLong (.get buf)))
     :u32  (fn [] (need 4) (Integer/toUnsignedLong (.getInt buf)))
     :u64  (fn [] (need 8) (.getLong buf))
     :bool (fn [] (need 1) (case (int (.get buf)) 0 false 1 true (fail :bad-bool)))
     :bytes (fn [n] (need n) (let [a (byte-array n)] (.get buf a) a))
     :get-str (fn ([] (get-str Long/MAX_VALUE)) ([max-utf16] (get-str max-utf16)))
     :left (fn [] (.remaining buf))}))

;; --- the wire: ops 15-22 (vfs-protocol/src/lib.rs) ----------------------------------------

(def ^:private state-byte {:absent 0 :present 1 :present-created 2 :tombstoned 3})

(defn encode-lookup-reply ^bytes [v state below?]
  (out-bytes (fn [{:keys [u8 u64]}] (u64 v) (u8 (state-byte state)) (u8 (if below? 1 0)))))

(defn encode-key-reply
  "version u64 | has_node u8 | created u8 | volatile u8 | last_write u64 |
  nvalues u32 {name ty u32 dlen u32 data}* | ntomb u32 {str}* |
  nchildren u32 {folded spelling state u8}*"
  ^bytes [v n]
  (out-bytes
   (fn [{:keys [u8 u32 u64 raw put-str]}]
     (u64 v)
     (if (nil? n)
       (u8 0)
       (do (u8 1)
           (u8 (if (:created n) 1 0))
           (u8 (if (:volatile n) 1 0))
           (u64 (:last-write n))
           (u32 (count (:values n)))
           (doseq [{vn :name :keys [ty ^bytes data]} (:values n)]
             (put-str vn) (u32 ty) (u32 (alength data)) (raw data))
           (u32 (count (:value-tombs n)))
           (doseq [t (:value-tombs n)] (put-str t))
           (u32 (count (:children n)))
           (doseq [[folded [spelling st]] (:children n)]
             (put-str folded) (put-str spelling) (u8 (if (= st :tomb) 1 0))))))))

(defn encode-version-reply ^bytes [v] (out-bytes (fn [{:keys [u64]}] (u64 v))))

(defn encode-changed-reply ^bytes [v changed?]
  (out-bytes (fn [{:keys [u8 u64]}] (u64 v) (u8 (if changed? 1 0)))))

(defn decode-request
  "A registry request's fields, or throws (a bad request). Every request
  starts with the key path; decoding must consume the payload exactly."
  [op ^bytes payload]
  (let [{:keys [u32 u64 bool get-str left] read-bytes :bytes} (reader payload)
        p (get-str)
        req (case (long op)
              15 {:op :lookup :path p}
              16 {:op :key :path p}
              17 (let [vn (get-str max-value-name) ty (u32) n (u32)]
                   (when (> n max-data) (fail :data-too-large))
                   {:op :set-value :path p :name vn :ty ty :data (read-bytes n)})
              18 {:op :delete-value :path p :name (get-str max-value-name)}
              19 {:op :create-key :path p :volatile (bool)}
              20 {:op :delete-key :path p}
              21 {:op :rename-key :path p :new-leaf (get-str max-key-name)}
              22 {:op :changed :path p :subtree (bool) :since (u64)})]
    (when-not (zero? (long (left))) (fail :trailing-bytes))
    req))

;; --- persistence: overlay.reg (vfs-registry/src/format.rs) -----------------------------------

(def ^:private magic (byte-array (map unchecked-byte [0x41 0x45 0x52 0x45 0x47 0 0 1])))   ; "AEREG\0\0\x01"

(defn- checksum
  "The wrapping sum of `b` read as zero-padded little-endian u64 words."
  ^long [^bytes b n]
  (let [padded (Arrays/copyOf b (int (* 8 (quot (+ (long n) 7) 8))))
        buf (.order (ByteBuffer/wrap padded) ByteOrder/LITTLE_ENDIAN)]
    (loop [sum 0]
      (if (.hasRemaining buf) (recur (unchecked-add sum (.getLong buf))) sum))))

(defn encode
  "The overlay as an overlay.reg file. Volatile nodes and their subtrees are
  left out."
  ^bytes [o]
  (let [body (out-bytes
              (fn [{:keys [u8 u32 u64 raw put-str]}]
                (raw magic)
                (let [root-e (get-in o [:entries "\\registry"])]
                  (if (or (nil? root-e) (get-in root-e [:node :volatile]))
                    (u8 0)
                    (do (u8 1)
                        ((fn enc [spelling p n]
                           (let [kids  (for [[_ [nm st]] (:children n) :when (= st :present)
                                             :let [cp (str p "\\" nm) cn (node o cp)]
                                             :when (and cn (not (:volatile cn)))]
                                         [nm cp cn])
                                 tombs (for [[_ [nm st]] (:children n) :when (= st :tomb)] nm)]
                             (put-str spelling)
                             (u8 (if (:created n) 1 0))
                             (u64 (:last-write n))
                             (u32 (count (:values n))) (u32 (count (:value-tombs n)))
                             (u32 (count tombs)) (u32 (count kids))
                             (doseq [{vn :name :keys [ty ^bytes data]} (:values n)]
                               (put-str vn) (u32 ty) (u32 (alength data)) (raw data))
                             (doseq [t (:value-tombs n)] (put-str t))
                             (doseq [t tombs] (put-str t))
                             (doseq [[nm cp cn] kids] (enc nm cp cn))))
                         (subs (:path root-e) 1) (:path root-e) (:node root-e)))))))]
    (let [sum (checksum body (alength ^bytes body))]
      (out-bytes (fn [{:keys [raw u64]}] (raw body) (u64 sum))))))

(defn decode
  "An overlay from overlay.reg bytes, fully validated (throws on anything
  damaged). The version restarts at 1, every node changed at 1."
  [^bytes b]
  (let [n (alength b)]
    (when (or (< n 17) (not (Arrays/equals (Arrays/copyOf b 8) ^bytes magic))) (fail :bad-magic))
    (let [body (Arrays/copyOf b (- n 8))
          sum  (.getLong (.order (ByteBuffer/wrap b (- n 8) 8) ByteOrder/LITTLE_ENDIAN))]
      (when-not (= sum (checksum body (alength body))) (fail :bad-checksum))
      (let [{:keys [u8 u32 u64 get-str left] read-bytes :bytes} (reader (Arrays/copyOfRange body 8 (alength body)))
            key-name (fn [] (let [s (get-str max-key-name)] (when (or (= "" s) (.contains ^String s "\\")) (fail :bad-key-name)) s))
            count* (fn [] (let [c (u32)] (when (> c (long (left))) (fail :truncated)) c))
            nodes (ArrayList.)                 ; [path node] in decode order
            dec-node (fn dec-node [p depth]
                       (when (> depth max-decode-depth) (fail :too-deep))
                       (let [flags (u8)
                             _ (when-not (zero? (bit-and flags (bit-not 1))) (fail :bad-flags))
                             lw (u64)
                             [nv nt nct nc] [(count*) (count*) (count*) (count*)]
                             values (vec (for [_ (range nv)]
                                           (let [nm (get-str max-value-name) ty (u32) dl (u32)]
                                             (when (> dl max-data) (fail :data-too-large))
                                             {:name nm :ty ty :data (read-bytes dl)})))
                             vtombs (vec (for [_ (range nt)] (let [t (get-str max-value-name)] (when-not (= t (fold t)) (fail :unfolded)) t)))
                             _ (when-not (apply distinct? (concat [::none] (map (comp fold :name) values) vtombs)) (fail :duplicate-value))
                             ctombs (vec (for [_ (range nct)] (key-name)))
                             slot (.size nodes)]
                         (.add nodes nil)
                         (let [kids (loop [i 0 acc []]
                                      (if (= i nc)
                                        acc
                                        (let [nm (key-name)]
                                          (dec-node (str p "\\" nm) (inc depth))
                                          (recur (inc i) (conj acc nm)))))
                               children (reduce (fn [m [nm st]]
                                                  (when (contains? m (fold nm)) (fail :duplicate-child))
                                                  (assoc m (fold nm) [nm st]))
                                                (sorted-map-by utf8-order)
                                                (concat (map vector ctombs (repeat :tomb)) (map vector kids (repeat :present))))]
                           (.set nodes slot [p {:values values :value-tombs vtombs :children children
                                                  :created (= 1 (bit-and flags 1)) :volatile false :last-write lw}]))))
            _ (case (long (u8))
                0 nil
                1 (let [nm (key-name)]
                    (when-not (= "registry" (fold nm)) (fail :bad-root))
                    (dec-node (str "\\" nm) 0)))]
        (when-not (zero? (long (left))) (fail :trailing-bytes))
        (assoc (reduce (fn [o [p nd]] (insert-node o p nd 1)) empty-overlay nodes) :version 1)))))

;; --- the director's registry: overlay + reg_gen + persistence ------------------------------------

(defrecord Registry [state ^Object lock ^AtomicLong gen publishers ^File file
                     ^AtomicBoolean dirty ^ScheduledExecutorService saver])

(defn- load-file* [^File f]
  (if-not (.isFile f)
    empty-overlay
    (try (decode (Files/readAllBytes (.toPath f)))
         (catch Exception _
           ;; a damaged file is moved aside, and the overlay starts empty
           (let [aside (io/file (.getParentFile f)
                                (str (.getName f) ".corrupt-" (quot (System/currentTimeMillis) 1000)))]
             (Files/move (.toPath f) (.toPath aside) (make-array CopyOption 0)))
           empty-overlay))))

(defn save!
  "Writes overlay.reg (a temp file, then a rename) if anything changed."
  [{:keys [state ^File file ^AtomicBoolean dirty]}]
  (when (.getAndSet dirty false)
    (let [tmp (io/file (.getParentFile file) (str (.getName file) ".tmp"))]
      (io/make-parents file)
      (io/copy (encode @state) tmp)
      (Files/move (.toPath tmp) (.toPath file)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE])))))

(defn registry
  "A registry layer stored in directory `dir` (overlay.reg; loaded now if it
  exists). Saved at most once a second while it changes, and on close."
  [dir]
  (let [f   (io/file dir "overlay.reg")
        ex  (Executors/newSingleThreadScheduledExecutor
             (reify ThreadFactory
               (newThread [_ r] (doto (Thread. ^Runnable r "registry-saver") (.setDaemon true)))))
        reg (->Registry (atom (load-file* f)) (Object.) (AtomicLong. 1) (atom []) f (AtomicBoolean.) ex)]
    (.scheduleWithFixedDelay ex ^Runnable #(try (save! reg) (catch Exception _)) 1 1 TimeUnit/SECONDS)
    reg))

(defn close! [{:keys [^ScheduledExecutorService saver] :as reg}]
  (.shutdown saver)
  (save! reg))

(defn publish-to!
  "Publishes reg_gen through `f` (a fn of the generation) now and after every
  write: a ring's header, before any client attaches."
  [{:keys [^AtomicLong gen publishers]} f]
  (swap! publishers conj f)
  (f (.get gen)))

(defn- write!
  "Applies write `f` (overlay -> [overlay version]) under the lock; on success
  bumps and publishes reg_gen before returning the version."
  [{:keys [state lock ^AtomicLong gen publishers ^AtomicBoolean dirty]} f]
  (locking lock
    (let [[o v] (f @state)]
      (reset! state o)
      (.set dirty true)
      (let [g (.incrementAndGet gen)] (doseq [pub @publishers] (pub g)))
      v)))

(def error-status
  "Overlay failures as ring statuses."
  {:not-found p/not-found :already-exists p/exists})

(defn- written
  "Applies write `f` (write!) and answers with the new version."
  [reg f]
  [p/ok (encode-version-reply (write! reg f))])

(defn serve
  "Answers registry request `req` (decode-request). Returns [status ^bytes reply]."
  [{:keys [state] :as reg} {:keys [op path] :as req}]
  (if-not (valid-path? path)
    [p/bad-request nil]
    (try
      (case op
        :lookup       (let [o @state [st below] (lookup o path)]
                        [p/ok (encode-lookup-reply (version o) st below)])
        :key          (let [o @state]
                        [p/ok (encode-key-reply (version o) (node o path))])
        :changed      (let [o @state]
                        [p/ok (encode-changed-reply (version o) (changed-since? o path (:subtree req) (:since req)))])
        :set-value    (written reg #(set-value % path (:name req) (:ty req) (:data req) (filetime-now)))
        :delete-value (written reg #(delete-value % path (:name req) (filetime-now)))
        :create-key   (written reg #(create-key % path (:volatile req) false (filetime-now)))
        :delete-key   (written reg #(delete-key % path (filetime-now)))
        :rename-key   (if (#{"." ".."} (:new-leaf req))
                        [p/bad-request nil]
                        (written reg #(rename-key % path (:new-leaf req) (filetime-now)))))
      (catch clojure.lang.ExceptionInfo e
        [(get error-status (::error (ex-data e)) p/bad-request) nil]))))
