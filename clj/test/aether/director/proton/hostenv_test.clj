(ns aether.director.proton.hostenv-test
  "vfs-proton/src/hostenv.rs's tests: an AppImage's $APPDIR additions taken out."
  (:require [aether.director.proton.hostenv :as hostenv]
            [clojure.test :refer [deftest is testing]]))

(def ^:private appdir "/run/appimage/.mount_HaskilXYZ")

(defn- c [v] (hostenv/clean appdir v))

(deftest a-value-wholly-inside-the-appimage-is-dropped
  (is (nil? (c "/run/appimage/.mount_HaskilXYZ/usr/")))
  ;; AppRun's PYTHONPATH with nothing of the host's after it
  (is (nil? (c "/run/appimage/.mount_HaskilXYZ/usr/share/pyshared/:"))))

(deftest the-hosts-entries-are-kept-in-order
  (is (= "/usr/local/bin:/usr/bin"
         (c "/run/appimage/.mount_HaskilXYZ/usr/bin/:/run/appimage/.mount_HaskilXYZ/bin/:/usr/local/bin:/usr/bin")))
  (is (= "/usr/share:/usr/local/share"
         (c "/run/appimage/.mount_HaskilXYZ/usr/share:/usr/share:/usr/local/share"))))

(deftest values-outside-the-appimage-are-untouched
  (doseq [v ["/usr/bin:/bin" "/usr/bin::/bin" "Adwaita:light" ""
             ;; a sibling whose name only starts the same is not inside it
             "/run/appimage/.mount_HaskilXYZ2/lib"]]
    (is (identical? v (c v)) (pr-str v)))
  (is (nil? (c "/run/appimage/.mount_HaskilXYZ")) "the mount point itself is inside")
  (is (nil? (c "/run/appimage//.mount_HaskilXYZ/./lib")) "compared by component, as Path::starts_with"))

(deftest the-appdir-must-be-absolute-and-not-the-root
  (is (= appdir (hostenv/appdir {"APPDIR" appdir})))
  (doseq [bad ["relative/dir" "/" "//" ""]]
    (is (nil? (hostenv/appdir {"APPDIR" bad})) (pr-str bad)))
  (is (nil? (hostenv/appdir {}))))

(def ^:private appimage-env
  {"APPDIR"                  appdir
   "PATH"                    (str appdir "/usr/bin:/usr/bin")
   "PYTHONHOME"              (str appdir "/usr/")
   "PYTHONDONTWRITEBYTECODE" "1"
   "GTK_THEME"               "Adwaita:dark"
   "HOME"                    "/home/me"})

(deftest inherited-is-what-a-host-program-inherits
  (testing "inside an AppImage"
    (is (= "/usr/bin" (hostenv/inherited appimage-env "PATH")))
    (is (nil? (hostenv/inherited appimage-env "PYTHONHOME")))
    (is (nil? (hostenv/inherited appimage-env "GTK_THEME")) "set by AppRun to a value that isn't a path")
    (is (= "/home/me" (hostenv/inherited appimage-env "HOME")))
    (is (nil? (hostenv/inherited appimage-env "UNSET"))))
  (testing "outside one, the value as it is"
    (let [env (dissoc appimage-env "APPDIR")]
      (is (= (env "PYTHONHOME") (hostenv/inherited env "PYTHONHOME")))
      (is (= "Adwaita:dark" (hostenv/inherited env "GTK_THEME"))))))

(deftest clean-env-is-the-environment-command-gives-a-child
  (is (= {"PATH" "/usr/bin" "HOME" "/home/me"}
         (hostenv/clean-env appimage-env))
      "APPDIR names the mount itself, so it goes too, as in hostenv.rs's command")
  (let [env (dissoc appimage-env "APPDIR")]
    (is (identical? env (hostenv/clean-env env)))))
