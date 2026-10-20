(ns build
  (:require [clojure.tools.build.api :as b]))

(defn javac
  "Compiles java/ into target/classes."
  [_]
  (b/javac {:src-dirs   ["java"]
            :class-dir  "target/classes"
            :basis      (b/create-basis {:project "deps.edn"})
            :javac-opts ["--release" "26"]}))

(defn jar
  "An uberjar of the director (Clojure AOT-compiled, direct-linked):
  target/director.jar, runnable as `java -cp target/director.jar aether.director.main`.
  A JVM AOT cache needs a jar: it refuses source directories on the classpath."
  [_]
  (javac nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/compile-clj {:basis        basis
                    :ns-compile   '[aether.director.main]
                    :class-dir    "target/classes"
                    :java-opts    ["--enable-native-access=ALL-UNNAMED"]
                    :compile-opts {:direct-linking true}})
    (b/uber {:class-dir "target/classes" :uber-file "target/director.jar" :basis basis
             :main 'aether.director.main})))
