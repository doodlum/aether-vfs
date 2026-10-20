(ns aether.director.source.login
  "Logging in to the content sources, at a terminal:

    clojure -M -m aether.director.source.login gog   <cache-dir> <credentials.json>
    clojure -M -m aether.director.source.login steam <login.json> [account]

  GOG: open the printed URL, log in, paste back the URL you land on. Steam:
  your password (not echoed) and any Steam Guard code; with no account, the
  saved file's. The files are owner-only; nothing secret is printed."
  (:require [aether.director.source.gog :as gog]
            [aether.director.source.net :as net]
            [aether.director.source.steam :as steam]
            [aether.director.source.steam.cm :as cm])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- prompt ^String [msg]
  (print msg) (flush)
  (or (read-line) (throw (ex-info "no input" {}))))

(defn- secret ^String [msg]
  (if-let [c (System/console)]
    (String. (.readPassword c "%s" (into-array Object [msg])))
    (prompt msg)))

(defn gog-login [cache-dir creds]
  (let [cfg (gog/config cache-dir creds)]
    (println "Open this URL, log in to GOG, and paste the URL you end up on:")
    (println (gog/login-url cfg))
    (gog/complete-login! (net/http) cfg (prompt "> "))
    (println "Logged in to GOG; saved to" creds)))

(defn steam-login [file account]
  (let [saved (try (steam/load-login file) (catch Exception _ nil))
        account (or account (:account saved) (prompt "Steam account: "))
        r (cm/login-password! account (secret "Steam password: ")
                              {:guard-data (when (= account (:account saved)) (:guard-data saved))
                               :device-code (fn [wrong?] (prompt (str (when wrong? "That code was wrong. ") "Steam Guard code from the app: ")))
                               :email-code (fn [domain wrong?] (prompt (str (when wrong? "That code was wrong. ") "Steam Guard code sent to your " domain " address: ")))
                               :confirm (fn [] (println "Approve the login in the Steam app…") true)})]
    (steam/save-login! file r)
    (println "Logged in to Steam as" (:account r) "; saved to" file)))

(defn -main [& [which & args]]
  (try
    (case which
      "gog" (apply gog-login args)
      "steam" (steam-login (first args) (second args))
      (println "usage: login gog <cache-dir> <credentials.json> | login steam <login.json> [account]"))
    (shutdown-agents)
    (System/exit 0)
    (catch Exception e
      (binding [*out* *err*] (println "login failed:" (ex-message e)))
      (System/exit 1))))
