(ns aether.director.proton.layout-test
  "vfs-proton/src/layout.rs's tests: the home's layout and the traversal guard."
  (:require [aether.director.proton.layout :as layout]
            [clojure.test :refer [deftest is testing]]))

(deftest at-places-everything-under-the-given-base
  (is (= "/srv/aebase/runtimes" (layout/runtimes "/srv/aebase")))
  (is (= "/srv/aebase/runtimes/GE-Proton11-6" (layout/runtime-dir "/srv/aebase" "GE-Proton11-6")))
  (is (= "/srv/aebase/downloads" (layout/downloads "/srv/aebase")))
  (is (= "/srv/aebase/runtimes" (layout/runtimes "/srv/aebase/")) "a trailing / is not doubled"))

(deftest sessions-places-each-session-under-the-sessions-directory
  (is (= "/srv/aebase/sessions" (layout/sessions "/srv/aebase")))
  (is (= "/srv/aebase/sessions/abc123" (layout/try-session-dir "/srv/aebase" "abc123"))))

(defn- refused? [f v]
  (try (f "/srv/aebase" v) false
       (catch clojure.lang.ExceptionInfo e (= :invalid-tag (::layout/error (ex-data e))))))

(deftest a-session-id-cannot-escape-the-sessions-directory
  (doseq [evil ["../../etc" ".." "a/../../b" "/absolute" "a/b" "" "a\\b" "C:\\x"]]
    (is (refused? layout/try-session-dir evil) (pr-str evil))))

(deftest a-tag-cannot-escape-the-runtimes-directory
  (doseq [evil ["../../etc" ".." "a/../../b" "/absolute" "a/b" nil]]
    (is (refused? layout/try-runtime-dir evil) (pr-str evil)))
  (is (= "/srv/aebase/runtimes/GE-Proton11-6" (layout/try-runtime-dir "/srv/aebase" "GE-Proton11-6"))))

(deftest the-home-comes-from-the-environment-in-order
  (testing "layout.rs:32-51"
    (is (= "/h" (layout/home {"VFS_HOME" "/h" "XDG_DATA_HOME" "/x" "HOME" "/u"})))
    (is (= "/x/aether-vfs" (layout/home {"XDG_DATA_HOME" "/x" "HOME" "/u"})))
    (is (= "/u/.local/share/aether-vfs" (layout/home {"HOME" "/u"})))
    (is (= "C:\\Users\\me\\AppData\\Local\\aether-vfs" (layout/home {"LOCALAPPDATA" "C:\\Users\\me\\AppData\\Local"})))
    (is (nil? (layout/home {})))))

(deftest join-drops-trailing-slashes-and-never-doubles-one
  (is (= "/a/b/c" (layout/join "/a" "b" "c")))
  (is (= "/a/b" (layout/join "/a//" "b")))
  (is (= "/a/b/c" (layout/join "/a/" "b/" "c")) "each piece, not just the base")
  (is (= "/x" (layout/join "/" "x")) "the root keeps its one slash"))

(deftest blank-and-drive-lettered-ids-are-not-plain-components
  (doseq [evil ["   " "C:" "c:x" "D:\\"]]
    (is (not (layout/valid-component? evil)) (pr-str evil))
    (is (refused? layout/try-session-dir evil) (pr-str evil)))
  (is (layout/valid-component? "GE-Proton11-6")))
