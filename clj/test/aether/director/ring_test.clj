(ns aether.director.ring-test
  "The server's request path, driven in-process by the warm-up (no section,
  no other process). The cross-process check is rust/'s vfs-ring-bench."
  (:require [aether.director.golden-test :refer [vectors]]
            [aether.director.proto :as p]
            [aether.director.registry :as registry]
            [aether.director.ring :as ring]
            [aether.director.tree :as tree]
            [clojure.test :refer [deftest is testing]])
  (:import (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio.file Files)))

(defn- seg [^bytes b]
  (doto (.allocate (Arena/ofAuto) (alength b)) (.copyFrom (MemorySegment/ofArray b))))

(deftest warm-up-drives-every-read-handler
  (let [t (tree/build [["Data/Skyrim.esm" (seg (byte-array 10000))] ["readme.txt" (seg (.getBytes "hi"))]])]
    (testing "each round is 9 requests, each checked"
      (is (= 27 (:requests (ring/warm-up! t {:rounds 3})))))
    (testing "an empty tree (nothing mounted yet) still warms the directory path"
      (is (= 9 (:requests (ring/warm-up! (tree/build []) {:rounds 3})))))))

;; --- the registry, through a real section ------------------------------------------

(defn- unhex ^bytes [^String h] (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16)) (partition 2 h))))

(defn- call
  "Submits one request to slot 0 of a started server, as the shim would, and
  waits for it: [status reply-bytes]."
  [{:keys [section stride]} op ^bytes payload]
  (let [^MemorySegment seg (.view ^aether.director.Native$Section section)
        state p/sh-state base p/ring-header-size]
    (#'ring/request! seg stride 0 op 0 payload)
    (loop [n 0]
      (when (and (< n 5000) (not= p/st-completed (.get seg ValueLayout/JAVA_INT_UNALIGNED (long (+ base state)))))
        (Thread/sleep 1) (recur (inc n))))
    (let [st  (.get seg ValueLayout/JAVA_INT_UNALIGNED (long (+ base p/sh-status)))
          len (.get seg ValueLayout/JAVA_INT_UNALIGNED (long (+ base p/sh-payload-len)))
          out (byte-array len)]
      (MemorySegment/copy seg ValueLayout/JAVA_BYTE (long (+ base p/slot-header-size)) out 0 len)
      (.set seg ValueLayout/JAVA_INT_UNALIGNED (long (+ base state)) (int p/st-free))
      [st (vec out)])))

(defn- reg-gen [{:keys [section]}]
  (.get (.view ^aether.director.Native$Section section) ValueLayout/JAVA_LONG_UNALIGNED (long p/rh-reg-gen)))

(deftest registry-ops-over-the-ring
  (let [v   @vectors
        dir (.toFile (Files/createTempDirectory "aether-ring-reg" (make-array java.nio.file.attribute.FileAttribute 0)))
        reg (registry/registry dir)
        t   (tree/build [])
        srv (ring/start t {:name (str "Local\\vfs_ring_clj_test_" (System/nanoTime)) :workers 2 :warm-up 0 :registry reg})
        bare (ring/start t {:name (str "Local\\vfs_ring_clj_test_" (System/nanoTime)) :workers 1 :warm-up 0})]
    (try
      (is (:registry? srv))
      (is (= 1 (reg-gen srv)) "reg_gen is published when the ring starts")
      (testing "a write is served, bumps reg_gen, and is seen by a lookup"
        (is (= [0 (vec (registry/encode-version-reply 1))] (call srv 17 (unhex (v "reg-set-value")))))
        (is (= 2 (reg-gen srv)))
        (is (= [0 (vec (registry/encode-lookup-reply 1 :present false))] (call srv 15 (unhex (v "reg-path")))))
        (is (= 2 (reg-gen srv)) "reads don't bump it"))
      (testing "refusals"
        (is (= p/bad-request (first (call srv 15 (byte-array [1 2 3])))) "doesn't decode")
        (is (= -10 (first (call srv 19 (unhex (v "reg-create-key-volatile"))))) "EXISTS")
        (is (= 2 (reg-gen srv))))
      (testing "without a registry, the ops aren't supported and reg_gen stays 0"
        (is (= p/not-supported (first (call bare 15 (unhex (v "reg-path"))))))
        (is (= 0 (reg-gen bare)))
        (is (not (:registry? bare))))
      (finally
        (ring/stop srv) (ring/stop bare) (registry/close! reg)))))
