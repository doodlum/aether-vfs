(ns aether.director.provider.zip
  "Stored (uncompressed) zip archives as director content. The whole archive is
  memory-mapped once; each entry is a slice of that mapping, so a read
  anywhere in a 16 GB archive is one copy with no lock and no system call.
  ZIP64 is supported. Compressed entries are refused, as in vfs-zip: random
  access into them would mean decompressing from the start."
  (:import (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.charset StandardCharsets)
           (java.nio.file OpenOption Path StandardOpenOption)))

(set! *warn-on-reflection* true)

(defn- u16 ^long [^MemorySegment s ^long off] (Short/toUnsignedLong (.get s ValueLayout/JAVA_SHORT_UNALIGNED off)))
(defn- u32 ^long [^MemorySegment s ^long off] (Integer/toUnsignedLong (.get s ValueLayout/JAVA_INT_UNALIGNED off)))
(defn- u64 ^long [^MemorySegment s ^long off] (.get s ValueLayout/JAVA_LONG_UNALIGNED off))

(defn- end-of-central-directory
  "{:count :offset} of the central directory, from the EOCD record (searched
  back from the end, past any comment) and, when its fields are saturated,
  the ZIP64 record its locator points to."
  [^MemorySegment z]
  (let [n    (.byteSize z)
        eocd (loop [i (- n 22)]
               (cond (< i (max 0 (- n 22 65535))) (throw (ex-info "not a zip: no end-of-central-directory record" {}))
                     (= 0x06054b50 (u32 z i)) i
                     :else (recur (dec i))))
        cnt  (u16 z (+ eocd 10))
        off  (u32 z (+ eocd 16))]
    (if (or (= cnt 0xFFFF) (= off 0xFFFFFFFF))
      (let [loc (- eocd 20)]
        (when-not (and (>= loc 0) (= 0x07064b50 (u32 z loc)))
          (throw (ex-info "ZIP64 archive without a ZIP64 locator" {})))
        (let [rec (u64 z (+ loc 8))]
          (when-not (= 0x06064b50 (u32 z rec)) (throw (ex-info "bad ZIP64 end-of-central-directory record" {})))
          {:count (u64 z (+ rec 32)) :offset (u64 z (+ rec 48))}))
      {:count cnt :offset off})))

(defn- zip64-extra
  "Fills the saturated sizes/offset of a central entry from its ZIP64 extra
  field (id 1), which holds them in this order, only those that are saturated."
  [^MemorySegment z ^long extra ^long extra-len {:keys [size csize local] :as e}]
  (loop [p extra]
    (if (>= p (+ extra extra-len))
      e
      (let [id (u16 z p) len (u16 z (+ p 2))]
        (if (= id 1)
          (let [at    (volatile! (+ p 4))
                take! (fn [] (let [v (u64 z @at)] (vswap! at + 8) v))
                size  (if (= size 0xFFFFFFFF) (take!) size)
                csize (if (= csize 0xFFFFFFFF) (take!) csize)
                local (if (= local 0xFFFFFFFF) (take!) local)]
            (assoc e :size size :csize csize :local local))
          (recur (+ p 4 len)))))))

(defn entries
  "The archive's file entries: [{:name :method :size :local} …], in central
  directory order. Directories (names ending in /) are left out."
  [^MemorySegment z]
  (let [{n :count offset :offset} (end-of-central-directory z)]
    (loop [i 0 p (long offset) out (transient [])]
      (if (= i n)
        (persistent! out)
        (do
          (when-not (= 0x02014b50 (u32 z p)) (throw (ex-info "bad central directory entry" {:offset p})))
          (let [name-len  (u16 z (+ p 28))
                extra-len (u16 z (+ p 30))
                cmt-len   (u16 z (+ p 32))
                nm        (let [b (byte-array name-len)]
                            (MemorySegment/copy z ValueLayout/JAVA_BYTE (+ p 46) b (int 0) (int name-len))
                            ;; general-purpose bit 11: UTF-8 names; otherwise CP437, which
                            ;; for the ASCII names games use is the same
                            (String. b StandardCharsets/UTF_8))
                e         (zip64-extra z (+ p 46 name-len) extra-len
                                       {:name nm :method (u16 z (+ p 10))
                                        :csize (u32 z (+ p 20)) :size (u32 z (+ p 24)) :local (u32 z (+ p 42))})]
            (recur (inc i) (+ p 46 name-len extra-len cmt-len)
                   (if (.endsWith ^String nm "/") out (conj! out e)))))))))

(defn- data-offset
  "Where an entry's bytes start: past its local header, whose name and extra
  lengths can differ from the central directory's."
  ^long [^MemorySegment z ^long local]
  (when-not (= 0x04034b50 (u32 z local)) (throw (ex-info "bad local file header" {:offset local})))
  (+ local 30 (u16 z (+ local 26)) (u16 z (+ local 28))))

(defn- common-top-folder
  "The one folder every entry is inside, if there is one (an archive of
  \"Skyrim Special Edition/…\"), so the game's own files appear at the root."
  [names]
  (let [tops (into #{} (map #(let [i (.indexOf ^String % "/")] (when (pos? i) (subs % 0 (inc i))))) names)]
    (when (= 1 (count tops)) (first tops))))

(defn open
  "Maps the Stored zip at `path` in `arena` and returns [[path slice] …] for
  aether.director.provider.tree/build, with a common top folder stripped. Throws on a
  compressed entry, naming it."
  [^Arena arena path]
  (with-open [ch (FileChannel/open (Path/of (str path) (make-array String 0))
                                   (into-array OpenOption [StandardOpenOption/READ]))]
    (let [z      (.map ch FileChannel$MapMode/READ_ONLY 0 (.size ch) arena)
          es     (entries z)
          prefix (common-top-folder (map :name es))]
      (vec (for [{nm :name :keys [method size local]} es]
             (do (when-not (zero? (long method))
                   (throw (ex-info (str "compressed entry (method " method "): " nm
                                        "; serve only Stored archives") {:entry nm :method method})))
                 [(if prefix (subs nm (count prefix)) nm)
                  (.asSlice z (data-offset z local) (long size))]))))))
