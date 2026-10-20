(ns aether.director.proto
  "The ring's wire contract, as the shim speaks it (rust/crates/vfs-ipc and
  vfs-protocol). Everything is little-endian. Offsets are from the start of
  the shared mapping; nothing in shared memory is a pointer.")

;; --- ring header (vfs-ipc/src/layout.rs) -----------------------------------

(def ^:const magic 0x56464950)          ; "VFIP"
(def ^:const version 2)                 ; the shim refuses any other
(def ^:const ring-header-size 40)
(def ^:const rh-magic 0)
(def ^:const rh-version 4)
(def ^:const rh-slot-count 8)
(def ^:const rh-slot-stride 12)
(def ^:const rh-payload-cap 16)
(def ^:const rh-req-seq 24)             ; u64; written by clients, never read here
(def ^:const rh-submit-seq 32)          ; u32; likewise

;; --- slot header -----------------------------------------------------------

(def ^:const slot-header-size 32)
(def ^:const sh-state 0)
(def ^:const sh-opcode 4)
(def ^:const sh-flags 8)
(def ^:const sh-payload-len 12)
(def ^:const sh-status 16)
(def ^:const sh-req-id 24)

;; slot states: FREE -> CLAIMED (client) -> SUBMITTED (client) -> PROCESSING
;; (server, CAS) -> COMPLETED (server, release) -> FREE (client)
(def ^:const st-free 0)
(def ^:const st-claimed 1)
(def ^:const st-submitted 2)
(def ^:const st-processing 3)
(def ^:const st-completed 4)

;; --- geometry, as vfs-director's IpcServe lays it out ----------------------

(def ^:const slot-count 32)
(def ^:const payload-cap 1048576)
(def ^:const arena-bytes (* 32 1024 1024))

(defn align8 ^long [^long n] (bit-and (+ n 7) (bit-not 7)))

(defn geometry
  "Byte layout of a ring of `slots` slots with `cap`-byte payloads and an
  `arena`-byte bulk arena, matching vfs-director's IpcServe exactly."
  ([] (geometry slot-count payload-cap arena-bytes))
  ([slots cap arena]
   (let [stride     (align8 (+ slot-header-size cap))
         ring-bytes (+ ring-header-size (* slots stride))
         map-bytes  (max (bit-and (+ ring-bytes arena 0xFFFF) (bit-not 0xFFFF)) (* 2 1024 1024))]
     {:slots slots :cap cap :stride stride
      :ring-bytes ring-bytes
      :arena-offset ring-bytes
      :arena-len arena
      :bank-size (max (quot arena slots) 4096)
      :map-bytes map-bytes})))

;; --- opcodes (vfs-protocol/src/lib.rs) --------------------------------------

(def ^:const op-getattr 1)
(def ^:const op-readdir 2)
(def ^:const op-open 3)
(def ^:const op-read 5)
(def ^:const op-write 6)
(def ^:const op-setattr 7)
(def ^:const op-rename 8)
(def ^:const op-delete 9)
(def ^:const op-mkdir 10)
(def ^:const op-close 11)
(def ^:const op-heartbeat 13)

(def ^:const flag-read-bulk 1)
(def ^:const read-resp-bulk-bit 0x80000000)
(def ^:const bulk-threshold 65536)      ; reads this big go to the arena regardless of the flag

(def ^:const open-write 2)

;; --- status codes (vfs-provider/src/status.rs) ------------------------------

(def ^:const ok 0)
(def ^:const not-found -1)
(def ^:const not-a-directory -2)
(def ^:const bad-request -3)
(def ^:const io-error -4)
(def ^:const is-dir -5)
(def ^:const bad-fh -6)
(def ^:const read-only -9)
(def ^:const overflow Integer/MIN_VALUE)  ; a reply too big for the slot
