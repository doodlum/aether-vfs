(ns aether.director.proton.release-test
  "vfs-proton/src/release.rs's tests, over its fixture: GitHub's releases API
  shape (vfs-proton/tests/fixtures/releases.json, copied)."
  (:require [aether.director.proton.release :as release]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- fixture [] (slurp (io/resource "aether/director/proton/releases.json")))

(deftest parses-tag-urls-and-size
  (let [r (first (filter #(= "GE-Proton11-6" (:tag %)) (release/parse-releases (fixture))))]
    (is (str/ends-with? (:tarball-url r) "GE-Proton11-6-x86_64.tar.gz"))
    (is (str/ends-with? (:digest-url r) "GE-Proton11-6-x86_64.sha512sum"))
    (is (= 533700853 (:size r)))))

(deftest skips-releases-with-no-x86_64-asset
  ;; taking "the first asset" would install an ARM runtime on an x86_64 host
  (let [rs (release/parse-releases (fixture))]
    (is (= ["GE-Proton11-6" "GE-Proton11-5" "GE-Proton9-1"] (mapv :tag rs)) "GE-Proton11-4 is aarch64-only")
    (is (every? #(str/includes? (:tarball-url %) "x86_64") rs))))

(deftest pick-takes-the-newest-and-honours-a-major-series
  (let [rs (release/parse-releases (fixture))]
    (is (= "GE-Proton11-6" (:tag (release/pick rs nil))))
    (is (= "GE-Proton11-6" (:tag (release/pick rs 11))))
    (is (= "GE-Proton9-1" (:tag (release/pick rs 9))))
    (is (nil? (release/pick rs 42)))
    (is (= "GE-Proton11-6" (:tag (release/pick (reverse rs) nil))) "order doesn't matter")))

(deftest malformed-json-is-an-error-not-a-crash
  (doseq [bad ["not json" "{}" "\"x\""]]
    (is (= :json (try (release/parse-releases bad) nil
                      (catch clojure.lang.ExceptionInfo e (::release/error (ex-data e)))))
        bad)))

(deftest odd-entries-are-skipped
  (is (= [{:tag "GE-Proton1-1" :tarball-url "t" :digest-url "d" :size 0}]
         (release/parse-releases
          (str "[{\"tag_name\": 5}, {\"assets\": []}, {\"tag_name\": \"x\"}, 7,"
               " {\"tag_name\": \"GE-Proton1-1\", \"assets\": ["
               "  {\"name\": \"a-x86_64.tar.gz\", \"browser_download_url\": \"t\", \"size\": -1},"
               "  {\"name\": \"no-url-x86_64.sha512sum\"},"
               "  {\"name\": \"a-x86_64.sha512sum\", \"browser_download_url\": \"d\"}]}]")))
      "a negative size reads as 0, as as_u64 does"))

(deftest the-releases-request-is-data
  (is (= {:url     "https://api.github.com/repos/GloriousEggroll/proton-ge-custom/releases?per_page=30"
          :headers {"User-Agent" "aether-vfs (vfs-proton)"}}
         (release/releases-request nil)
         (release/releases-request "")))
  (is (= "Bearer t0k" (get-in (release/releases-request "t0k") [:headers "Authorization"]))
      "GITHUB_TOKEN goes only to the API, as a bearer token"))
