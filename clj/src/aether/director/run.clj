(ns aether.director.run
  "Launch a program with the Clojure director serving layered content:

    clojure -M:run --session <dir> --natives <dir>
                   [--zip <file>]... [--layer <dir>]...     ; content, later wins
                   [--stage <vpath> [--fallback <dir>]... [--also <vpath>]...]
                   [--env K=V]...
                   [<exe> [args...]]

  The program sees <session>/root as a folder holding the content. With
  --stage, the image is the content's own <vpath>, staged with its import
  closure; otherwise <exe> is a real file. Exits with the program's code."
  (:require [aether.director.launch :as launch]
            [aether.director.ring :as ring]
            [aether.director.stage :as stage]
            [aether.director.tree :as tree]
            [aether.director.zip :as zip]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.lang.foreign Arena))
  (:gen-class))

(defn- parse [args]
  (loop [[a b & more :as args] args opts {:env {} :content [] :fallback [] :also []}]
    (case a
      "--session" (recur more (assoc opts :session b))
      "--natives" (recur more (assoc opts :natives b))
      "--zip"     (recur more (update opts :content conj [:zip b]))
      "--layer"   (recur more (update opts :content conj [:dir b]))
      "--stage"   (recur more (assoc opts :stage b))
      "--fallback" (recur more (update opts :fallback conj b))
      "--also"    (recur more (update opts :also conj b))
      "--env"     (let [[k v] (str/split b #"=" 2)] (recur more (assoc-in opts [:env k] v)))
      (assoc opts :exe a :args (vec (rest args))))))

(defn- content
  "[[path data] …] from every layer, in order: tree/build lets later win."
  [^Arena arena layers]
  (vec (mapcat (fn [[kind src]]
                 (case kind
                   :zip (zip/open arena src)
                   :dir (tree/dir-files arena src)))
               layers)))

(defn -main [& args]
  (let [{:keys [session natives stage fallback also] :as opts} (parse args)
        t0     (System/nanoTime)
        ms     #(long (/ (- (System/nanoTime) t0) 1e6))
        log    (fn [& xs] (binding [*out* *err*] (apply println "[director]" (ms) "ms:" xs)))
        arena  (Arena/ofShared)
        t      (tree/build (content arena (:content opts)))
        _      (log "content indexed")
        stage-dir (io/file session "stage")
        ;; STAGE_EXEC: a child EXE that only the VFS holds, staged on demand
        on-demand (fn [vpath] (:exe (stage/stage t vpath {:dir stage-dir :fallback fallback})))
        server (ring/start t {:name (str "Local\\vfs_ring_clj_" (.pid (ProcessHandle/current)))
                              :stage-exec on-demand})
        _      (log "serving; warm-up" (:warm-up server))
        s      (launch/session server session)
        staged (when stage
                 (stage/stage t stage {:dir stage-dir :fallback fallback :also also}))
        _      (when staged (log "staged" (:staged staged)))
        exe    (or (:exe staged) (:exe opts))
        code   (launch/launch s (assoc opts :exe exe :launch-image (:exe staged)))]
    (log "exit" code (ring/stats server))
    (when staged (stage/cleanup! staged stage-dir))
    (ring/stop server)
    (.close arena)
    (shutdown-agents)
    (System/exit code)))
