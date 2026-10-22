(ns aether.director.proton.steam-test
  "aether.director.proton.steam, ported from vfs-proton's tests/steam_client.rs
  and vfs-embed's Session::steam_launch."
  (:require [aether.director.proton.steam :as steam]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- scratch ^java.io.File [tag]
  (.toFile (Files/createTempDirectory (str "aev-steam-" tag) (make-array FileAttribute 0))))

(defn- proc-with
  "A fake /proc under `dir` holding one process."
  [dir pid comm]
  (let [proc (io/file dir "proc")]
    (io/make-parents (io/file proc (str pid) "comm"))
    (spit (io/file proc (str pid) "comm") (str comm "\n"))
    (str proc)))

(deftest the-state-dir-is-home-dot-steam
  (is (= "/home/u/.steam" (steam/state-dir "/home/u")))
  (is (= "/home/u/.steam" (steam/state-dir "/home/u/")))
  (is (nil? (steam/state-dir nil)))
  (is (nil? (steam/state-dir ""))))

(deftest a-pid-file-naming-a-live-steam-process-is-a-running-client
  (let [d (scratch "live")]
    (spit (io/file d "steam.pid") "4242\n")
    (is (= 4242 (steam/running-client (str d) (proc-with d 4242 "steam"))))))

(deftest a-stale-pid-file-is-not-a-running-client
  (let [d    (scratch "stale")
        proc (proc-with d 1 "systemd")]
    (is (nil? (steam/running-client (str d) proc)) "no pid file")
    (spit (io/file d "steam.pid") "4242")
    (is (nil? (steam/running-client (str d) proc)) "the process is gone")
    (spit (io/file d "steam.pid") "1")
    (is (nil? (steam/running-client (str d) proc)) "the pid was reused by something else")
    (spit (io/file d "steam.pid") "not a pid")
    (is (nil? (steam/running-client (str d) proc)))))

(deftest the-client-decision-is-pure
  (is (= 7 (steam/client-pid " 7\n" {7 "steam\n"})))
  (is (nil? (steam/client-pid nil {7 "steam"})))
  (is (nil? (steam/client-pid "7" {7 "steamwebhelper"})))
  (is (nil? (steam/client-pid "-7" {-7 "steam"})) "not a pid")
  (is (nil? (steam/client-pid "7" {}))))

(deftest the-note-is-one-line-naming-the-pid-file
  (let [n (steam/not-running-note "/home/u/.steam")]
    (is (str/includes? n "/home/u/.steam/steam.pid"))
    (is (str/includes? n "without Steam"))
    (is (not (str/includes? n "\n")))))

(deftest the-injectors-report-is-read-back
  (is (= "/s/ready.flag.steam-helper" (steam/helper-report-path "/s/ready.flag")))
  (doseq [[raw want] [["started:236:317" {:helper/status :helper/started :pid 236 :ms 317}]
                      ["cleared\n" {:helper/status :helper/cleared}]
                      ["failed:it (process 9) exited before publishing itself"
                       {:helper/status :helper/not-running :why "it (process 9) exited before publishing itself"}]
                      ["disabled:SteamGameId is not set" {:helper/status :helper/not-running :why "SteamGameId is not set"}]
                      ["started:x:1" {:helper/status :helper/not-running :why "started:x:1"}]
                      ["something new" {:helper/status :helper/not-running :why "something new"}]]]
    (is (= want (steam/helper-status :helper raw false false)) raw)))

(deftest a-missing-report-is-pending-until-the-injector-is-past-it
  (is (= {:helper/status :helper/pending} (steam/helper-status :helper nil false false)))
  (is (= {:helper/status :helper/unreported} (steam/helper-status :helper nil true false)))
  (is (= {:helper/status :helper/unreported} (steam/helper-status :helper nil false true))
      "the target is running, so the injector wrote whatever it was going to")
  (is (= {:helper/status :helper/not-requested} (steam/helper-status :untouched "cleared" true true))))

(deftest the-report-is-read-from-beside-the-ready-file
  (let [d     (scratch "report")
        ready (str (io/file d "ready.flag"))]
    (is (= :helper/pending (:helper/status (steam/read-helper-status :helper ready false))))
    (spit (steam/helper-report-path ready) "started:236:317")
    (is (= {:helper/status :helper/started :pid 236 :ms 317} (steam/read-helper-status :helper ready false)))))

(deftest only-a-helper-that-is-not-running-or-an-old-injector-is-noted
  (is (nil? (steam/helper-note :helper {:helper/status :helper/started :pid 1 :ms 1})))
  (is (nil? (steam/helper-note :off {:helper/status :helper/cleared})))
  (is (nil? (steam/helper-note :helper {:helper/status :helper/pending})))
  (let [n (steam/helper-note :helper {:helper/status :helper/not-running :why "why"})]
    (is (and (str/includes? n "(why)") (str/includes? n "without Steam")) n))
  (let [n (steam/helper-note :helper {:helper/status :helper/unreported})]
    (is (and (str/includes? n "rebuild") (str/includes? n "without Steam")) n))
  (let [n (steam/helper-note :off {:helper/status :helper/unreported})]
    (is (and (str/includes? n "rebuild") (not (str/includes? n "without Steam"))) n)))

;; --- steam-side ---------------------------------------------------------------

(def ^:private proton {:proton {:steam-client "/home/u/.local/share/Steam" :app-id 489830}})

(deftest a-prefix-proton-did-not-set-up-is-untouched
  (is (= {:mode :untouched :env {} :notes []}
         (steam/steam-side {:prefix-init :wineboot :state-dir "/home/u/.steam" :client-pid 1}))))

(deftest the-helper-starts-when-there-is-an-app-id-and-a-running-client
  (let [side (steam/steam-side {:prefix-init proton :state-dir "/home/u/.steam" :client-pid 4242})]
    (is (= :helper (:mode side)))
    (is (= 489830 (:app-id side)))
    (is (= {"SteamAppId" "489830" "SteamGameId" "489830"
            "STEAM_COMPAT_CLIENT_INSTALL_PATH" "/home/u/.local/share/Steam"
            "VFS_INJECT_STEAM_HELPER" "C:\\windows\\system32\\steam.exe C:\\windows\\system32\\rundll32.exe"}
           (:env side)))
    (is (= "steam.exe=b" (:dll-overrides side)))
    (is (= [] (:notes side))))
  (testing "the app id may come from the launch's SteamAppId"
    (let [side (steam/steam-side {:prefix-init {:proton {:steam-client "/s"}} :env {"SteamAppId" " 72850 "}
                                  :state-dir "/h/.steam" :client-pid 1})]
      (is (= [:helper 72850] [(:mode side) (:app-id side)])))))

(deftest otherwise-a-proton-prefix-only-clears-the-stale-pid
  (doseq [[why opts note] [["helper turned off" {:helper? false} nil]
                           ["no app id" {:prefix-init {:proton {:steam-client "/s"}}} nil]
                           ["app id 0" {:prefix-init {:proton {:steam-client "/s" :app-id 0}}} nil]
                           ["an unparsable SteamAppId" {:prefix-init {:proton {:steam-client "/s"}} :env {"SteamAppId" "x"}} nil]
                           ["no HOME" {:state-dir nil} "HOME is not set"]
                           ["no client" {:client-pid nil} "/home/u/.steam/steam.pid"]]]
    (let [side (steam/steam-side (merge {:prefix-init proton :state-dir "/home/u/.steam" :client-pid 4242} opts))]
      (is (= :off (:mode side)) why)
      (is (= {"VFS_INJECT_STEAM_HELPER" "off"} (:env side)) why)
      (if note
        (is (and (= 1 (count (:notes side))) (str/includes? (first (:notes side)) note)) why)
        (is (= [] (:notes side)) why)))))
