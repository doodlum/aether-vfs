(ns aether.director.proton.prefix-test
  "aether.director.proton.prefix: the pure half, ported from vfs-proton's
  prefix/{mod,init,links}.rs tests and vfs-embed's session/proton/mod.rs
  (wine_cwd, link_roots). Every path here is a Linux-shaped string."
  (:require [aether.director.proton.layout :as layout]
            [aether.director.proton.prefix :as prefix]
            [clojure.test :refer [deftest is testing]]))

(defn- refusal
  "The ex-data of what `f` throws, or nil."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e)))))

(deftest prefix-dir-follows-the-init
  (is (= "/home/x/sessions/s/prefix" (prefix/prefix-dir "/home/x" "s" :wineboot)))
  (is (= "/home/x/sessions/s/compat/pfx"
         (prefix/prefix-dir "/home/x/" "s" {:proton {:steam-client "/steam"}})))
  (doseq [bad ["../x" "" " " "a/b" "a\\b" ".." "/abs" "C:x" nil]]
    (is (= :invalid-tag (::layout/error (refusal #(prefix/prefix-dir "/home/x" bad :wineboot))))
        (pr-str bad))))

(deftest initialised-means-system32-exists
  (let [dirs #{"/p/drive_c/windows/system32"}]
    (is (prefix/initialised? "/p" dirs))
    (is (prefix/initialised? "/p/" dirs))
    (is (not (prefix/initialised? "/q" dirs)))))

(deftest a-wineboot-prefix-is-set-up-once
  (is (prefix/needs-setup? :wineboot {:initialised? false}))
  (is (not (prefix/needs-setup? :wineboot {:initialised? true :version "anything"}))))

(deftest a-proton-prefix-is-set-up-again-for-another-runtime-or-a-moved-home
  (let [init  {:proton {:steam-client "/steam"}}
        rt    "/home/x/runtimes/GE-Proton99-1"
        state {:initialised? true :version "GE-Proton99-1\n" :tag "GE-Proton99-1" :runtime rt}]
    (is (not (prefix/needs-setup? init state)) "up to date, no config_info: the version decides")
    (is (prefix/needs-setup? init (assoc state :initialised? false)))
    (is (prefix/needs-setup? init (assoc state :version nil)) "never set up by Proton")
    (is (prefix/needs-setup? init (assoc state :version "  ")) "an empty version is none")
    (is (prefix/needs-setup? init (assoc state :tag "GE-Proton99-2")) "set up by another runtime")
    (is (not (prefix/needs-setup? init (assoc state :config-info (str "GE-Proton99-1\n" rt "/files/share/fonts/\n"))))
        "config_info names the runtime where it is")
    (is (prefix/needs-setup? init (assoc state :config-info
                                         "GE-Proton99-1\n/old/home/runtimes/GE-Proton99-1/files/share/fonts/\n"))
        "the home moved")))

(deftest proton-setup-drops-the-hosts-proton-knobs-and-dll-overrides
  (is (= ["WINEDLLOVERRIDES" "PROTON_USE_WINED3D" "PROTON_LOG"]
         (prefix/inherited-proton-env
          ["PATH" "WINEDLLOVERRIDES" "PROTON_USE_WINED3D" "PROTON_LOG" "PROTONFIXES_DISABLE" "proton_x"]))))

(deftest wineboot-output-naming-the-32-bit-loader-is-missing-32-bit
  (is (prefix/missing-32-bit? "wine: could not load ld-linux.so.2: No such file"))
  (is (not (prefix/missing-32-bit? "wineboot: something else"))))

(deftest the-log-tail-is-the-last-twenty-lines
  (let [text (apply str (for [i (range 30)] (str "line " i "\n")))
        tail (prefix/log-tail "/c/aether-proton-init.log" text)]
    (is (.startsWith tail "; log /c/aether-proton-init.log:\n"))
    (is (.contains tail "line 10\n") )
    (is (not (.contains tail "line 9\n")))
    (is (.endsWith tail "line 29"))))

(deftest parse-location-names-components-under-drive-c
  (is (= ["Games" "Fixture"] (prefix/parse-location "C:\\Games\\Fixture")))
  (is (= ["Games" "Fixture"] (prefix/parse-location "c:/Games/./Fixture/")))
  (doseq [bad ["D:\\Games" "C:\\a\\..\\b" "C:\\" "C:" "Games" "/srv/x" "\\\\srv\\share\\x" ""]]
    (let [r (refusal #(prefix/parse-location bad))]
      (is (= :bad-location (::prefix/error r)) (pr-str bad))
      (is (= bad (:location r)))
      (is (.startsWith ^String (:message r) (str "bad root location " bad ":")) (:message r)))))

(deftest windows-path-maps-only-paths-under-drive-c
  (is (= "C:\\Games\\g.exe" (prefix/windows-path "/p" "/p/drive_c/Games/g.exe")))
  (is (= "C:\\Games\\g.exe" (prefix/windows-path "/p/" "/p/drive_c//Games/./g.exe")))
  (is (= "C:" (prefix/windows-path "/p" "/p/drive_c")))
  (is (nil? (prefix/windows-path "/p" "/etc/passwd")) "outside the prefix there is no C: form")
  (is (nil? (prefix/windows-path "/p" "/p/drive_c2/x")) "a sibling spelled alike is not under drive_c")
  (is (nil? (prefix/windows-path "/p" "/p/drive_c/a/../b")) "never normalized into a C: form")
  (is (= "C:\\Games\\g.exe" (prefix/windows-path "/p//q" "/p/q/drive_c/Games/g.exe"))
      "a doubled separator in the prefix is dropped too")
  (is (= "C:\\Games" (prefix/windows-path "/p/q" "/p//q/drive_c/Games"))))

(deftest a-root-without-a-backing-is-refused
  (doseq [r [{:id 3 :location "C:\\Saves"} {:id 3 :location "C:\\Saves" :backing ""}]]
    (let [e (refusal #(prefix/link-plan "/p" [r] {} #{}))]
      (is (= [:no-backing 3] [(::prefix/error e) (:root e)]) (pr-str r))
      (is (= "root 3 has no backing directory" (:message e))))))

(deftest join-wine-appends-plain-components-and-refuses-the-rest
  (is (= "C:\\vfs-session\\state\\ring.bin" (prefix/join-wine "C:\\vfs-session\\state" "ring.bin")))
  (is (= "C:\\s\\a\\b" (prefix/join-wine "C:\\s" "a/./b/")))
  (is (= "C:\\s" (prefix/join-wine "C:\\s" "")))
  (doseq [bad ["../x" "a/../b" "/abs" "./a" "C:/x" "\\x"]]
    (is (= :bad-join (::prefix/error (refusal #(prefix/join-wine "C:\\s" bad)))) (pr-str bad))))

(deftest wine-cwd-defaults-to-the-image-directory
  (is (= "C:\\G\\bin" (prefix/wine-cwd nil "C:\\G" "C:\\G\\bin\\x.exe")))
  (is (= "C:\\" (prefix/wine-cwd nil "C:\\G" "C:\\x.exe")))
  (is (= "C:/tools" (prefix/wine-cwd nil "C:\\G" "C:/tools/probe.exe")))
  (is (= "C:\\G" (prefix/wine-cwd nil "C:\\G" "x.exe")) "no directory: root 0"))

(deftest wine-cwd-takes-absolute-paths-as-given-and-relative-ones-under-root-zero
  (is (= "D:\\x" (prefix/wine-cwd "D:\\x" "C:\\G" "C:\\G\\a.exe")))
  (is (= "C:\\G\\Data\\SKSE" (prefix/wine-cwd "Data/SKSE/" "C:\\G" "C:\\G\\a.exe")))
  (is (= "C:\\G\\Data" (prefix/wine-cwd "\\Data" "C:\\G\\" "C:\\G\\a.exe"))))

(deftest wine-cwd-refuses-empty-and-dot-dot
  (doseq [bad ["" "  " "..\\x" "C:\\G\\..\\Windows"]]
    (let [r (refusal #(prefix/wine-cwd bad "C:\\G" "C:\\G\\a.exe"))]
      (is (= :bad-cwd (::prefix/error r)) (pr-str bad))
      (is (.contains ^String (:message r) "cwd")))))

;; --- link-plan ------------------------------------------------------------

(def ^:private drive-c "/p/drive_c")

(deftest link-plan-creates-parents-and-links
  (let [[step] (prefix/link-plan "/p" [{:id 1 :location "C:\\Games\\Fixture" :backing "/s/roots/1"}] {} #{})]
    (is (= {:id 1 :location "C:\\Games\\Fixture" :backing "/s/roots/1"
            :mkdirs ["/p/drive_c/Games"] :link "/p/drive_c/Games/Fixture" :replace nil}
           step)))
  (testing "relinking our own symlink replaces it"
    (let [[step] (prefix/link-plan "/p" [{:id 1 :location "c:/Games/Fixture/" :backing "/s/roots/1b"}]
                                   {drive-c {"Games" :dir} "/p/drive_c/Games" {"Fixture" :symlink}}
                                   #{"/p/drive_c/Games/Fixture"})]
      (is (= [] (:mkdirs step)))
      (is (= "/p/drive_c/Games/Fixture" (:link step)))
      (is (= "/p/drive_c/Games/Fixture" (:replace step))))))

(deftest link-plan-reuses-existing-parents-case-insensitively
  (let [listing {drive-c {"users" :dir} "/p/drive_c/users" {"steamuser" :dir}}
        [step]  (prefix/link-plan "/p" [{:id 1 :location "C:\\Users\\SteamUser\\Saves" :backing "/b"}] listing #{})]
    (is (= "/p/drive_c/users/steamuser/Saves" (:link step)) "landed in the prefix's own users/steamuser")
    (is (= [] (:mkdirs step)) "no Users tree beside it")
    (is (nil? (:replace step))))
  (testing "relinking under another spelling finds our own link"
    (let [listing {drive-c {"users" :dir} "/p/drive_c/users" {"steamuser" :dir}
                   "/p/drive_c/users/steamuser" {"Saves" :symlink}}
          [step]  (prefix/link-plan "/p" [{:id 1 :location "c:\\USERS\\steamuser\\saves" :backing "/b2"}]
                                    listing #{"/p/drive_c/users/steamuser/Saves"})]
      (is (= "/p/drive_c/users/steamuser/Saves" (:replace step)) "the old link goes")
      (is (= "/p/drive_c/users/steamuser/saves" (:link step)) "the new one has the declared spelling")))
  (testing "an exact spelling wins over a case-insensitive one"
    (let [[step] (prefix/link-plan "/p" [{:id 1 :location "C:\\Games\\X" :backing "/b"}]
                                   {drive-c {"GAMES" :dir "Games" :dir}} #{})]
      (is (= "/p/drive_c/Games/X" (:link step))))))

(deftest link-plan-refuses-real-files-and-directories
  (doseq [[listing what] [[{drive-c {"games" :dir} "/p/drive_c/games" {"mine" :dir}} "a directory spelled differently"]
                          [{drive-c {"Games" :dir} "/p/drive_c/Games" {"Mine" :dir}} "a directory"]
                          [{drive-c {"Games" :dir} "/p/drive_c/Games" {"Mine" :file}} "a file"]]]
    (let [r (refusal #(prefix/link-plan "/p" [{:id 1 :location "C:\\Games\\Mine" :backing "/b"}] listing #{}))]
      (is (= :bad-location (::prefix/error r)) what)
      (is (.contains ^String (:message r) "real file or directory") (:message r))
      (is (.startsWith ^String (:message r) "root 1: bad root location C:\\Games\\Mine") (:message r)))))

(deftest link-plan-refuses-a-symlink-it-did-not-create
  (let [r (refusal #(prefix/link-plan "/p" [{:id 1 :location "C:\\Games\\Skyrim" :backing "/b"}]
                                      {drive-c {"Games" :dir} "/p/drive_c/Games" {"Skyrim" :symlink}} #{}))]
    (is (= :bad-location (::prefix/error r)))
    (is (.contains ^String (:message r) "did not create") (:message r))
    (is (.contains ^String (:message r) "/p/.aether-vfs-links") "names the manifest")))

(deftest link-plan-refuses-bad-locations-by-root
  (doseq [bad ["D:\\Games" "C:\\a\\..\\b" "C:\\" "Games" "\\\\srv\\share\\x"]]
    (let [r (refusal #(prefix/link-plan "/p" [{:id 3 :location bad :backing "/b"}] {} #{}))]
      (is (= :bad-location (::prefix/error r)) bad)
      (is (= 3 (:root r))))))

(deftest nested-roots-link-inside-the-outer-backing-dir-in-either-order
  (doseq [roots [[{:id 0 :location "C:\\G" :backing "/s/root"} {:id 1 :location "C:\\G\\Saves" :backing "/s/roots/1"}]
                 [{:id 1 :location "C:\\G\\Saves" :backing "/s/roots/1"} {:id 0 :location "C:\\G" :backing "/s/root"}]]]
    (let [plan (prefix/link-plan "/p" roots {} #{})]
      (is (= [0 1] (map :id plan)) "shallowest first")
      (is (= ["/p/drive_c/G" "/p/drive_c/G/Saves"] (map :link plan)))
      (is (= [] (:mkdirs (second plan))) "the inner root's parent is the outer link")))
  (testing "a stable sort: equal depths keep their order"
    (is (= [2 1] (map :id (prefix/link-plan "/p" [{:id 2 :location "C:\\B" :backing "/2"}
                                                      {:id 1 :location "C:\\A" :backing "/1"}] {} #{})))))
  (testing "what is already in the outer backing dir is matched through the link"
    (let [plan (prefix/link-plan "/p" [{:id 2 :location "C:\\G" :backing "/s/roots/2"}
                                          {:id 1 :location "C:\\G\\Data\\Saves" :backing "/s/roots/1"}]
                                 {"/s/roots/2" {"data" :dir}} #{})]
      (is (= "/p/drive_c/G/data/Saves" (:link (second plan))))
      (is (= [] (:mkdirs (second plan))))))
  (testing "a real directory in the outer backing dir is refused through the link"
    (is (= :bad-location
           (::prefix/error (refusal #(prefix/link-plan "/p" [{:id 2 :location "C:\\G" :backing "/s/roots/2"}
                                                               {:id 1 :location "C:\\G\\Saves" :backing "/s/roots/1"}]
                                                      {"/s/roots/2" {"saves" :dir}} #{})))))))

(deftest link-plan-creates-missing-parents-once
  (let [plan (prefix/link-plan "/p" [{:id 1 :location "C:\\Games\\A" :backing "/1"}
                                        {:id 2 :location "C:\\games\\B" :backing "/2"}] {} #{})]
    (is (= [["/p/drive_c/Games"] []] (map :mkdirs plan)) "the second root reuses the parent the first made")
    (is (= ["/p/drive_c/Games/A" "/p/drive_c/Games/B"] (map :link plan)))))
