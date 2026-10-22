(ns aether.director.proton.launch
  "Launching a Windows target under GE-Proton's Wine with the shim injected
  (vfs-proton/src/launch/{mod,injector}.rs), the pure parts.

  Nothing about a launch is interesting except what a test can check without
  spawning anything (launch/mod.rs:1-34):

  - The injector's argv is positional: `wine vfs-injector.exe <target> <shim>
    <config> <ready> [-- args…]`. Swap two and everything still starts; the
    shim just never attaches.
  - The ring geometry must be the director's real one. The shim defaults
    VFS_RING_BYTES to 2 MiB, which attaches cleanly over a ~34 MiB ring and
    fails only under load.

  A launch here is a map; the host paths are Linux strings, the target is in
  C: form (proton.prefix/windows-path):
    :runtime :injector :shim-dll :config-file :ready-file   host paths
    :target                                                C:\\… path
    :args [s…]
    :ring-bytes :arena-offset :arena-len                   the live geometry
    :ring-host-path                                        the ring file, for the length check

  The spawn comes later:

    (spawn! l env)
      ;; launch/mod.rs:350-404. check-geometry (with the ring file's length),
      ;; verify-ge, delete stale-reports, then command-line through ProcessBuilder
      ;; with `env` (proton.env/launch-env) and every stale or removed name
      ;; cleared in each spelling; setsid for a process group of its own; stdout
      ;; and stderr appended to one log file, the notes first; retried on
      ;; ETXTBSY (error=26). Its Launch's await-exit is finish over the wine
      ;; exit code and the injector-error report, then wineserver -w."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- under [runtime & parts]
  (str/join "/" (cons (str/replace runtime #"/+$" "") parts)))

(defn wine-binary
  "`wine` inside a GE-Proton runtime: never a host wine on PATH, which is not
  the verified build (launch/mod.rs:308-310)."
  [runtime]
  (under runtime "files" "bin" "wine"))

(defn wineserver-binary
  "`wineserver` inside a GE-Proton runtime (prefix/wineserver.rs:27)."
  [runtime]
  (under runtime "files" "bin" "wineserver"))

(defn command-line
  "[program argv] for a launch: wine, then the injector's positional contract
  <injector> <target> <shim> <config> <ready>, then `--` and the target's args
  when there are any (launch/mod.rs:318-335). Never reorder it."
  [{:keys [runtime injector target shim-dll config-file ready-file args]}]
  [(wine-binary runtime)
   (cond-> [injector target shim-dll config-file ready-file]
     (seq args) (into (cons "--" args)))])

(defn check-geometry
  "Refuses a launch whose ring geometry cannot describe the ring, before
  anything is spawned (launch/mod.rs:471-496): an arena past ring-bytes, or a
  ring file (`host-length` bytes; nil skips, as does a missing file) shorter
  than ring-bytes. Both attach cleanly and fail only under load. nil when fine;
  throws {:launch/error :launch/geometry}."
  [{:keys [ring-bytes arena-offset arena-len ring-host-path]} host-length]
  (let [need (+ (long arena-offset) (long arena-len))]
    (cond
      (> need (long ring-bytes))
      (throw (ex-info (str "ring geometry is inconsistent: arena_offset " arena-offset " + arena_len " arena-len
                           " = " need " exceeds ring_bytes " ring-bytes "; the child would map a view too small "
                           "to hold the arena and fail only under load")
                      {:launch/error :launch/geometry}))
      (and host-length (< (long host-length) (long ring-bytes)))
      (throw (ex-info (str "ring geometry is inconsistent: ring file " ring-host-path " is " host-length
                           " bytes but ring_bytes is " ring-bytes "; mapping past the end of a file faults on "
                           "touch rather than failing at map time")
                      {:launch/error :launch/geometry})))))

(defn injector-error-path
  "Where the injector says why it failed: the ready file + .injector-error (launch/injector.rs:7-11)."
  [ready]
  (str ready ".injector-error"))

(defn child-refused-path
  "Where the shim lists children it killed because it could not inject them:
  the ready file + .child-refused (launch/injector.rs:15-19)."
  [ready]
  (str ready ".child-refused"))

(defn stale-reports
  "The reports beside `ready` a launch deletes before it spawns, since one an
  earlier launch left would be read as this one's (launch/mod.rs:354-363)."
  [ready]
  [(injector-error-path ready) (str ready ".steam-helper")])

(defn child-refusals
  "The child-refused file's lines (`<image> <reason>` each), from its text or
  nil (launch/injector.rs:23-33)."
  [text]
  (into [] (comp (map str/trim) (remove str/blank?)) (some-> text str/split-lines)))

(defn describe-child-refusal
  "One refusal line as a sentence for an error or a note (launch/injector.rs:36-41)."
  [line]
  (str "a child process was refused and killed because the shim could not inject it "
       "(`<image> <reason>`): " line))

(def ^:private status-hints
  {0xC0000135 (str " (STATUS_DLL_NOT_FOUND: a DLL the program imports is missing: stage it "
                   "(:also / :fallback), or launch in a Proton-initialized prefix, which carries the "
                   "DirectX and Visual C++ redistributables)")
   0xC000007B " (STATUS_INVALID_IMAGE_FORMAT: an imported DLL is not a PE of the right architecture)"
   0xC0000142 " (STATUS_DLL_INIT_FAILED: a DLL failed to initialise)"})

(defn describe-injector-error
  "A readable account of the injector's one-line failure report
  (launch/injector.rs:44-85; the prefixes are vfs-env's INJECTOR_*_PREFIX)."
  [raw]
  (let [raw   (str/trim raw)
        after (fn [prefix] (when (str/starts-with? raw prefix) (subs raw (count prefix))))]
    (if-let [code (after "target-exited:")]
      (str "the target exited with " code
           (get status-hints (some-> (re-matches #"(?:0x)?([0-9a-fA-F]{1,8})" code) second (Long/parseLong 16)))
           " before the shim reported ready")
      (if-let [secs (after "ready-timeout:")]
        (str "the shim did not report ready within " secs " s, so the target was killed before it ran: it is "
             "hung or still starting; raise the ready timeout if a cold prefix is this slow")
        (if-let [why (after "fuse-failed:")]
          (str "the shim could not attach to the director, so the target was killed before it ran: " why)
          (if-let [why (after "bootstrap-failed:")]
            (str "the shim could not bootstrap, so the target was killed before it ran: " why)
            (str "injection failed: " (or (after "inject:") raw))))))))

(defn finish
  "How a launch ended, from wine's exit `code` (nil: signalled) and the
  injector-error report's text (nil when absent) (launch/mod.rs:441-456):
  {:exit code} for the target's own code, any code but 2 and 3; for 2 (bad
  argv) and 3 (injection failed), the injector's, {:error msg :code c
  :launch/error :launch/injector} when it said why, else :launch/injector-exit.
  A target that picks 2 or 3 itself is reported as the injector's: the loud
  mistake, where the quiet one would hide an injection that never happened."
  [{:keys [runtime]} code report]
  (cond
    (nil? code)
    {:launch/error :launch/signalled :error (str (wine-binary runtime) " exited without a code (signalled)")}

    (not (#{2 3} code)) {:exit code}

    (not (str/blank? report))
    {:launch/error :launch/injector :code code
     :error (str "vfs-injector did not run the target: " (describe-injector-error report))}

    :else
    {:launch/error :launch/injector-exit :code code
     :error (str "vfs-injector exited " code " without running the target (2 = bad argv, 3 = injection failed)")}))
