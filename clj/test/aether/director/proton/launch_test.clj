(ns aether.director.proton.launch-test
  "aether.director.proton.launch, the pure parts, ported from vfs-proton's
  launch/{mod,injector}.rs tests."
  (:require [aether.director.proton.launch :as launch]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private sample
  {:runtime      "/ge/GE-Proton11-6-x86_64"
   :injector     "/ge/bin/vfs-injector.exe"
   :shim-dll     "/ge/bin/vfs_shim_dll.dll"
   :target       "C:\\probe\\target.exe"
   :config-file  "/ge/state/shim.cfg"
   :ready-file   "/ge/state/ready.txt"
   :ring-bytes   33751040
   :arena-offset 65536
   :arena-len    33554432
   :args         ["-arg1" "arg2"]})

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e)))))

(deftest wine-comes-from-inside-the-runtime-not-the-host-path
  (is (= "/ge/GE-Proton11-6-x86_64/files/bin/wine" (launch/wine-binary "/ge/GE-Proton11-6-x86_64/")))
  (is (= "/ge/GE-Proton11-6-x86_64/files/bin/wineserver" (launch/wineserver-binary "/ge/GE-Proton11-6-x86_64"))))

(deftest argv-is-the-injector-contract-in-order
  ;; vfs-injector <target_exe> <shim_dll> <config> <ready> [-- args]: positional,
  ;; so a swap is silent and this is the only thing that catches it.
  (let [[prog argv] (launch/command-line sample)]
    (is (= "/ge/GE-Proton11-6-x86_64/files/bin/wine" prog))
    (is (= ["/ge/bin/vfs-injector.exe" "C:\\probe\\target.exe" "/ge/bin/vfs_shim_dll.dll"
            "/ge/state/shim.cfg" "/ge/state/ready.txt"]
           (subvec argv 0 5)))))

(deftest target-args-follow-a-separator-and-keep-their-order
  (is (= ["--" "-arg1" "arg2"] (subvec (second (launch/command-line sample)) 5))))

(deftest no-separator-is-emitted-when-there-are-no-target-args
  (is (= 5 (count (second (launch/command-line (assoc sample :args []))))))
  (is (= 5 (count (second (launch/command-line (dissoc sample :args)))))))

(deftest a-view-too-small-for-the-arena-is-refused-before-spawning
  (let [r (refusal #(launch/check-geometry (assoc sample :ring-bytes (* 2 1024 1024) :arena-offset 132136
                                                  :arena-len 33554432)
                                           nil))]
    (is (= :launch/geometry (:launch/error r)))
    (is (str/includes? (:message r) "exceeds ring_bytes") (:message r))))

(deftest a-consistent-geometry-passes
  (is (nil? (launch/check-geometry (assoc sample :ring-bytes 33751040 :arena-offset 132136 :arena-len 33554432) nil)))
  (is (nil? (launch/check-geometry (assoc sample :ring-host-path "/s/ring.bin") 33751040)) "a file exactly long enough"))

(deftest a-ring-file-shorter-than-ring-bytes-is-refused
  ;; mapping past a file's end faults on touch rather than failing at map time
  (let [r (refusal #(launch/check-geometry (assoc sample :ring-host-path "/s/ring.bin" :ring-bytes 65536
                                                  :arena-offset 0 :arena-len 0)
                                           128))]
    (is (= :launch/geometry (:launch/error r)))
    (is (str/includes? (:message r) "but ring_bytes is") (:message r))
    (is (str/includes? (:message r) "/s/ring.bin"))))

(deftest the-launchs-reports-sit-beside-the-ready-file
  (is (= "/s/ready.flag.injector-error" (launch/injector-error-path "/s/ready.flag")))
  (is (= "/s/ready.flag.child-refused" (launch/child-refused-path "/s/ready.flag")))
  (is (= ["/s/ready.flag.injector-error" "/s/ready.flag.steam-helper"] (launch/stale-reports "/s/ready.flag"))
      "a report an earlier launch left would be read as this one's"))

(deftest injector-reports-are-described
  (let [dll (launch/describe-injector-error "target-exited:0xc0000135\n")]
    (is (and (str/includes? dll "0xc0000135") (str/includes? dll "STATUS_DLL_NOT_FOUND")) dll))
  (is (str/includes? (launch/describe-injector-error "target-exited:0xC000007B") "STATUS_INVALID_IMAGE_FORMAT"))
  (is (str/includes? (launch/describe-injector-error "target-exited:0xc0000142") "STATUS_DLL_INIT_FAILED"))
  (let [other (launch/describe-injector-error "target-exited:0x1")]
    (is (and (str/includes? other "0x1") (str/includes? other "before the shim reported ready")
             (not (str/includes? other "STATUS_"))) other))
  (is (str/includes? (launch/describe-injector-error "ready-timeout:180") "180 s"))
  (let [f (launch/describe-injector-error "fuse-failed:no ring")]
    (is (and (str/includes? f "attach to the director") (str/includes? f "no ring")) f))
  (let [b (launch/describe-injector-error "bootstrap-failed:shim config version 3")]
    (is (and (str/includes? b "bootstrap") (str/includes? b "version 3")) b))
  (is (= "injection failed: CreateProcess" (launch/describe-injector-error "inject:CreateProcess")))
  (is (= "injection failed: something new" (launch/describe-injector-error "something new"))))

(deftest child-refusals-are-lines-and-absent-means-none
  (is (= [] (launch/child-refusals nil)))
  (is (= ["C:\\a b\\x.exe ready-timeout" "y.exe child-32bit"]
         (launch/child-refusals "C:\\a b\\x.exe ready-timeout\n\ny.exe child-32bit\n")))
  (is (str/includes? (launch/describe-child-refusal "y.exe child-32bit") "refused and killed")))

(deftest finish-maps-exits-and-reads-the-injector-report
  (is (= {:exit 0} (launch/finish sample 0 nil)))
  (is (= {:exit 7} (launch/finish sample 7 "target-exited:0x1")) "a target's own code is its exit, report or not")
  (testing "2 and 3 are the injector's own: the target never ran"
    (doseq [code [2 3]]
      (let [r (launch/finish sample code nil)]
        (is (= [:launch/injector-exit code] [(:launch/error r) (:code r)]))
        (is (str/includes? (:error r) (str "vfs-injector exited " code " without running the target")) (:error r))))
    (is (= :launch/injector-exit (:launch/error (launch/finish sample 3 " \n"))) "an empty report says nothing")
    (let [r (launch/finish sample 3 "target-exited:0xc0000135")]
      (is (= [:launch/injector 3] [(:launch/error r) (:code r)]))
      (is (str/starts-with? (:error r) "vfs-injector did not run the target: "))
      (is (str/includes? (:error r) "STATUS_DLL_NOT_FOUND") (:error r))))
  (testing "no code at all: signalled"
    (let [r (launch/finish sample nil nil)]
      (is (= :launch/signalled (:launch/error r)))
      (is (str/includes? (:error r) "/ge/GE-Proton11-6-x86_64/files/bin/wine")))))
