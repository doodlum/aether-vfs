(ns aether.director.proton.ring-home-test
  "aether.director.proton.ring-home, ported from vfs-embed's
  session/proton/ring.rs tests. Stat data stands in for the filesystem."
  (:require [aether.director.proton.ring-home :as home]
            [clojure.test :refer [deftest is testing]]))

;; A trimmed mountinfo: a disk root, a tmpfs under it, a disk mount under that
;; tmpfs, and a mount point with a space in its name.
(def ^:private mounts
  (str "25 1 0:23 / / rw,relatime shared:1 - btrfs /dev/mapper/root rw,compress=zstd:3\n"
       "30 25 0:27 / /run rw,nosuid shared:2 - tmpfs tmpfs rw,mode=755\n"
       "61 30 0:52 / /run/user/1000 rw,nosuid,nodev shared:9 - tmpfs tmpfs rw,size=9646860k\n"
       "70 61 8:1 / /run/user/1000/disk rw - ext4 /dev/sda1 rw\n"
       "80 25 0:60 / /mnt/my\\040ram rw - ramfs none rw\n"))

(deftest a-directory-is-in-memory-only-if-its-innermost-mount-is
  (let [on #(home/memory-fs? mounts %)]
    (is (on "/run/user/1000"))
    (is (on "/run/user/1000/aether-vfs/ring-1"))
    (is (on "/run/lock"))
    (is (on "/mnt/my ram/x") "an escaped space in a mount point")
    (is (not (on "/home/me/state")) "the disk root")
    (is (not (on "/run/user/1000/disk/x")) "a disk mounted inside a tmpfs is a disk")
    (is (on "/run/user/1000/disk2/x") "a sibling whose name merely starts the same is on the tmpfs around it")
    (is (not (home/memory-fs? "" "/run")) "no table, no claim")))

(deftest a-later-mount-over-the-same-point-shadows-the-earlier
  (let [m (str mounts "90 61 0:70 / /run/user/1000 rw - ext4 /dev/sdb1 rw\n")]
    (is (not (home/memory-fs? m "/run/user/1000/x")))))

(deftest only-a-directory-private-to-this-user-may-hold-the-ring
  (let [mine {:dir? true :symlink? false :uid 1000 :mode 0700}]
    (is (home/private-dir? mine 1000))
    (doseq [open [01777 0755 0750 0701]]
      (is (not (home/private-dir? (assoc mine :mode open) 1000)) (format "mode %o is not private" open)))
    (is (not (home/private-dir? (assoc mine :uid 0) 1000)) "not ours")
    (is (not (home/private-dir? (assoc mine :symlink? true) 1000)) "a link to a private directory is not ours")
    (is (not (home/private-dir? (assoc mine :dir? false) 1000)) "a file")
    (is (not (home/private-dir? nil 1000)) "absent")))

(deftest the-ring-directory-is-named-for-the-canonical-state-directory
  (let [a (home/ring-dir "/run/user/1000" "/home/me/s/state")]
    (is (re-matches #"/run/user/1000/aether-vfs/ring-[0-9a-f]{16}" a) a)
    (is (= a (home/ring-dir "/run/user/1000/" "/home/me/s/state")) "stable for one directory")
    (is (not= a (home/ring-dir "/run/user/1000" "/home/me/s/state/sub")) "distinct for another")))

(def ^:private private-runtime
  {:xdg-runtime-dir "/run/user/1000" :mountinfo mounts :uid 1000
   :stat {"/run/user/1000" {:dir? true :symlink? false :uid 1000 :mode 0700}}
   :state-dir "/home/me/s/state" :file-name "ring.bin"})

(deftest the-ring-goes-in-memory-under-a-private-tmpfs-runtime-dir
  (let [{:keys [dirs file]} (home/ring-in-memory private-runtime)
        ring                (home/ring-dir "/run/user/1000" "/home/me/s/state")]
    (is (= ["/run/user/1000/aether-vfs" ring] dirs) "made 0700 in order, each checked private after")
    (is (= (str ring "/ring.bin") file) "the same file name as the state dir's")))

(deftest otherwise-the-ring-stays-in-the-state-dir
  (testing "no runtime dir"
    (is (nil? (home/ring-in-memory (dissoc private-runtime :xdg-runtime-dir))))
    (is (nil? (home/ring-in-memory (assoc private-runtime :xdg-runtime-dir "")))))
  (testing "a relative one"
    (is (nil? (home/ring-in-memory (assoc private-runtime :xdg-runtime-dir "run/user/1000")))))
  (testing "on a disk"
    (is (nil? (home/ring-in-memory (assoc private-runtime :xdg-runtime-dir "/home/me/rt"
                                          :stat {"/home/me/rt" {:dir? true :uid 1000 :mode 0700}})))))
  (testing "not private: XDG_RUNTIME_DIR=/tmp style"
    (is (nil? (home/ring-in-memory (assoc-in private-runtime [:stat "/run/user/1000" :mode] 01777))))
    (is (nil? (home/ring-in-memory (assoc-in private-runtime [:stat "/run/user/1000" :uid] 0))))))
