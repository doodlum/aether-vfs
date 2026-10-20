(ns aether.director.source.nexus-test
  "aether-nexus's tests, against a fake Nexus API and file host."
  (:require [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [aether.director.source.archive-test :refer [build-zip lcg nexus pattern seekable stored]]
            [aether.director.source.fake-server :as fake :refer [with-server]]
            [aether.director.source.net :as net]
            [aether.director.source.nexus :as nx]
            [charred.api :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director.provider Entry Stat)
           (java.lang.foreign MemorySegment)
           (java.time Instant)
           (java.util Arrays)
           (java.util.concurrent Executors TimeUnit)))

(def key* "test-key")
(def uid (nx/uid 1704 75329))

(defn- fake-nexus!
  "Routes on `s` for the Nexus API and file host. Returns its state atom:
  {:archives {uid bytes} :valid #{sig} :mints n :ttl-s n}."
  [s]
  (let [st (atom {:archives {} :valid #{} :mints 0 :ttl-s 3600 :hourly-remaining 100})
        api (fn [f] (fn [req]
                      (if (not= key* (get-in req [:headers "apikey"]))
                        [401 {"Content-Type" "application/problem+json"} (json/write-json-str {"detail" "bad key"})]
                        (let [[code body] (f req)]
                          [code {"Content-Type" "application/json"
                                 "x-rl-hourly-limit" "100" "x-rl-hourly-remaining" (str (:hourly-remaining @st))
                                 "x-rl-hourly-reset" "2099-01-01 00:00:00 +0000"}
                           (json/write-json-str body)]))))]
    (fake/route! s #"/v3/mod-file-versions/(\d+)/download-repacked"
                 (api (fn [{:keys [path]}]
                        (let [u (second (re-matches #".*/(\d+)/download-repacked" path))
                              n (:mints (swap! st update :mints inc))]
                          [200 {"download_url" (str (:url s) "/repacked/" u "?exp=0&kid=1&sig=" n)
                                "expires_at" (str (.plusSeconds (Instant/now) (:ttl-s @st)))}]))))
    (fake/route! s #"/v3/games/[^/]+/mod-file-versions/(\d+)"
                 (api (fn [{:keys [path]}]
                        (let [f (parse-long (second (re-matches #".*/(\d+)" path)))]
                          [200 {"data" {"id" (str (nx/uid 1704 f)) "name" "Main" "version" "1.0"}}]))))
    (fake/route! s #"/v1/users/validate\.json" (api (fn [_] [200 {"name" "tester" "is_premium" true "key" key*}])))
    (fake/route! s #"/repacked/(\d+)"
                 (fn [{:keys [path query]}]
                   (let [u (parse-long (second (re-matches #"/repacked/(\d+)" path)))
                         sig (second (re-find #"sig=(\d+)" (str query)))]
                     (if (contains? (:valid @st) (some-> sig parse-long))
                       [:file nil (get-in @st [:archives u])]
                       [403 {} "Invalid signature"]))))
    st))

(defn- client [s & [opts]]
  (nx/client (net/http {:retry {:max-attempts 3 :base-delay-ms 1 :max-delay-ms 10 :max-retry-after-ms 2000}})
             key* (merge {:base-url (:url s)} opts)))

(defmacro ^:private failure [form]
  `(try ~form nil (catch clojure.lang.ExceptionInfo e# {:kind (net/kind e#) :msg (ex-message e#)})))

;; --- the API and links -------------------------------------------------------------------

(deftest api-calls
  (with-server [s]
    (let [st (fake-nexus! s) c (client s)]
      (is (= {:uid uid :name "Main" :version "1.0" :category ""} (nx/file-record c "skyrimspecialedition" 75329)))
      (is (= {:name "tester" :premium? true} (nx/validate c)))
      (is (= 50 (:spare (nx/quota c))) "remaining above half the limit")
      (testing "a bad key is a clear error, and the key is never shown"
        (let [f (failure (nx/validate (nx/client (net/http) "WRONG-SECRET" {:base-url (:url s)})))]
          (is (= :unauthorized (:kind f)))
          (is (not (str/includes? (:msg f) "SECRET"))))
        (is (not (str/includes? (pr-str c) key*)))))))

(deftest signed-links
  (with-server [s]
    (let [st (fake-nexus! s) c (client s)]
      (swap! st assoc :valid #{1 2 3})
      (testing "8 concurrent callers mint one link"
        (let [ex (Executors/newVirtualThreadPerTaskExecutor)
              fs (vec (repeatedly 8 #(.submit ex ^java.util.concurrent.Callable (fn [] (nx/download-url c uid)))))]
          (is (apply = (map #(.get ^java.util.concurrent.Future %) fs)))
          (is (= 1 (:mints @st)))
          (is (not (str/includes? (pr-str (.get ^java.util.concurrent.Future (first fs))) "sig=")))))
      (testing "a link close to expiry is renewed"
        (let [c (client s)]
          (swap! st assoc :ttl-s 100)
          (nx/download-url c uid)
          (nx/download-url c uid)
          (is (= 3 (:mints @st)) "both inside the 5-minute margin")
          (swap! st assoc :ttl-s 3600)))
      (testing "a refused link is renewed once"
        (let [c (client s) archive (build-zip [(stored "a.txt" (.getBytes "hello"))])]
          (swap! st assoc-in [:archives uid] archive)
          (swap! st assoc :valid #{} :mints 0)
          (nx/download-url c uid)                       ; sig=1, now refused
          (swap! st assoc :valid #{2})
          (let [ar (nx/open-archive c uid)]
            (is (= 2 (:mints @st)))
            (is (= "hello" (String. (nx/read-entry ar (nx/find-entry ar "A.TXT")))))))))))

(deftest link-file-persists-links
  (with-server [s]
    (let [st (fake-nexus! s)
          f (java.io.File/createTempFile "nexus-links" ".json")]
      (.delete f)
      (let [c (client s {:link-file f})]
        (nx/download-url c uid)
        (nx/save-links! c))
      (is (.exists f))
      (let [c (client s {:link-file f})]
        (is (nx/has-url? c uid))
        (nx/download-url c uid)
        (is (= 1 (:mints @st)) "the saved link is reused")))))

;; --- archives --------------------------------------------------------------------------------

(def frame (* 64 1024))

(defn- fixture [st]
  (let [big (lcg (* 10 frame)) small (pattern 3000) mid (pattern (* 3 frame))
        z (build-zip [(stored "Readme.txt" small)
                      (nexus "Data/Big.bsa" big frame)
                      (stored "Data/" (byte-array 0))
                      (seekable "Data/Mid.bin" mid frame)])]
    (swap! st assoc-in [:archives uid] z)
    (swap! st assoc :valid #{1 2 3 4 5})
    {:big big :small small :mid mid :zip z}))

(deftest archive-reads
  (with-server [s]
    (let [st (fake-nexus! s) c (client s) {:keys [big small mid]} (fixture st)]
      (fake/clear-log! s)
      (let [ar (nx/open-archive c uid)]
        (testing "opening is one request"
          (is (= 1 (count (fake/requests s "/repacked/")))))
        (testing "a read fetches only the frames it covers: header and first run in one request"
          (fake/clear-log! s)
          (let [id (nx/find-entry ar "data/big.bsa")]
            (is (Arrays/equals (Arrays/copyOfRange big (int (+ 100 (* 2 frame))) (int (* 4 frame)))
                               (nx/read-range ar id (+ 100 (* 2 frame)) (* 4 frame))))
            (is (= 1 (count (fake/requests s "/repacked/"))))
            (fake/clear-log! s)
            (is (Arrays/equals (Arrays/copyOfRange big (int (* 7 frame)) (int (* 9 frame)))
                               (nx/read-range ar id (* 7 frame) (* 9 frame))))
            (is (= 1 (count (fake/requests s "/repacked/"))) "the data range is known now: just the frames")
            (is (Arrays/equals big (nx/read-entry ar id)))))
        (testing "stored entries, and an in-stream seek table"
          (is (Arrays/equals small (nx/read-entry ar (nx/find-entry ar "README.TXT"))))
          (is (Arrays/equals (Arrays/copyOfRange mid 5 (int (+ 5 frame))) (nx/read-range ar (nx/find-entry ar "data/mid.bin") 5 (+ 5 frame)))))
        (testing "a partial frame is decoded once"
          (fake/clear-log! s)
          (let [id (nx/find-entry ar "data/big.bsa")]
            (nx/read-range ar id 10 20)
            (nx/read-range ar id 30 40)
            (is (= 1 (count (fake/requests s "/repacked/"))))))))))

(deftest archive-changed
  (with-server [s]
    (let [st (fake-nexus! s) c (client s) _ (fixture st)
          ar (nx/open-archive c uid)]
      (swap! st update-in [:archives uid] #(byte-array (concat % [1 2 3])))
      (is (= :archive-changed (:kind (failure (nx/read-entry ar (nx/find-entry ar "data/big.bsa")))))))))

;; --- the provider ----------------------------------------------------------------------------------

(deftest archive-provider
  (with-server [s]
    (let [st (fake-nexus! s) c (client s) {:keys [big small]} (fixture st)
          pv (nx/provider (nx/open-archive c uid))]
      (is (.-dir? ^Stat (prov/stat pv "")))
      (is (= ["data" "readme.txt"] (map #(.toLowerCase ^String (.-name ^Entry %)) (prov/list-dir pv ""))))
      (is (= #{"Big.bsa" "Mid.bin"} (set (map #(.-name ^Entry %) (prov/list-dir pv "data")))))
      (is (= (alength ^bytes big) (.-size ^Stat (prov/stat pv "data/big.bsa"))))
      (is (= "Data/Big.bsa" (prov/spelling pv "data/big.bsa")))
      (is (= p/read-only (prov/open pv "data/big.bsa" p/open-write)))
      (is (= p/not-found (prov/open pv "nope" p/open-read)))
      (is (= p/not-a-directory (prov/list-dir pv "readme.txt")))
      (let [h (prov/open pv "DATA/BIG.BSA" p/open-read)
            buf (byte-array (* 3 frame))]
        (is (= (* 3 frame) (prov/read-into h (MemorySegment/ofArray buf) 0 (- (* 5 frame) 7) (* 3 frame))))
        (is (Arrays/equals (Arrays/copyOfRange ^bytes big (int (- (* 5 frame) 7)) (int (- (* 8 frame) 7))) buf))
        (is (= 7 (prov/read-into h (MemorySegment/ofArray buf) 0 (- (alength ^bytes big) 7) 100)) "short at EOF")
        (is (= 0 (prov/read-into h (MemorySegment/ofArray buf) 0 (alength ^bytes big) 100)))
        (prov/close! h)))))
