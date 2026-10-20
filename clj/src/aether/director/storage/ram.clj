(ns aether.director.storage.ram
  "The RAM tier: decompressed blocks, by (store file id, block index), in a
  sharded CLOCK cache with a byte budget (aether-storage's ram.rs).

  A hit is one ConcurrentHashMap lookup and a reference-bit write; inserts
  and evictions take their shard's lock. Blocks are byte arrays, never
  changed once cached: a writer invalidates a file's blocks and puts new
  ones."
  (:import (java.util ArrayDeque Arrays)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.concurrent.atomic AtomicLong)))

(set! *warn-on-reflection* true)

(deftype Key [^bytes id ^long block ^int hash]
  Object
  (hashCode [_] hash)
  (equals [_ o]
    (and (instance? Key o)
         (= block (.-block ^Key o))
         (Arrays/equals id ^bytes (.-id ^Key o)))))

(defn- key ^Key [^bytes id ^long block]
  (Key. id block (unchecked-int (+ (* 31 (Arrays/hashCode id)) (Long/hashCode block)))))

(definterface ISlot
  (^bytes touch [])
  (^boolean referenced [])
  (clear []))

(deftype Slot [^bytes data ^:volatile-mutable ^boolean ref]
  ISlot
  (touch [_] (set! ref (boolean true)) data)
  (referenced [_] ref)
  (clear [_] (set! ref (boolean false))))

(deftype Shard [^ConcurrentHashMap slots ^ArrayDeque clock ^AtomicLong bytes ^long budget])

(defrecord Ram [shards ^long mask ^AtomicLong hits ^AtomicLong misses])

(defn ram
  "A RAM tier of about `budget` bytes for blocks of `block-size`."
  [^long budget ^long block-size]
  (let [blocks (max 1 (quot budget block-size))
        n      (loop [n 64] (if (and (> n 1) (< (quot blocks n) 8)) (recur (quot n 2)) n))]
    (->Ram (vec (repeatedly n #(Shard. (ConcurrentHashMap.) (ArrayDeque.) (AtomicLong.) (quot budget n))))
           (dec n) (AtomicLong.) (AtomicLong.))))

(defn- shard ^Shard [^Ram r ^Key k] (nth (.-shards r) (bit-and (.hashCode k) (.-mask r))))

(defn get-block
  "The cached block, or nil."
  ^bytes [^Ram r ^bytes id ^long block]
  (let [k (key id block)
        s (.get ^ConcurrentHashMap (.-slots (shard r k)) k)]
    (if s
      (do (.incrementAndGet ^AtomicLong (.-hits r)) (.touch ^Slot s))
      (do (.incrementAndGet ^AtomicLong (.-misses r)) nil))))

(defn put-block!
  "Caches `data` (not to be changed afterwards) as block `block` of `id`."
  [^Ram r ^bytes id ^long block ^bytes data]
  (let [k  (key id block)
        sh (shard r k)
        ^ConcurrentHashMap slots (.-slots sh)
        ^ArrayDeque clock (.-clock sh)
        ^AtomicLong used (.-bytes sh)]
    (locking sh
      (if-let [^Slot old (.put slots k (Slot. data false))]
        (.addAndGet used (- (alength ^bytes (.-data old))))
        (.addLast clock k))
      (.addAndGet used (alength data))
      ;; the clock hand: a referenced block gets another lap, an unreferenced one goes
      (loop [laps (* 2 (.size clock))]
        (when (and (> (.get used) (.-budget sh)) (pos? laps) (not (.isEmpty clock)))
          (let [^Key victim (.pollFirst clock)
                ^Slot s     (.get slots victim)]
            (cond
              (nil? s) nil
              (and (.referenced ^Slot s) (not= victim k)) (do (.clear ^Slot s) (.addLast clock victim))
              (= victim k) (.addLast clock victim)
              :else (do (.remove slots victim) (.addAndGet used (- (alength ^bytes (.-data s))))))
            (recur (dec laps))))))))

(defn invalidate!
  "Drops every cached block of file `id`."
  [^Ram r ^bytes id]
  (doseq [^Shard sh (.-shards r)]
    (locking sh
      (let [it (.iterator ^ArrayDeque (.-clock sh))]
        (while (.hasNext it)
          (let [^Key k (.next it)]
            (when (Arrays/equals id ^bytes (.-id k))
              (.remove it)
              (when-let [^Slot s (.remove ^ConcurrentHashMap (.-slots sh) k)]
                (.addAndGet ^AtomicLong (.-bytes sh) (- (alength ^bytes (.-data s))))))))))))

(defn stats [^Ram r]
  {:hits (.get ^AtomicLong (.-hits r)) :misses (.get ^AtomicLong (.-misses r))
   :bytes (reduce + (map #(.get ^AtomicLong (.-bytes ^Shard %)) (.-shards r)))})
