(ns aether.director.waker-test
  "The platform value, and the ring's two wakers: EventWaker (named events, the
  Windows section transport) and ParkWaker (no events: spin, then park)."
  (:require [aether.director.platform :as platform]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director EventWaker Native ParkWaker Waker)))

(def windows? (= :platform/windows (platform/current)))

(deftest the-platform-comes-from-the-os-name-unless-overridden
  (is (= :platform/windows (platform/of-os-name "Windows 11")))
  (is (= :platform/windows (platform/of-os-name "windows server 2022")))
  (is (= :platform/linux (platform/of-os-name "Linux")))
  (is (#{:platform/windows :platform/linux} (platform/current)))
  (is (= :platform/linux (platform/current {:platform :platform/linux})))
  (is (= (platform/current) (platform/current {}))))

(defn- nanos-of ^long [f]
  (let [t0 (System/nanoTime)] (f) (- (System/nanoTime) t0)))

(deftest park-waker-spins-while-hot-then-parks-on-a-growing-schedule
  (testing "the default schedule is vfs-ipc's AdaptiveNotifier (notifier.rs:71-109)"
    (let [w (ParkWaker.)]
      (is (= 400000 (.spinNanos w)))
      (is (= 100000 (.parkNanos w 0)) "quiet up to 5 ms: the floor")
      (is (= 100000 (.parkNanos w 5000000)))
      (is (= 500000 (.parkNanos w 5000001)) "quiet past 5 ms")
      (is (= 500000 (.parkNanos w 50000000)))
      (is (= 2000000 (.parkNanos w 50000001)) "quiet past 50 ms: the cap")
      (is (= 2000000 (.parkNanos w Long/MAX_VALUE)))))
  (testing "configurable"
    (let [w (ParkWaker. 10000 1000 2000 3000)]
      (is (= 10000 (.spinNanos w)))
      (is (= [1000 2000 3000] (mapv #(.parkNanos w (long %)) [0 6000000 60000000])))))
  (testing "inside the spin window idle returns at once; outside it parks"
    (let [w (ParkWaker. 1000000000 20000000 20000000 20000000)]
      (is (< (nanos-of #(.idle w 0)) 5000000) "hot: spins")
      (is (>= (nanos-of #(.idle w 2000000000)) 10000000) "quiet: parks (20 ms asked)")))
  (testing "completed does nothing; wakeAll unparks a parked worker"
    (let [w     (ParkWaker. 0 5000000000 5000000000 5000000000)   ; 5 s parks
          done  (promise)
          t     (doto (Thread. ^Runnable (fn [] (deliver done (nanos-of #(.idle w Long/MAX_VALUE)))))
                  (.start))]
      (.completed w)
      (Thread/sleep 100)
      (.wakeAll w)
      (is (< (long (deref done 3000 Long/MAX_VALUE)) 3000000000) "woken well before its 5 s park")
      (.join t 1000)
      (.close w))))

(deftest event-waker-is-the-section-transport-s-wake
  (if-not windows?
    (println "SKIP event-waker test: named events are Windows-only")
    (let [n   (str "Local\\vfs_waker_test_" (System/nanoTime))
          srv (Native/createEvent (str n "_srv"))
          cli (Native/createEvent (str n "_cli"))
          w   (EventWaker. srv cli 400000)]
      (try
        (testing "completed signals the client event"
          (.completed w)
          (is (= 0 (Native/waitFor cli 0)) "signalled")
          (is (= 0x102 (Native/waitFor cli 0)) "auto-reset"))
        (testing "hot: spins; quiet: a 1 ms wait on the server event"
          (is (< (nanos-of #(.idle w 0)) 1000000))
          (is (>= (nanos-of #(.idle w 1000000000)) 500000)))
        (testing "wakeAll signals the server event, so a waiting worker returns"
          (.wakeAll w)
          (is (= 0 (Native/waitFor srv 0))))
        (finally (.close w))))))

(deftest the-no-op-waker-is-for-a-private-ring
  (let [^Waker w Waker/NONE]
    (.completed w) (.idle w 0) (.wakeAll w) (.close w)
    (is true)))
