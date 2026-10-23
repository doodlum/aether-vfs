(ns aether.director.run
  "Launch a program with the Clojure director serving layered content:

    clojure -M:run --session <dir> --natives <dir>
                   [--zip <file>]... [--layer <dir>]...     ; root 0's content, later wins
                   [--write-layer <dir>]                   ; root 0's writes: a copy-on-write overlay
                   [--root <id>=<path>] [--root-layer <id>=<dir>]... [--root-write <id>=<dir>]  ; more roots
                   [--store <dir>]                         ; block storage (aether.director.storage)
                   [--store-write <id>=<layer>]...          ; a root's writes into a storage layer
                   [--registry <dir>]                      ; virtualize the registry: overlay.reg in <dir>
                   [--stage <vpath> [--fallback <dir>]... [--also <vpath>]...]
                   [--env K=V]...
                   [--transport section|file]                ; the ring: a named section (default) or state/ring.bin
                   [<exe> [args...]]

  The program sees <session>/root as a folder holding the content. With
  --stage, the image is the content's own <vpath>, staged with its import
  closure; otherwise <exe> is a real file. Exits with the program's code."
  (:require [aether.director.crossover]   ; the macOS launch's methods
            [aether.director.launch :as launch]
            [aether.director.provider.compose :as compose]
            [aether.director.provider.disk :as disk]
            [aether.director.provider.tree :as tree]
            [aether.director.platform :as platform]
            [aether.director.provider.zip :as zip]
            [aether.director.proton.launch :as plaunch]
            [aether.director.proton.prefix :as prefix]
            [aether.director.registry :as registry]
            [aether.director.ring :as ring]
            [aether.director.stage :as stage]
            [aether.director.storage :as storage]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.lang.foreign Arena))
  (:gen-class))

(defn- split-kv
  "\"k=v\" as [k v]."
  [s]
  (str/split s #"=" 2))

(defn- parse [args]
  (loop [[a b & more :as args] args opts {:env {} :content [] :fallback [] :also []}]
    (case a
      "--session"     (recur more (assoc opts :session b))
      "--natives"     (recur more (assoc opts :natives b))
      "--zip"         (recur more (update opts :content conj [:zip b]))
      "--layer"       (recur more (update opts :content conj [:dir b]))
      "--root"        (let [[id at] (split-kv b)] (recur more (assoc-in opts [:roots (parse-long id)] at)))
      "--write-layer" (recur more (assoc-in opts [:write 0] b))
      "--root-write"  (let [[id d] (split-kv b)] (recur more (assoc-in opts [:write (parse-long id)] d)))
      "--root-layer"  (let [[id d] (split-kv b)]
                        (recur more (update-in opts [:root-content (parse-long id)] (fnil conj []) [:dir d])))
      "--registry"    (recur more (assoc opts :registry b))
      "--store"       (recur more (assoc opts :store b))
      "--store-write" (let [[id nm] (split-kv b)] (recur more (assoc-in opts [:store-write (parse-long id)] nm)))
      "--stage"       (recur more (assoc opts :stage b))
      "--fallback"    (recur more (update opts :fallback conj b))
      "--also"        (recur more (update opts :also conj b))
      "--trace"       (recur (cons b more) (assoc opts :trace true))
      "--env"         (let [[k v] (split-kv b)] (recur more (assoc-in opts [:env k] v)))
      "--transport"   (if (#{"section" "file"} b)
                        (recur more (assoc opts :transport (keyword b)))
                        (throw (ex-info (str "--transport is section or file, not " (pr-str b)) {:transport b})))
      (assoc opts :exe a :args (vec (rest args))))))

(defn- content
  "[[path data] …] from every layer, in order: tree/build lets later win."
  [^Arena arena layers]
  (vec (mapcat (fn [[kind src]]
                 (case kind
                   :zip (zip/open arena src)
                   :dir (tree/dir-files arena src)))
               layers)))

(defn launch!
  "Serves `root` (a provider: root 0) and `roots` ({id {:path p :provider
  prov}}: more roots, each virtualized at the real path p) and runs the
  program; returns its exit code once it has ended and the director has
  stopped. Options:
    :session dir (required)   :natives dir (rust/'s vfs-injector and shim)
    :stage vpath  :fallback [dir]  :also [vpath]   the launch image, staged from root 0
    :exe path :args [arg]     a real program instead (or the staged one's args)
    :cwd vpath                the program's working directory in root 0 (default: its root)
    :env {k v}  :registry dir  :trace bool
    :transport :section|:file the ring's backing (ring/start); a file ring is
                              <session>/state/ring.bin
    :platform                 :platform/windows|:platform/linux|:platform/macos,
                              overriding this process's (aether.director.platform)
    :proton {…}               a Linux launch's runtime, prefix and Steam
                              options (launch/resolve-proton); `roots`' paths
                              are then C: locations
    :crossover {…}            a macOS launch's CrossOver install and bottle
                              (aether.director.crossover/resolve); `roots`'
                              paths are then C: locations
    :on-launch (fn [launch])  called with the started launch/Launch, so a
                              host can poll it or launch/stop! it while this waits
    :log (fn [& xs])          progress lines"
  [root roots {:keys [session stage fallback also registry trace transport log on-launch]
               :or {log (fn [& _])} :as opts}]
  (let [platform  (platform/current opts)
        wine?     (platform/wine? platform)
        stage-dir (io/file session "stage")
        ;; STAGE_EXEC: a child EXE that only the VFS holds, staged on demand
        on-demand (fn [vpath] (:exe (stage/stage root vpath {:dir stage-dir :fallback fallback})))
        reg    (when registry (registry/registry (doto (io/file registry) (.mkdirs))))
        server (ring/start root {:name (str "Local\\vfs_ring_clj_" (.pid (ProcessHandle/current)))
                                 :transport transport
                                 :platform platform
                                 :path (io/file session "state" "ring.bin")
                                 ;; under Wine the shim opens the ring through the session's link
                                 :shim-path (when wine? (prefix/join-wine plaunch/wine-session "state/ring.bin"))
                                 :stage-exec on-demand
                                 :trace trace
                                 :registry reg
                                 :roots (into {} (for [[id {:keys [provider]}] roots] [id provider]))})
        _      (log "serving; warm-up" (:warm-up server))
        s      (launch/session server session {:platform platform
                                                :roots (into {} (for [[id {:keys [path]}] roots] [id path]))})
        staged (when stage
                 (stage/stage root stage {:dir stage-dir :fallback fallback :also also}))
        _      (when staged (log "staged" (:staged staged)))]
    (try
      (let [l    (launch/start! s (assoc opts :platform platform
                                         :exe (or (:exe staged) (:exe opts))
                                         ;; the shim makes the launch image's folder a second
                                         ;; spelling of root 0, so it must be the stage
                                         ;; folder itself: an exe staged at
                                         ;; stage/bin/x64/game.exe finds ../../archive there
                                         :launch-image (when staged
                                                         (str (io/file stage-dir (.getName (io/file (:exe staged))))))))
            _    (when on-launch (on-launch l))
            code (launch/await-exit l)]
        (log "exit" code (ring/stats server))
        (doseq [[k n] (ring/trace-top server 25)] (log "trace" n k))
        code)
      (finally
        (when staged (stage/cleanup! staged stage-dir))
        (ring/stop server)
        (when reg (registry/close! reg))))))

(defn -main [& args]
  (let [opts   (parse args)
        t0     (System/nanoTime)
        ms     #(long (/ (- (System/nanoTime) t0) 1e6))
        log    (fn [& xs] (binding [*out* *err*] (apply println "[director]" (ms) "ms:" xs)))
        arena  (Arena/ofShared)
        stor   (when-let [d (:store opts)] (storage/open d))
        ;; each root: its layers indexed, under a copy-on-write overlay when it
        ;; has a write layer (a folder, or a storage layer)
        root   (fn [id layers]
                 (let [lower (tree/build (content arena layers))
                       upper (if-let [nm (get-in opts [:store-write id])]
                               (storage/layer (or stor (throw (ex-info "--store-write needs --store" {}))) nm)
                               (some-> (get-in opts [:write id]) disk/disk))]
                   (if upper (compose/overlay upper lower) lower)))
        t      (root 0 (:content opts))
        more   (into {} (for [[id path] (:roots opts)]
                          [id {:path path :provider (root id (get-in opts [:root-content id] []))}]))
        _      (log "content indexed")
        code   (launch! t more (assoc opts :log log))]
    (when stor (storage/close stor))
    (.close arena)
    (shutdown-agents)
    (System/exit code)))
