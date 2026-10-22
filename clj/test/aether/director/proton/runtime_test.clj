(ns aether.director.proton.runtime-test
  "vfs-proton/src/runtime.rs's tests, over fixture runtimes/ dirs in a temp dir."
  (:require [aether.director.proton.layout :as layout]
            [aether.director.proton.runtime :as rt]
            [aether.director.test-support :as ts]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir ^String []
  (str (Files/createTempDirectory "aether-proton-rt" (make-array FileAttribute 0))))

(defn- write! [dir rel text]
  (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))

(defn- error-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest verify-ge-accepts-a-real-ge-version-file
  ;; exactly the bytes GE-Proton11-6 ships: a build id, a space, the tag
  (let [d (temp-dir)]
    (write! d "version" "1787951532 GE-Proton11-6\n")
    (is (= "GE-Proton11-6" (rt/verify-ge d)))))

(deftest verify-ge-rejects-stock-proton
  ;; PROTONPATH defaults to UMU-Proton, which is stock Valve Proton
  (let [d (temp-dir)]
    (write! d "version" "1234567890 proton-9.0-4\n")
    (let [e (error-of #(rt/verify-ge d))]
      (is (= :not-ge (::rt/error e)))
      (is (= "1234567890 proton-9.0-4" (:version e))))))

(deftest verify-ge-rejects-a-missing-version-file
  (is (= :missing (::rt/error (error-of #(rt/verify-ge (temp-dir)))))))

(deftest verify-ge-tolerates-no-trailing-newline-and-extra-fields
  (let [d (temp-dir)]
    (write! d "version" "1787951532 GE-Proton11-6 extra")
    (is (= "GE-Proton11-6" (rt/verify-ge d)))))

(defn- fake-rt [dmo? av?]
  (let [d (temp-dir)]
    (write! d "version" "1 GE-Proton11-7\n")
    (.mkdirs (io/file d "files/lib/wine/x86_64-unix"))
    (.mkdirs (io/file d "files/lib/x86_64-linux-gnu"))
    (when dmo? (write! d "files/lib/wine/x86_64-unix/winedmo.so" ""))
    (when av? (write! d "files/lib/x86_64-linux-gnu/libavformat.so.62.12.100" ""))
    d))

(deftest verify-ge-requires-ffmpeg-beside-winedmo
  (let [e (try (rt/verify-ge (fake-rt true false)) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (= :missing-lib (::rt/error (ex-data e))))
    (is (re-find #"libavformat\.so" (ex-message e))))
  (is (= "GE-Proton11-7" (rt/verify-ge (fake-rt true true))))
  (is (= "GE-Proton11-7" (rt/verify-ge (fake-rt false false))))
  (testing "check-ffmpeg on its own"
    (is (nil? (rt/check-ffmpeg (fake-rt false false))))
    (is (= :missing-lib (::rt/error (error-of #(rt/check-ffmpeg (fake-rt true false))))))))

(deftest version-tag-is-the-ge-token
  (is (= "GE-Proton11-6" (rt/version-tag "1 GE-Proton11-6\n")))
  (is (nil? (rt/version-tag "1 proton-9.0-4")))
  (is (nil? (rt/version-tag ""))))

(deftest runtime-lib-env-prepends-and-keeps-inherited
  (is (= {"LD_LIBRARY_PATH"      "/rt/files/lib/x86_64-linux-gnu:/rt/files/lib/i386-linux-gnu:/host/lib"
          "ORIG_LD_LIBRARY_PATH" "/host/lib"
          "WINEDLLPATH"          "/rt/files/lib/vkd3d:/rt/files/lib/wine:/host/dll"}
         (rt/runtime-lib-env "/rt" "/host/lib" nil "/host/dll"))))

(deftest runtime-lib-env-without-host-values-and-with-orig-present
  (is (= {"LD_LIBRARY_PATH"      "/rt/files/lib/x86_64-linux-gnu:/rt/files/lib/i386-linux-gnu"
          "ORIG_LD_LIBRARY_PATH" ""
          "WINEDLLPATH"          "/rt/files/lib/vkd3d:/rt/files/lib/wine"}
         (rt/runtime-lib-env "/rt" nil nil nil)))
  (is (not (contains? (rt/runtime-lib-env "/rt" "/x" "/o" nil) "ORIG_LD_LIBRARY_PATH")))
  (is (= "/rt/files/lib/x86_64-linux-gnu:/rt/files/lib/i386-linux-gnu"
         (get (rt/runtime-lib-env "/rt" "" nil "") "LD_LIBRARY_PATH"))
      "an empty inherited value adds no empty entry"))

(deftest tags-order-numerically-not-lexically
  ;; "GE-Proton11-6" < "GE-Proton9-1" as strings
  (is (pos? (rt/cmp-tags "GE-Proton11-6" "GE-Proton9-1")))
  (is (pos? (rt/cmp-tags "GE-Proton11-10" "GE-Proton11-9")))
  (is (zero? (rt/cmp-tags "GE-Proton11-6" "GE-Proton11-6")))
  (is (neg? (rt/cmp-tags "junk" "GE-Proton1-0")) "junk sorts below every real tag")
  (is (zero? (rt/cmp-tags "junk" "other")))
  (is (= [1 2] (rt/parse-tag "GE-Proton1-2")))
  (is (nil? (rt/parse-tag "GE-Proton1-x"))))

(deftest installed-lists-only-verified-ge-runtimes-newest-first
  (let [home (temp-dir)]
    (doseq [[tag body] [["GE-Proton11-6" "1 GE-Proton11-6\n"]
                        ["GE-Proton9-1" "1 GE-Proton9-1\n"]
                        ["junk-dir" "1 proton-9.0-4\n"]   ; not GE: excluded
                        ["half-extracted" nil]]]          ; no version file: excluded
      (.mkdirs (io/file (layout/runtime-dir home tag)))
      (when body (write! (layout/runtime-dir home tag) "version" body)))
    (write! (layout/runtimes home) "stray-file" "1 GE-Proton12-1\n")
    (is (= ["GE-Proton11-6" "GE-Proton9-1"] (rt/installed home)))
    (is (= [["GE-Proton11-6" (layout/runtime-dir home "GE-Proton11-6")]
            ["GE-Proton9-1" (layout/runtime-dir home "GE-Proton9-1")]]
           (rt/installed-dirs home)))))

(deftest newest-is-the-first-of-installed-dirs-or-nil
  (let [home (str (temp-dir) "/home")]
    (is (nil? (rt/newest home)) "no runtimes dir")
    (is (= [] (rt/installed-dirs home)))
    (doseq [[dir tag] [["a" "GE-Proton9-1"] ["b" "GE-Proton11-6"]]]
      (write! (layout/runtime-dir home dir) "version" (str "1 " tag "\n")))
    (is (= (layout/runtime-dir home "b") (rt/newest home))
        "the directory found, not one re-joined from the tag")))

(deftest an-unreadable-runtimes-dir-is-an-error-not-empty
  ;; read_dir's error is returned, as in runtime.rs:190-196; a missing dir is empty
  (let [home (temp-dir)]
    (spit (io/file (layout/runtimes home)) "a file, not a directory")
    (is (thrown? java.io.IOException (rt/installed-dirs home)))))

(defn- symlink! [link target]
  (Files/createSymbolicLink (.toPath (io/file link)) (.toPath (io/file target)) (make-array FileAttribute 0)))

(deftest a-symlinked-runtime-directory-is-listed-under-its-link
  ;; runtime.rs's #[cfg(unix)] test; on Windows a symlink needs Developer Mode
  (let [base (temp-dir) home (str base "/home") real (str base "/elsewhere/GE-Proton11-7-x86_64")
        link (layout/runtime-dir home "GE-Proton11-7-x86_64")]
    (write! real "version" "1 GE-Proton11-7\n")
    (.mkdirs (io/file (layout/runtimes home)))
    (ts/requires [(ts/linux)]
      (symlink! link real)
      (symlink! (layout/runtime-dir home "dangling") (str base "/gone"))
      (is (= [["GE-Proton11-7" link]] (rt/installed-dirs home))
          "listed at the link (never canonicalized); the dangling link is skipped"))))
