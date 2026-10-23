(ns aether.director.test-support
  "What a test needs to run, and the one way a test skips when it can't.

    (requires [(windows) (prerequisite (.isFile f) \"f not built (cargo …)\")]
      body…)

  runs `body` when every requirement holds. Otherwise the first unmet one is
  reported: the test prints `SKIP <test>: <why>` and is reported pending,
  never passed. Two kinds, as rust/crates/vfs-embed/tests/support/proton.rs
  draws them:

    the wrong OS        always a skip: a Windows-only test on Linux can't run
                        however the machine is provisioned
    a prerequisite      a skip, or with VFS_TEST_REQUIRE_ALL=1 a failure: a
                        fully provisioned machine (CI) must run the test

  Paths a test needs from the repo come from repo-root, never the working
  directory."
  (:require [aether.director.platform :as platform]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t])
  (:import (java.io File)))

(def ^:dynamic *require-all*
  "Overrides VFS_TEST_REQUIRE_ALL when not nil (for the helper's own tests)."
  nil)

(defn require-all?
  "VFS_TEST_REQUIRE_ALL is set to something other than empty or 0 (require_all, proton.rs:80-82)."
  []
  (if (some? *require-all*)
    (boolean *require-all*)
    (let [v (System/getenv "VFS_TEST_REQUIRE_ALL")]
      (boolean (and v (not= "" v) (not= "0" v))))))

(defn windows
  "nil on Windows, else the reason a Windows-only test skips."
  []
  (when-not (= :platform/windows (platform/current))
    {:skip/kind :os :why "Windows only (named sections, events, the injector)"}))

(defn linux
  "nil on Linux (and macOS, which has the same POSIX behaviour), else the
  reason a Linux-only test skips."
  []
  (when-not (#{:platform/linux :platform/macos} (platform/current))
    {:skip/kind :os :why "Linux only (symlinks without privilege, POSIX modes)"}))

(defn prerequisite
  "nil when `ok?`, else a missing prerequisite, `why` saying how to provide it."
  [ok? why]
  (when-not ok? {:skip/kind :prerequisite :why why}))

(defn repo-root
  "The aether-vfs checkout this test file lives in: the parent of clj/, found
  from this namespace's source on the classpath, so no test depends on the
  working directory."
  ^File []
  (let [src (io/resource "aether/director/test_support.clj")]
    (if (and src (= "file" (.getProtocol src)))
      ;; <root>/clj/test/aether/director/test_support.clj
      (-> (io/file (.toURI src)) .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile)
      (.getParentFile (.getCanonicalFile (io/file (System/getProperty "user.dir")))))))

(defn natives
  "The directory holding rust/'s release natives: $VFS_NATIVES, else
  <repo>/rust/target/release."
  ^File []
  (if-let [d (not-empty (System/getenv "VFS_NATIVES"))]
    (io/file d)
    (io/file (repo-root) "rust" "target" "release")))

(defn- test-name []
  (or (some->> t/*testing-vars* first meta ((juxt :ns :name)) (str/join "/")) "test"))

(defn skip!
  "Reports `unmet` (from windows, linux or prerequisite) for the running test."
  [{:keys [skip/kind why]}]
  (let [test (test-name)]
    (if (and (= :prerequisite kind) (require-all?))
      (t/is false (str test ": " why " (VFS_TEST_REQUIRE_ALL is set, so a skip is a failure)"))
      (do (println (str "SKIP " test ": " why))
          (t/do-report {:type :kaocha/pending :message (str "SKIP: " why)})))))

(defmacro requires
  "Runs `body` when every one of `reqs` (forms evaluating to nil or an unmet
  requirement) is nil, else skip!s the first unmet one."
  [reqs & body]
  `(if-let [unmet# (first (remove nil? ~reqs))]
     (skip! unmet#)
     (do ~@body)))
