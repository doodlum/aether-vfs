(ns aether.director.golden-test
  "The director's encodings against rust/'s golden vectors
  (rust/crates/vfs-protocol/tests/golden/vectors.txt): the same bytes the
  shim's own codecs are tested against."
  (:require [aether.director.launch :as launch]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def vectors
  (delay (into {} (for [line (str/split-lines (slurp (io/file ".." "rust" "crates" "vfs-protocol" "tests" "golden" "vectors.txt")))
                        :let [[k v] (str/split (str/trim line) #"\s+")]
                        :when v]
                    [k v]))))

(defn- hex [^bytes b] (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(deftest shim-config-v2
  (is (= (@vectors "shim-config-v2-root-runtime")
         (hex (launch/shim-config "C:\\GameLayers\\runtime"))))
  (is (= (@vectors "shim-config-v2-one-static-import")
         (hex (launch/shim-config "C:\\GameLayers\\runtime" [["d3d11.dll" "C:\\GameLayers\\d3d11.dll"]])))))
