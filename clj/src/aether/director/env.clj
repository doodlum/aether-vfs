(ns aether.director.env
  "The handshake env as data: every name the host writes and the shim or
  injector reads, mirroring rust/crates/vfs-env (its `handshake` tables,
  lib.rs:1115-1196), plus the pure rules a launch builds a child's
  environment by.

  A launch adds to an inherited environment, so whatever an earlier session
  left in this process (or its host) would reach the child. So every
  transport name a launch does not set, it clears (`stale`); `VFS_RING_PATH`
  in particular wins over `VFS_RING_SECTION` in the shim
  (vfs-shim/src/director.rs, ring_source), so an inherited one would send
  the child to another ring. Names compare without case on the Windows side
  (Wine included), so a removal clears every spelling (`removals`)."
  (:require [clojure.string :as str])
  (:import (java.util Map)))

(set! *warn-on-reflection* true)

;; --- names (vfs-env/src/lib.rs:44-186) ------------------------------------------

(def ring-section     "VFS_RING_SECTION")
(def ring-path        "VFS_RING_PATH")
(def ring-bytes       "VFS_RING_BYTES")
(def ring-payload-cap "VFS_RING_PAYLOAD_CAP")
(def arena-offset     "VFS_ARENA_OFFSET")
(def arena-len        "VFS_ARENA_LEN")
(def server-ev        "VFS_SERVER_EV")
(def client-ev        "VFS_CLIENT_EV")
(def fuse-cfg         "VFS_FUSE_CFG")
(def virtual-dir      "VFS_VIRTUAL_DIR")
(def virtual-roots    "VFS_VIRTUAL_ROOTS")
(def root-backings    "VFS_ROOT_BACKINGS")
(def registry         "VFS_REGISTRY")
(def inject-cwd       "VFS_INJECT_CWD")
(def inject-steam-helper "VFS_INJECT_STEAM_HELPER")
(def state-dir        "VFS_STATE_DIR")
(def home             "VFS_HOME")
(def launch-image     "VFS_LAUNCH_IMAGE")
(def discovery-path   "VFS_DISCOVERY_PATH")
(def shim-config      "VFS_SHIM_CONFIG")
(def shim-ready       "VFS_SHIM_READY")
(def child-refused-log "VFS_CHILD_REFUSED_LOG")
(def force-injection  "VFS_FORCE_INJECTION")

;; --- the tables (handshake::TRANSPORT, INJECT, CONFIG) -----------------------------

(def transport
  "What a launch sets or clears every time: the ring transport, its geometry,
  the roots and the registry switch."
  [ring-path ring-section ring-bytes ring-payload-cap arena-offset arena-len
   server-ev client-ev fuse-cfg virtual-dir virtual-roots root-backings registry])

(def inject
  "What only the injector reads. A Wine launch sets or clears these like
  `transport`; a Windows launch leaves them alone (its host may have set them
  for its own injector)."
  [inject-cwd inject-steam-helper])

(def config
  "The other handshake names: configuration for the shim, or staging. A launch
  doesn't clear them, but a caller's env may not set them on Linux."
  [state-dir home launch-image discovery-path shim-config shim-ready
   child-refused-log force-injection])

(defn handshake-names [] (concat transport inject config))

(defn handshake?
  "Whether `name` is a handshake name, ignoring ASCII case."
  [^String name]
  (boolean (some #(.equalsIgnoreCase ^String % name) (handshake-names))))

(defn stale
  "The names of `names` (default: `transport` and `inject`, as a Wine launch
  clears) that `set?` says the launch did not set itself: the ones to remove
  from the child's inherited environment."
  ([set?] (stale set? (concat transport inject)))
  ([set? names] (vec (remove set? names))))

(defn removals
  "The spellings among `inherited` (the parent's variable names) to remove:
  each matching a name in `remove` without regard to case, unless the launch
  sets that name itself (`set-names`, also compared without case). The
  removal half of vfs-proton launch/mod.rs:412-428 (clear_env)."
  [inherited remove set-names]
  (let [eq    (fn [^String a ^String b] (.equalsIgnoreCase a b))
        set?  (fn [n] (some #(eq % n) set-names))
        want? (fn [n] (and (some #(eq % n) remove) (not (set? n))))]
    (filterv want? inherited)))

(defn encode-roots
  "VFS_VIRTUAL_ROOTS: `id=location;…` for `roots`, or nil for none (the
  variable is then unset, not empty) (handshake::encode_roots,
  vfs-env/src/lib.rs:1182-1193). [[id location] …] keeps its order, as Rust
  does; a map {id location} goes in id order."
  [roots]
  (when (seq roots)
    (str/join ";" (for [[id at] (if (map? roots) (sort roots) roots)] (str id "=" at)))))

;; --- the transport's env ---------------------------------------------------------

(defn transport-env
  "The ring env for a server map (aether.director.ring/start): its geometry,
  and how the shim finds its backing. A :section names the section and both
  events; a :file names the ring file by `:ring-path`, the path as the shim
  opens it (vfs-shim/src/director.rs, connect_source), and no events: the
  shim maps a file ring with a null server event."
  [server]
  (merge {ring-bytes       (str (:map-bytes server))
          ring-payload-cap (str (:cap server))
          arena-offset     (str (:arena-offset server))
          arena-len        (str (:arena-len server))}
         (case (:transport server)
           :section {ring-section (:name server)
                     server-ev    (:server-event server)
                     client-ev    (:client-event server)}
           :file    {ring-path (:ring-path server)})))

(defn apply!
  "Applies a launch's env to `m` (a ProcessBuilder's environment): removes
  every key equal, ignoring case, to a name in `:remove`, then puts `:set`
  ([[k v] …]) in order, so a later entry wins."
  [^Map m {:keys [remove set]}]
  (let [gone (fn [^String k] (some #(.equalsIgnoreCase ^String % k) remove))]
    (doseq [k (filterv gone (vec (.keySet m)))] (.remove m k)))
  (doseq [[k v] set] (.put m k v))
  m)
