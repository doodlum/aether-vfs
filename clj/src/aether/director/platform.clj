(ns aether.director.platform
  "The one platform value the director chooses its seams by:
  :platform/windows, :platform/linux or :platform/macos. Chosen once, from
  os.name; a caller's :platform option overrides it (tests, and the file
  transport on Windows).

  Linux and macOS both run the target under Wine (GE-Proton on Linux,
  CrossOver on macOS), so they share the Wine half: wine? says which."
  (:require [clojure.string :as str]))

(defn of-os-name
  "The platform an os.name value names."
  [^String os-name]
  (let [n (str/lower-case os-name)]
    (cond (str/starts-with? n "windows") :platform/windows
          (or (str/starts-with? n "mac") (str/starts-with? n "darwin")) :platform/macos
          :else :platform/linux)))

(defn current
  "This process's platform, or `opts`' :platform when it names one."
  ([] (of-os-name (System/getProperty "os.name")))
  ([opts] (or (:platform opts) (current))))

(defn wine?
  "Whether `platform` runs the target under Wine, with a file ring named in
  C: form."
  [platform]
  (contains? #{:platform/linux :platform/macos} platform))
