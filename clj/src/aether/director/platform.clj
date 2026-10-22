(ns aether.director.platform
  "The one platform value the director chooses its seams by:
  :platform/windows or :platform/linux. Chosen once, from os.name; a caller's
  :platform option overrides it (tests, and the file transport on Windows)."
  (:require [clojure.string :as str]))

(defn of-os-name
  "The platform an os.name value names."
  [^String os-name]
  (if (str/starts-with? (str/lower-case os-name) "windows") :platform/windows :platform/linux))

(defn current
  "This process's platform, or `opts`' :platform when it names one."
  ([] (of-os-name (System/getProperty "os.name")))
  ([opts] (or (:platform opts) (current))))
