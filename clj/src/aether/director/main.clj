(ns aether.director.main
  "Serves the benchmark fixture over a ring, for vfs-ring-bench:
  prints `SECTION <name> <map_bytes>` and serves until stdin closes."
  (:require [aether.director.fixture :as fixture]
            [aether.director.provider.tree :as tree]
            [aether.director.ring :as ring])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn -main [& args]
  (let [opts   (into {} (for [[k v] (partition 2 args)] [(keyword (subs k 2)) (parse-long v)]))
        server (ring/start (tree/build (fixture/files))
                           (merge {:name (str "Local\\vfs_ring_clj_" (.pid (ProcessHandle/current)))}
                                  (select-keys opts [:workers :spin-us :warm-up])))]
    (binding [*out* *err*] (println "warm-up" (:warm-up server)))
    (println "SECTION" (:name server) (:map-bytes server))
    (flush)
    (while (not= -1 (.read System/in)))       ; serve until our stdin closes
    (binding [*out* *err*] (println "served" (ring/stats server)))
    (ring/stop server)
    (shutdown-agents)
    (System/exit 0)))
