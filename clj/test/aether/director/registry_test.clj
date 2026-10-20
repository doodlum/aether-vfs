(ns aether.director.registry-test
  "The registry overlay, ported from vfs-registry's overlay tests; its wire
  encoding against the golden vectors; persistence; the director's serving
  and reg_gen."
  (:require [aether.director.golden-test :refer [vectors]]
            [aether.director.registry :as reg]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)))

(def K "\\Registry\\Machine\\Software\\Mod")
(def SZ 1)

(defn- hex [^bytes b] (apply str (map #(format "%02x" (bit-and % 0xff)) b)))
(defn- unhex ^bytes [^String h] (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16)) (partition 2 h))))
(defn- b ^bytes [s] (.getBytes ^String s "UTF-8"))

(defmacro ^:private err [form] `(try ~form nil (catch clojure.lang.ExceptionInfo e# (::reg/error (ex-data e#)))))

(defn- run
  "Applies writes in order to an empty overlay: each is [f & args] with the
  overlay first and `now` last. Returns the overlay."
  [& ops]
  (reduce (fn [o [f & args]] (first (apply f o args))) reg/empty-overlay ops))

(defn- val-of [o p n] (some #(when (= (reg/fold n) (reg/fold (:name %))) %) (:values (reg/node o p))))

;; --- the overlay (vfs-registry/src/overlay.rs tests) ----------------------------------------

(deftest lookup-is-case-insensitive-and-spelling-preserved
  (let [o (run [reg/create-key K false false 1]
               [reg/set-value (.toLowerCase ^String K) "MyVal" SZ (b "a") 2]
               [reg/set-value K "MYVAL" SZ (b "b") 3])]
    (is (= :present-created (first (reg/lookup o (.toUpperCase ^String K)))))
    (is (= ["Mod" :present] (get-in (reg/node o "\\Registry\\Machine\\Software") [:children "mod"])))
    (let [n (reg/node o K)]
      (is (= 1 (count (:values n))))
      (is (= "MyVal" (:name (first (:values n)))) "the first spelling stays")
      (is (= "b" (String. ^bytes (:data (first (:values n))))))
      (is (= 3 (:last-write n))))))

(deftest missing-ancestors-are-present-not-created
  (let [o (run [reg/set-value K "v" SZ (b "x") 1])]
    (is (= :present (first (reg/lookup o "\\Registry\\Machine\\Software"))))
    (is (= :present (first (reg/lookup o "\\Registry"))))
    (is (= :present (first (reg/lookup o K))))
    (is (second (reg/lookup o "\\Registry\\Machine\\Software")))
    (is (not (second (reg/lookup o K))))
    (is (= [:absent false] (reg/lookup o "\\Registry\\Machine\\Other")))))

(deftest set-delete-set-same-value
  (let [o (run [reg/set-value K "V" SZ (b "1") 1] [reg/delete-value K "v" 2])]
    (is (nil? (val-of o K "V")))
    (is (= ["v"] (:value-tombs (reg/node o K))))
    (let [o (first (reg/set-value o K "V" SZ (b "2") 3))]
      (is (= "2" (String. ^bytes (:data (val-of o K "V")))))
      (is (empty? (:value-tombs (reg/node o K)))))))

(deftest delete-value-with-no-overlay-copy-still-hides-real
  (is (= ["real"] (:value-tombs (reg/node (run [reg/delete-value K "Real" 1]) K)))))

(deftest delete-key-tombstones-and-drops-subtree
  (let [sub (str K "\\Sub\\Deep")
        o   (run [reg/set-value sub "v" SZ (b "x") 1] [reg/delete-key K 2])]
    (is (= [:tombstoned false] (reg/lookup o K)))
    (is (nil? (reg/node o K)))
    (is (nil? (reg/node o sub)))
    (is (= :tombstoned (first (reg/lookup o sub))))
    (is (= ["Mod" :tomb] (get-in (reg/node o "\\Registry\\Machine\\Software") [:children "mod"])))
    (is (= :not-found (err (reg/delete-key o K 3))))
    (is (= :invalid-path (err (reg/delete-key o "\\Registry" 3))))))

(deftest writing-under-a-tombstone-revives-the-chain
  (let [deep (str K "\\Sub")
        o    (run [reg/set-value K "old" SZ (b "x") 1] [reg/delete-key K 2] [reg/set-value deep "v" SZ (b "y") 3])]
    (is (= :present-created (first (reg/lookup o K))))
    (is (= :present-created (first (reg/lookup o deep))))
    (is (nil? (val-of o K "old")))
    (is (= :present (second (get-in (reg/node o "\\Registry\\Machine\\Software") [:children "mod"]))))
    (let [o (-> o (reg/delete-key K 4) first (reg/create-key K true true 5) first)]
      (is (= :present-created (first (reg/lookup o K))) "create over a tombstone revives, created-here")
      (is (:volatile (reg/node o K))))))

(deftest create-key-existing-is-already-exists
  (let [o (run [reg/create-key K false true 1])]
    (is (= :present (first (reg/lookup o K))))
    (is (= :already-exists (err (reg/create-key o K false true 2))))))

(deftest limits
  (let [o (run [reg/create-key (str K "\\" (apply str (repeat 255 "k"))) false false 1])]
    (is (= :name-too-long (err (reg/create-key o (str K "\\" (apply str (repeat 256 "k"))) false false 1))))
    (is (some? (reg/set-value o K (apply str (repeat 16383 "n")) SZ (byte-array 0) 1)))
    (is (= :name-too-long (err (reg/set-value o K (apply str (repeat 16384 "n")) SZ (byte-array 0) 1))))
    (is (= :name-too-long (err (reg/delete-value o K (apply str (repeat 16384 "n")) 1))))
    (is (some? (reg/set-value o K "big" SZ (byte-array (bit-shift-left 1 20)) 1)))
    (is (= :data-too-large (err (reg/set-value o K "big" SZ (byte-array (inc (bit-shift-left 1 20))) 1))))
    (is (= :invalid-path (err (reg/rename-key o K "a\\b" 1))))
    (is (= :invalid-path (err (reg/set-value o "\\Device\\x" "v" SZ (byte-array 0) 1))))))

(deftest depth-cap-refuses-513-and-accepts-512
  (let [chain (fn [n] (apply str "\\Registry" (for [i (range n)] (str "\\k" i))))
        o     (run [reg/create-key (chain 512) false false 1])]
    (is (= :too-deep (err (reg/create-key o (chain 513) false false 2))))
    (is (= 1 (reg/version o)))))

(deftest version-bumps-once-per-mutation
  (let [o0 reg/empty-overlay
        [o v1] (reg/set-value o0 K "a" SZ (byte-array 0) 1)
        [o v2] (reg/delete-value o K "a" 2)
        [o v3] (reg/create-key o (str K "\\C") false false 3)
        [o v4] (reg/rename-key o (str K "\\C") "D" 4)
        [o v5] (reg/delete-key o (str K "\\D") 5)]
    (is (= [0 1 2 3 4 5] [(reg/version o0) v1 v2 v3 v4 v5]))
    (is (= :not-found (err (reg/rename-key o (str K "\\Nope") "X" 6))) "a failed write doesn't bump")))

(deftest rename-moves-the-subtree
  (let [a (str K "\\A")
        o (run [reg/set-value (str a "\\Child") "v" SZ (b "x") 1] [reg/set-value a "top" SZ (b "t") 1] [reg/rename-key a "B" 2])]
    (is (= :tombstoned (first (reg/lookup o a))))
    (is (= "x" (String. ^bytes (:data (val-of o (str K "\\B\\Child") "v")))))
    (is (= :present-created (first (reg/lookup o (str K "\\B")))))
    (is (= :tomb (second (get-in (reg/node o K) [:children "a"]))))
    (is (= ["B" :present] (get-in (reg/node o K) [:children "b"])))
    (let [o (first (reg/create-key o (str K "\\E") false false 3))]
      (is (= :already-exists (err (reg/rename-key o (str K "\\B") "e" 4))))
      (is (= :not-found (err (reg/rename-key o a "Z" 4))))
      (let [o (-> o (reg/rename-key (str K "\\E") "e2" 5) first (reg/rename-key (str K "\\e2") "E2" 6) first)]
        (is (= ["E2" :present] (get-in (reg/node o K) [:children "e2"])) "a case-only rename respells, no tombstone")
        (is (some? (reg/node o (str K "\\e2"))))))))

(deftest changed-since-subtree-and-not
  (let [deep (str K "\\Sub\\Deep")
        [o v1] (reg/set-value reg/empty-overlay deep "v" SZ (b "x") 1)]
    (is (reg/changed-since? o deep false 0))
    (is (not (reg/changed-since? o deep false v1)))
    (is (reg/changed-since? o (str K "\\Sub") false 0))
    (is (reg/changed-since? o K false 0))
    (let [[o v2] (reg/set-value o deep "w" SZ (b "y") 2)]
      (is (not (reg/changed-since? o K false v1)))
      (is (reg/changed-since? o K true v1))
      (is (reg/changed-since? o "\\Registry\\Machine" true v1))
      (is (not (reg/changed-since? o "\\Registry\\Machine" false v1)))
      (is (not (reg/changed-since? o K true v2)))
      (is (not (reg/changed-since? o "\\Registry\\Machine\\Nothing" true 0)))
      (let [[o v3] (reg/delete-key o (str K "\\Sub") 3)]
        (is (reg/changed-since? o (str K "\\Sub") false v2))
        (is (reg/changed-since? o K false v2))
        (is (reg/changed-since? o deep true v2))
        (is (not (reg/changed-since? o deep true v3)))
        (let [[o v4] (reg/delete-value o K "x" 4)]
          (is (reg/changed-since? o K false v3))
          (is (not (reg/changed-since? o K false v4))))))))

(deftest changed-since-sees-deletions-after-revive-and-rename
  (let [sub (str K "\\Sub")
        [o v1] (reg/set-value reg/empty-overlay sub "v" SZ (b "x") 1)
        [o v2] (reg/delete-key o K 2)
        [o v3] (reg/set-value o K "n" SZ (b "y") 3)]
    (is (= :absent (first (reg/lookup o sub))))
    (is (reg/changed-since? o sub false v1))
    (is (reg/changed-since? o sub true v1))
    (is (reg/changed-since? o K false v2))
    (is (not (reg/changed-since? o sub false v3)))
    (let [[o v4] (reg/delete-key o K 4)
          [o _]  (reg/create-key o K false true 5)]
      (is (reg/changed-since? o sub true v3))
      (is (not (reg/changed-since? o sub true v4)))))
  (let [a (str K "\\A") deep (str a "\\Deep")
        [o v1] (reg/set-value reg/empty-overlay deep "v" SZ (b "x") 1)
        [o v2] (reg/rename-key o a "B" 2)
        [o v3] (reg/set-value o a "w" SZ (b "y") 3)]
    (is (reg/changed-since? o deep true v1))
    (is (reg/changed-since? o a false v2))
    (is (not (reg/changed-since? o deep true v3)))
    (is (reg/changed-since? o (str K "\\B") false v1))))

(deftest deleted-at-is-bounded-and-stays-conservative
  (let [base "\\Registry\\Machine\\Software"
        o (reduce (fn [o i] (let [p (str base "\\k" i)]
                              (-> o (reg/create-key p false false 1) first (reg/delete-key p 1) first)))
                  (first (reg/create-key reg/empty-overlay base false false 1))
                  (range (+ reg/max-deleted-at 5)))]
    (is (<= (count (:deleted-at o)) reg/max-deleted-at))
    (is (reg/changed-since? o (str base "\\k0") false 0))
    (is (not (reg/changed-since? o (str base "\\never") false (reg/version o))))))

;; --- the wire --------------------------------------------------------------------------------

(deftest golden-requests-decode
  (let [v @vectors dec (fn [op k] (reg/decode-request op (unhex (v k))))]
    (is (= {:op :lookup :path "\\Registry\\Machine\\Software"} (dec 15 "reg-path")))
    (let [r (dec 17 "reg-set-value")]
      (is (= [:set-value "\\Registry\\Machine\\Software" "Name" 3] ((juxt :op :path :name :ty) r)))
      (is (= [9 8 7] (vec (:data r)))))
    (is (= {:op :delete-value :path "\\Registry\\Machine\\Software" :name "Name"} (dec 18 "reg-delete-value")))
    (is (= {:op :create-key :path "\\Registry\\Machine\\Software" :volatile true} (dec 19 "reg-create-key-volatile")))
    (is (= {:op :rename-key :path "\\Registry\\Machine\\Software" :new-leaf "Other"} (dec 21 "reg-rename-key")))
    (is (= {:op :changed :path "\\Registry\\Machine" :subtree true :since 77} (dec 22 "reg-changed-subtree-since77")))
    (testing "decoding is strict"
      (is (thrown? Exception (reg/decode-request 15 (byte-array (concat (unhex (v "reg-path")) [0])))) "trailing bytes")
      (is (thrown? Exception (reg/decode-request 19 (byte-array (concat (unhex (v "reg-path")) [2])))) "a bool must be 0 or 1"))))

(deftest golden-replies-encode
  (let [v @vectors]
    (is (= (v "reg-changed-reply-true-5") (hex (reg/encode-changed-reply 5 true))))
    (is (= (v "reg-version-reply-9") (hex (reg/encode-version-reply 9))))
    (is (= (v "reg-lookup-reply-state1-below-42") (hex (reg/encode-lookup-reply 42 :present true))))
    (is (= (v "reg-key-reply-none-13") (hex (reg/encode-key-reply 13 nil))))
    (is (= (v "reg-key-reply-sample-node-12")
           (hex (reg/encode-key-reply 12 {:created true :volatile true :last-write 0x0123456789ABCDEF
                                          :values [{:name "Path" :ty 1 :data (byte-array [1 2 3])}
                                                   {:name "" :ty 4 :data (byte-array 0)}]
                                          :value-tombs ["gone"]
                                          :children (into (sorted-map) {"dead" ["Dead" :tomb] "sub" ["Sub" :present]})}))))))

;; --- persistence -----------------------------------------------------------------------------

(deftest overlay-reg-round-trips
  (let [o  (run [reg/set-value K "v" SZ (b "x") 1]
                [reg/create-key (str K "\\Sub") true false 2]      ; volatile: not saved
                [reg/delete-key (str K "\\Gone") 3]
                [reg/delete-value K "Real" 4])
        o2 (reg/decode (reg/encode o))]
    (is (= 1 (reg/version o2)) "a loaded overlay restarts at version 1")
    (let [plain #(-> (reg/node % K) (dissoc :children) (update :values (partial mapv (fn [v] (update v :data vec)))))]
      (is (= (plain o) (plain o2))))
    (is (= :tombstoned (first (reg/lookup o2 (str K "\\Gone")))))
    (is (nil? (reg/node o2 (str K "\\Sub"))) "volatile keys aren't persisted")
    (is (= (seq (reg/encode o2)) (seq (reg/encode (reg/decode (reg/encode o2))))) "stable")
    (testing "damage is refused"
      (let [bytes (reg/encode o)]
        (aset-byte bytes 20 (unchecked-byte (inc (aget bytes 20))))
        (is (thrown? Exception (reg/decode bytes)))))))

;; --- the director's registry -------------------------------------------------------------------

(deftest serving-publishes-reg-gen-after-every-write
  (let [dir (.toFile (Files/createTempDirectory "aether-reg" (make-array java.nio.file.attribute.FileAttribute 0)))
        r   (reg/registry dir)
        seen (atom [])
        _   (reg/publish-to! r #(swap! seen conj %))
        serve (fn [op & kvs] (first (reg/serve r (apply hash-map :op op kvs))))]
    (is (= [1] @seen) "the current generation is published on attach")
    (is (= 0 (serve :set-value :path K :name "v" :ty SZ :data (b "x"))))
    (is (= 0 (serve :lookup :path K)))
    (is (= [1 2] @seen) "a write bumps it; a read doesn't")
    (is (= -10 (serve :create-key :path K :volatile false)) "EXISTS")
    (is (= -1 (serve :rename-key :path (str K "\\Nope") :new-leaf "X")) "NOT_FOUND")
    (is (= -3 (serve :rename-key :path K :new-leaf "..")) "BAD_REQUEST")
    (is (= -3 (serve :lookup :path "Registry\\Machine")) "a path that isn't canonical")
    (is (= -3 (serve :lookup :path "\\Registry\\Machine\\\\x")))
    (is (= [1 2] @seen) "refused writes don't bump it")
    (reg/close! r)
    (is (.isFile (io/file dir "overlay.reg")))
    (let [r2 (reg/registry dir)]
      (is (= "x" (String. ^bytes (:data (val-of @(:state r2) K "v")))) "a new session loads the saved overlay")
      (reg/close! r2))))
