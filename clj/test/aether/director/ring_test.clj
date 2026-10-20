(ns aether.director.ring-test
  "The server's request path, driven in-process by the warm-up (no section,
  no other process). The cross-process check is rust/'s vfs-ring-bench."
  (:require [aether.director.ring :as ring]
            [aether.director.tree :as tree]
            [clojure.test :refer [deftest is testing]])
  (:import (java.lang.foreign Arena MemorySegment)))

(defn- seg [^bytes b]
  (doto (.allocate (Arena/ofAuto) (alength b)) (.copyFrom (MemorySegment/ofArray b))))

(deftest warm-up-drives-every-read-handler
  (let [t (tree/build [["Data/Skyrim.esm" (seg (byte-array 10000))] ["readme.txt" (seg (.getBytes "hi"))]])]
    (testing "each round is 8 requests, each checked"
      (is (= 24 (:requests (ring/warm-up! t {:rounds 3})))))
    (testing "an empty tree (nothing mounted yet) still warms the directory path"
      (is (= 9 (:requests (ring/warm-up! (tree/build []) {:rounds 3})))))))
