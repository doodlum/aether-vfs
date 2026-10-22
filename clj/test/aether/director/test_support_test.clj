(ns aether.director.test-support-test
  "The skip helper: a wrong OS is always a skip, a missing prerequisite a
  failure under VFS_TEST_REQUIRE_ALL."
  (:require [aether.director.test-support :as ts]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]]))

(defn- reports
  "The clojure.test reports `f` makes, and whether its body ran."
  [f]
  (let [seen (atom []) ran (atom false)]
    (binding [t/report (fn [m] (swap! seen conj (select-keys m [:type :message])))]
      (with-out-str (f #(reset! ran true))))
    {:reports @seen :ran? @ran}))

(def ^:private wrong-os {:skip/kind :os :why "elsewhere only"})

(deftest every-requirement-met-runs-the-body
  (is (= {:reports [] :ran? true} (reports #(ts/requires [nil (ts/prerequisite true "x")] (%))))))

(deftest a-wrong-os-is-a-pending-skip-even-when-everything-is-required
  (doseq [all? [false true]]
    (binding [ts/*require-all* all?]
      (let [{:keys [reports ran?]} (reports #(ts/requires [wrong-os] (%)))]
        (is (not ran?))
        (is (= [{:type :kaocha/pending :message "SKIP: elsewhere only"}] reports) (str "require-all " all?))))))

(deftest a-missing-prerequisite-is-a-skip-or-under-require-all-a-failure
  (binding [ts/*require-all* false]
    (is (= [:kaocha/pending] (map :type (:reports (reports #(ts/requires [(ts/prerequisite false "build it")] (%))))))))
  (binding [ts/*require-all* true]
    (let [{:keys [reports ran?]} (reports #(ts/requires [(ts/prerequisite false "build it")] (%)))]
      (is (not ran?))
      (is (= [:fail] (map :type reports))))))

(deftest the-first-unmet-requirement-is-the-one-reported
  (binding [ts/*require-all* true]
    (is (= [:kaocha/pending] (map :type (:reports (reports #(ts/requires [wrong-os (ts/prerequisite false "p")] (%)))))))))

(deftest exactly-one-os-is-this-one
  (is (= 1 (count (remove some? [(ts/windows) (ts/linux)])))))

(deftest the-repo-root-holds-this-checkout
  (is (.isFile (io/file (ts/repo-root) "clj" "deps.edn")))
  (is (.isDirectory (io/file (ts/repo-root) "rust" "crates"))))
