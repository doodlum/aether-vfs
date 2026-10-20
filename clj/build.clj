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
  ;; Its own class dir: AOT classes in target/classes (the dev classpath) would
  ;; shadow the sources, and a redefined deftype would then fail to cast.
  (b/delete {:path "target/jar-classes"})
  (b/javac {:src-dirs ["java"] :class-dir "target/jar-classes" :javac-opts ["--release" "26"]
            :basis (b/create-basis {:project "deps.edn"})})
  (b/copy-dir {:src-dirs ["resources"] :target-dir "target/jar-classes"})   ; the native library
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/compile-clj {:basis        basis
                    :ns-compile   '[aether.director.main aether.director.run]
                    :class-dir    "target/jar-classes"
                    :java-opts    ["--enable-native-access=ALL-UNNAMED"]
                    :compile-opts {:direct-linking true}})
    (b/uber {:class-dir "target/jar-classes" :uber-file "target/director.jar" :basis basis
             :main 'aether.director.main})))
