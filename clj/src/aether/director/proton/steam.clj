(ns aether.director.proton.steam
  "What a Proton launch does about Steam (vfs-proton/src/steam.rs, whose docs
  at :1-65 are the full story), so that the target's Steam API finds the
  running host client without the game being started from it.

  A direct `wine` launch has no Proton helper (steam.exe), so the pid in the
  prefix's HKCU\\Software\\Valve\\Steam\\ActiveProcess names a dead process, or
  by coincidence of Wine's pid numbering a live unrelated one. In a prefix
  Proton set up, with an app id and a running client, the injector starts the
  helper beside the target before creating it (:helper); otherwise it only
  clears the stale pid (:off). A prefix Proton did not set up has no Steam
  state to look after (:untouched).

  The injector writes what it did to <ready>.steam-helper; helper-status reads
  it back and helper-note turns it into the one line a launch says."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def helper-command
  "The command line that starts Proton's Steam helper, as a Wine process
  writes it: the helper, and rundll32.exe for it to run, which returns at once
  (steam.rs:77). Sent as VFS_INJECT_STEAM_HELPER."
  "C:\\windows\\system32\\steam.exe C:\\windows\\system32\\rundll32.exe")

(def helper-override
  "The WINEDLLOVERRIDES entry that makes steam.exe Proton's built-in helper (steam.rs:81)."
  "steam.exe=b")

(def report-suffix "Appended to the ready file's path for the helper report (vfs-env STEAM_HELPER_REPORT_SUFFIX)." ".steam-helper")

(defn state-dir
  "The dir the Steam client keeps steam.pid in: <home>/.steam; nil without a
  home (steam.rs:194-198)."
  [home]
  (when-not (str/blank? home)
    (str (str/replace home #"/+$" "") "/.steam")))

(defn client-pid
  "The client's pid, when `pid-text` (steam.pid's contents, or nil) names a
  process whose comm, by `comm-of` (pid -> /proc/<pid>/comm text or nil), is
  `steam`. A pid file outlives its process and the number is reused
  (steam.rs:211-220)."
  [pid-text comm-of]
  (when-let [pid (some-> pid-text str/trim parse-long)]
    (when (and (<= 0 pid 0xffffffff) (= "steam" (some-> (comm-of pid) str/trim)))
      pid)))

(defn- slurp-or-nil [f]
  (let [f (io/file f)] (when (.isFile f) (slurp f))))

(defn running-client
  "The pid of the Steam client running out of `state-dir`, or nil: steam.pid
  there names a live process called steam in `proc` (default /proc). Only the
  pid file is read, never configuration or credentials (steam.rs:206-220)."
  ([state-dir] (running-client state-dir "/proc"))
  ([state-dir proc]
   (client-pid (slurp-or-nil (io/file state-dir "steam.pid"))
               #(slurp-or-nil (io/file proc (str %) "comm")))))

(defn not-running-note
  "The one line a launch says when it leaves the helper out because no client
  is running (steam.rs:224-230)."
  [state-dir]
  (str "aether-vfs: the Steam client is not running (no live process for " state-dir
       "/steam.pid), so the program runs without Steam"))

(defn helper-report-path
  "Where the injector reports on the helper: the ready file + .steam-helper (steam.rs:118-122)."
  [ready]
  (str ready report-suffix))

(defn parse-helper-report
  "The injector's one-line helper report as a status (steam.rs:141-159):
  started:<pid>:<ms>, cleared, or anything else, failed:/disabled: stripped,
  as :helper/not-running with :why."
  [raw]
  (let [raw (str/trim raw)]
    (or (when (= "cleared" raw) {:helper/status :helper/cleared})
        (when-let [[_ pid ms] (re-matches #"started:(\d+):(\d+)" raw)]
          {:helper/status :helper/started :pid (parse-long pid) :ms (parse-long ms)})
        {:helper/status :helper/not-running
         :why (cond (str/starts-with? raw "failed:")   (subs raw 7)
                    (str/starts-with? raw "disabled:") (subs raw 9)
                    :else raw)})))

(defn helper-status
  "The helper's status for a launch whose steam-side mode is `mode`, given the
  report's text (nil when absent), whether the injector is past the helper
  step (its wine exited), and whether the ready file exists: the injector
  reports before it creates the target, so either makes a missing report
  :helper/unreported, an injector that predates the helper (steam.rs:130-139)."
  [mode report past-helper? ready-exists?]
  (cond (= :untouched mode)            {:helper/status :helper/not-requested}
        report                         (parse-helper-report report)
        (or past-helper? ready-exists?) {:helper/status :helper/unreported}
        :else                          {:helper/status :helper/pending}))

(defn read-helper-status
  "helper-status from the files beside `ready`."
  [mode ready past-helper?]
  (helper-status mode (slurp-or-nil (helper-report-path ready)) past-helper? (.exists (io/file ready))))

(defn helper-note
  "The one line a launch says about `status`, or nil (steam.rs:162-178)."
  [mode {:keys [helper/status why]}]
  (case status
    :helper/not-running (str "aether-vfs: the Steam helper is not running (" why "), so the program runs "
                             "without Steam")
    :helper/unreported  (str "aether-vfs: vfs-injector.exe did not report on the Steam helper"
                             (when (= :helper mode) ", so the program runs without Steam")
                             ": the Windows artifacts are older than this aether-vfs; rebuild them "
                             "(bin/build-windows)")
    nil))

(defn- app-id [init env]
  (let [id (or (get-in init [:proton :app-id])
               (some-> (get env "SteamAppId") str/trim parse-long))]
    (when (and id (pos? id)) id)))

(defn steam-side
  "The Steam side of a launch, as data (vfs-embed session/proton/mod.rs:210-252,
  the env from vfs-proton launch/env.rs:121-142):
    :prefix-init  :wineboot | {:proton {:steam-client dir :app-id n}}
    :helper?      whether the helper may start (default true)
    :env          the launch's own env, whose SteamAppId stands in for :app-id
    :state-dir    the client's state dir (state-dir), nil without HOME
    :client-pid   (running-client state-dir), nil when none runs
  => {:mode :untouched|:off|:helper :env {…} :notes [line…]}, and with
  :helper :app-id, :client and :dll-overrides (helper-override, to merge into
  WINEDLLOVERRIDES). :client must be absolute: it is sent as is."
  [{:keys [prefix-init helper? env state-dir client-pid] :or {helper? true}}]
  (let [off (fn [notes] {:mode :off :env {"VFS_INJECT_STEAM_HELPER" "off"} :notes notes})
        id  (app-id prefix-init env)]
    (cond
      (not (map? prefix-init))  {:mode :untouched :env {} :notes []}
      (not (and helper? id))    (off [])
      (nil? state-dir)          (off ["aether-vfs: HOME is not set, so no Steam client can be found and the program runs without Steam"])
      (nil? client-pid)         (off [(not-running-note state-dir)])
      :else
      (let [client (get-in prefix-init [:proton :steam-client])]
        {:mode :helper :app-id id :client client :dll-overrides helper-override :notes []
         :env {"SteamAppId" (str id) "SteamGameId" (str id)
               "STEAM_COMPAT_CLIENT_INSTALL_PATH" client
               "VFS_INJECT_STEAM_HELPER" helper-command}}))))
