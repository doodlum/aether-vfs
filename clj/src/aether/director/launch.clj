(ns aether.director.launch
  "Launching a program under the director: the host half of vfs-embed's
  Session::serve + Session::launch.

  The game sees an empty directory, the managed root, as its folder; the shim
  inside it turns every path under that root into a ring request. The shim
  finds the ring through its environment and two files in the session's
  state directory:

    shim.cfg   root, overlay dir, an empty static-import table, and an empty
               snapshot (the shim refuses zero-length snapshot bytes)
    fuse.cfg   the ring's coordinates, as key=value lines

  Injection itself stays native: rust/'s vfs-injector creates the process
  suspended, injects the payload and the shim, waits for the shim's ready
  file, resumes, and exits with the target's code. The target inherits the
  injector's environment and working directory, and the injector inherits
  ours, so the child's environment is built explicitly here, never by
  changing this process's own."
  (:require [aether.director.ring :as ring]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream File)
           (java.lang ProcessBuilder$Redirect)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)))

;; vfs-embed's EMPTY_TREE_SNAPSHOT_HEX: a single empty root directory (128 bytes)
(def ^:private empty-snapshot-hex
  (str "535346560100000000000000000000008000000000000000010000003000000000000000"
       "800000000000000080000000000000000000000080000000000000000000000000000000"
       "800000000000000000000000000000000000000000000000000000000000000000000000"
       "0000000000000000000000000000000000000000"))

(defn- hex->bytes ^bytes [^String hex]
  (let [b (byte-array (quot (count hex) 2))]
    (dotimes [i (alength b)]
      (aset b i (unchecked-byte (Integer/parseInt (subs hex (* 2 i) (+ 2 (* 2 i))) 16))))
    b))

(defn shim-config
  "shim.cfg bytes (vfs-shim's encode_config_full with no static imports):
  [u32 len][root][u32 len][overlay] \"VFS1\" [u32 0] [snapshot]"
  ^bytes [^String root ^String overlay]
  (let [out (ByteArrayOutputStream.)
        u32 (fn [n] (.write out (.array (.putInt (.order (ByteBuffer/allocate 4) ByteOrder/LITTLE_ENDIAN) (int n)))))
        str! (fn [^String s] (let [b (.getBytes s StandardCharsets/UTF_8)] (u32 (alength b)) (.write out b)))]
    (str! root)
    (str! overlay)
    (.write out (.getBytes "VFS1" StandardCharsets/US_ASCII))
    (u32 0)
    (.write out (hex->bytes empty-snapshot-hex))
    (.toByteArray out)))

(defn- canonical ^String [f] (.getCanonicalPath (io/file f)))

(defn session
  "Creates the session's directories under `dir`: root/ (the managed root,
  empty), overlay/ and state/, and writes shim.cfg and fuse.cfg for `server`
  (from ring/start). Returns the session map launch takes. `:overlay` puts the
  shim's write overlay somewhere other than <dir>/overlay."
  [server dir & [{:keys [overlay]}]]
  (let [[root overlay state] (for [f [(io/file dir "root") (io/file (or overlay (io/file dir "overlay")))
                                      (io/file dir "state")]]
                               (doto f (.mkdirs)))
        root-s  (canonical root)
        shim    (io/file state "shim.cfg")
        fuse    (io/file state "fuse.cfg")
        {:keys [name cap map-bytes arena-offset arena-len server-event client-event]} server]
    (io/copy (shim-config root-s (canonical overlay)) shim)
    (spit fuse (str "section=" name "\nroot=" root-s "\npayload_cap=" cap "\nring_bytes=" map-bytes
                    "\narena_offset=" arena-offset "\narena_len=" arena-len
                    "\nserver_ev=" server-event "\nclient_ev=" client-event "\n"))
    {:root root-s :overlay (canonical overlay) :state (canonical state)
     :shim-config (canonical shim) :ready (str (canonical state) File/separator "ready.flag")
     :env {"VFS_RING_SECTION"     name
           "VFS_RING_BYTES"       (str map-bytes)
           "VFS_RING_PAYLOAD_CAP" (str cap)
           "VFS_ARENA_OFFSET"     (str arena-offset)
           "VFS_ARENA_LEN"        (str arena-len)
           "VFS_SERVER_EV"        server-event
           "VFS_CLIENT_EV"        client-event
           "VFS_FUSE_CFG"         (canonical fuse)
           "VFS_VIRTUAL_DIR"      root-s}}))

(defn launch
  "Runs `exe` (a real file) with `args` under `session`, via the injector at
  `natives` (a directory holding vfs-injector.exe, vfs_shim_dll.dll and
  vfs_payload.dll). `env` adds variables for the target. Waits for it and
  returns its exit code; the injector's and target's output go to ours."
  [session {:keys [natives exe args env]}]
  (let [native (fn [n] (let [f (io/file natives n)]
                         (when-not (.isFile f) (throw (ex-info (str n " not found in " natives) {:file f})))
                         (canonical f)))
        cmd    (concat [(native "vfs-injector.exe") (canonical exe)
                        (native "vfs_shim_dll.dll") (native "vfs_payload.dll")
                        (:shim-config session) (:ready session)]
                       (when (seq args) (cons "--" args)))
        pb     (doto (ProcessBuilder. ^java.util.List (vec cmd))
                 (.directory (io/file (:root session)))
                 (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                 (.redirectError ProcessBuilder$Redirect/INHERIT))]
    (io/delete-file (:ready session) true)
    (doto (.environment pb)
      (.putAll ^java.util.Map (:env session))
      (.putAll ^java.util.Map (or env {})))
    (.waitFor (.start pb))))
