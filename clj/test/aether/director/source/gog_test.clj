(ns aether.director.source.gog-test
  "aether-gog's tests, against a fake GOG (auth, content system, CDN)."
  (:require [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [aether.director.source.archive-test :refer [lcg pattern]]
            [aether.director.source.fake-server :as fake :refer [with-server]]
            [aether.director.source.gog :as gog]
            [aether.director.source.net :as net]
            [charred.api :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director.provider Entry Stat)
           (java.lang.foreign MemorySegment)
           (java.nio.file Files)
           (java.security MessageDigest)
           (java.util Arrays HexFormat)
           (java.util.zip Deflater)))

(defn- tmp [] (.toFile (Files/createTempDirectory "aether-gog" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- md5 [^bytes b] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "MD5") b)))
(defn- zlib ^bytes [^bytes b] (let [d (Deflater.) out (byte-array (+ 64 (* 2 (alength b))))]
                                (.setInput d b) (.finish d)
                                (let [n (.deflate d out)] (.end d) (Arrays/copyOf out n))))

(defn- chunks
  "Chunk records for `data` in `size` pieces; the compressed bytes go into `store`."
  [store ^bytes data size]
  (vec (for [a (range 0 (alength data) size)]
         (let [piece (Arrays/copyOfRange data (int a) (int (min (alength data) (+ a size))))
               z (zlib piece)]
           (swap! store assoc (md5 z) z)
           {"md5" (md5 piece) "size" (alength piece) "compressedMd5" (md5 z) "compressedSize" (alength z)}))))

(def big (lcg 300000))
(def readme (.getBytes "read me"))
(def small (.getBytes "small=1"))
(def dlc (pattern 5000))

(defn- fake-gog! [s]
  (let [st (atom {:store {} :tokens 0 :refreshes 0 :cdn-gets 0 :links 0 :corrupt #{}})
        store (atom {})
        container-data (byte-array (concat (.getBytes "header-") small (.getBytes "-trailer")))
        base-manifest {"depot" {"items" [{"type" "DepotFile" "path" "Data\\Big.bin" "chunks" (chunks store big 100000)}
                                         {"type" "DepotFile" "path" "Readme.TXT" "chunks" (chunks store readme 1000)}
                                         {"type" "DepotLink" "path" "link" "target" "Readme.TXT"}
                                         {"type" "DepotFile" "path" "Data\\Small.ini" "chunks" []
                                          "sfcRef" {"offset" 7 "size" (alength small)}}]
                                "smallFilesContainer" {"chunks" (chunks store container-data 1000)}}}
        dlc-manifest {"depot" {"items" [{"type" "DepotFile" "path" "Data\\DLC.esp" "chunks" (chunks store dlc 2000)}
                                        {"type" "DepotFile" "path" "data\\big.bin" "chunks" (chunks store (byte-array 10) 10)}]}}
        bearer? (fn [req] (re-matches #"Bearer access-\d+" (str (get-in req [:headers "authorization"]))))]
    (swap! st assoc :store @store)
    (fake/route! s #"/auth/token"
                 (fn [{:keys [query]}]
                   (let [q (into {} (map #(str/split % #"=" 2)) (str/split (str query) #"&"))]
                     (cond
                       (or (not= gog/client-id (q "client_id")) (not= gog/client-secret (q "client_secret"))) [401 {} "invalid_client"]
                       (and (= "authorization_code" (q "grant_type")) (= "good-code" (q "code")))
                       [200 {} (json/write-json-str {"access_token" (str "access-" (:tokens (swap! st update :tokens inc)))
                                                     "refresh_token" "refresh-1" "expires_in" 3600 "user_id" "u1"})]
                       (and (= "refresh_token" (q "grant_type")) (str/starts-with? (str (q "refresh_token")) "refresh-") (not (:refuse-refresh @st)))
                       (do (swap! st update :refreshes inc)
                           [200 {} (json/write-json-str {"access_token" (str "access-" (:tokens (swap! st update :tokens inc)))
                                                         "refresh_token" "refresh-2" "expires_in" "3600" "user_id" 1})])
                       :else [400 {} "invalid_grant"]))))
    (fake/route! s #"/cs/products/(\d+)/os/windows/builds"
                 (fn [req] (if (bearer? req)
                             [200 {} (json/write-json-str {"items" [{"build_id" 123 "product_id" 1 "os" "windows" "generation" 2
                                                                    "link" (str (:url s) "/meta/build")}]})]
                             [401 {} ""])))
    (fake/route! s #"/meta/build" (fn [_] [200 {} (zlib (.getBytes ^String (json/write-json-str {"baseProductId" "1" "depots" [{"productId" 1 "manifest" "AB12CD"} {"productId" "2" "manifest" "ef34ab"}]})))]))
    (fake/route! s #"/cdn/content-system/v2/meta/ab/12/ab12cd" (fn [_] [200 {} (zlib (.getBytes ^String (json/write-json-str base-manifest)))]))
    (fake/route! s #"/cdn/content-system/v2/meta/ef/34/ef34ab" (fn [_] [200 {} (zlib (.getBytes ^String (json/write-json-str dlc-manifest)))]))
    (fake/route! s #"/cs/products/(\d+)/secure_link"
                 (fn [{:keys [path query] :as req}]
                   (cond
                     (not (bearer? req)) [401 {} ""]
                     (= "/cs/products/9/secure_link" path) [403 {} "not owned"]
                     :else
                     (let [n (:links (swap! st update :links inc))
                           product (second (re-matches #"/cs/products/(\d+)/secure_link" path))]
                       [200 {} (json/write-json-str
                                {"urls" [{"url_format" "{base_url}/token=nva={expires_at}~dirs=2~token={token}{path}" "priority" 1
                                          "parameters" {"base_url" (str (:url s) "/cdn") "path" (str "/content-system/v2/store/" product)
                                                        "token" (str "SeCrEt-cdn-token-" n) "expires_at" (+ 3600 (quot (System/currentTimeMillis) 1000))}}]})]))))
    (fake/route! s #"/cdn/token=.*"
                 (fn [{:keys [path]}]
                   (swap! st update :cdn-gets inc)
                   (let [[_ token md] (re-matches #".*~token=(SeCrEt-cdn-token-\d+)/content-system/v2/store/\d+/../../([0-9a-f]+)" path)]
                     (cond
                       (nil? md) [403 {} "bad link"]
                       (contains? (:corrupt @st) md) [200 {} (byte-array [1 2 3])]
                       :else [200 {} (get (:store @st) md)]))))
    st))

(defn- setup [s]
  (let [dir (tmp)
        cfg (gog/config dir (java.io.File. dir "creds.json")
                        {:auth-base (str (:url s) "/auth") :content-base (str (:url s) "/cs") :cdn-meta-base (str (:url s) "/cdn")})
        h (net/http {:retry {:max-attempts 3 :base-delay-ms 1 :max-delay-ms 10 :max-retry-after-ms 2000}})]
    {:cfg cfg :h h}))

(defmacro ^:private failure [form]
  `(try ~form nil (catch clojure.lang.ExceptionInfo e# {:kind (net/kind e#) :msg (ex-message e#)})))

(deftest login
  (is (= "abc" (gog/code-from-paste "https://embed.gog.com/on_login_success?origin=client&code=abc")))
  (is (= "abc" (gog/code-from-paste "  abc ")))
  (is (= :login (:kind (failure (gog/code-from-paste "a b")))))
  (is (= :login (:kind (failure (gog/code-from-paste "https://embed.gog.com/x?origin=client")))))
  (with-server [s]
    (let [st (fake-gog! s) {:keys [cfg h]} (setup s)]
      (is (= :not-logged-in (:kind (failure (gog/content h cfg)))))
      (is (= :login (:kind (failure (gog/complete-login! h cfg "bad-code")))))
      (gog/complete-login! h cfg "https://embed.gog.com/on_login_success?origin=client&code=good-code")
      (is (= "access-1" (:access-token (gog/load-credentials (:credentials cfg)))))
      (testing "an expired token is refreshed once before the call"
        (let [f (:credentials cfg)]
          (spit f (str/replace (slurp f) #"\"expires_at\"\s*:\s*\d+" "\"expires_at\": 1"))
          (let [c (gog/content h cfg)]
            (is (= 1 (count (gog/builds c 1 :windows))))
            (is (= 1 (:refreshes @st))))))
      (testing "a refused refresh says to log in again"
        (let [f (:credentials cfg)]
          (spit f (str/replace (slurp f) #"\"expires_at\"\s*:\s*\d+" "\"expires_at\": 1"))
          (swap! st assoc :refuse-refresh true)
          (is (= :login-expired (:kind (failure (gog/builds (gog/content h cfg) 1 :windows))))))))))

(defn- logged-in [s]
  (let [st (fake-gog! s) {:keys [cfg h]} (setup s)]
    (gog/complete-login! h cfg "good-code")
    [st cfg (gog/content h cfg)]))

(deftest content-and-reads
  (with-server [s]
    (let [[st cfg c] (logged-in s)
          [b] (gog/builds c 1 :windows)
          d (gog/build-details c b)]
      (is (= [{:product-id 1 :manifest "ab12cd" :size 0 :languages []} {:product-id 2 :manifest "ef34ab" :size 0 :languages []}] (:depots d)))
      (let [m (gog/depot-manifest c "AB12CD")]
        (is (= ["Data\\Big.bin" "Readme.TXT" "Data\\Small.ini"] (map :path (:files m))) "links dropped")
        (is (.exists (java.io.File. (:cache-dir cfg) "manifests/ab12cd")) "cached on disk"))
      (let [file (first (:files (gog/depot-manifest c "ab12cd")))
            read (fn [pos n] (let [b (byte-array n) k (gog/read-file! c 1 file pos n (MemorySegment/ofArray b) 0)] (Arrays/copyOf b (int k))))]
        (testing "a read across chunks fetches just those, then serves from the cache"
          (swap! st assoc :cdn-gets 0)
          (is (Arrays/equals (Arrays/copyOfRange ^bytes big 99000 101000) (read 99000 2000)))
          (is (= 2 (:cdn-gets @st)))
          (read 99500 100)
          (is (= 2 (:cdn-gets @st))))
        (is (= 0 (count (read 300000 10))) "at EOF")
        (is (= 10 (count (read 299990 100))) "short at EOF"))
      (testing "a file in the small-files container"
        (let [file (nth (:files (gog/depot-manifest c "ab12cd")) 2) b (byte-array 7)]
          (gog/read-file! c 1 file 0 7 (MemorySegment/ofArray b) 0)
          (is (= "small=1" (String. b)))))
      (testing "a corrupt chunk is never served or cached, and the token never shown"
        (let [file (second (:files (gog/depot-manifest c "ab12cd")))
              cm (get-in (first (:chunks file)) [:cmd5])]
          (swap! st update :corrupt conj cm)
          (swap! st assoc :cdn-gets 0)
          (let [f (failure (gog/read-file! c 1 file 0 7 (MemorySegment/ofArray (byte-array 7)) 0))]
            (is (= :corrupt-part (:kind f)))
            (is (not (str/includes? (str (:msg f)) "SeCrEt"))))
          (is (= 3 (:cdn-gets @st)))
          (swap! st update :corrupt disj cm)
          (let [b (byte-array 7)] (gog/read-file! c 1 file 0 7 (MemorySegment/ofArray b) 0) (is (= "read me" (String. b))))))
      (testing "an unowned product"
        (is (= :not-owned (:kind (failure (gog/read-file! c 9 (first (:files (gog/depot-manifest c "ef34ab"))) 0 7 (MemorySegment/ofArray (byte-array 7)) 0))))
            "(a chunk not yet cached: a cached one is served without a link)")))))

(deftest concurrent-reads-of-a-chunk-fetch-it-once
  (with-server [s]
    (let [[st _ c] (logged-in s)
          file (first (:files (gog/depot-manifest c "ab12cd")))]
      (swap! st assoc :cdn-gets 0)
      (let [fs (doall (for [_ (range 8)] (future (let [b (byte-array 100)] (gog/read-file! c 1 file 0 100 (MemorySegment/ofArray b) 0) b))))]
        (is (every? #(Arrays/equals (Arrays/copyOf ^bytes big 100) ^bytes @%) fs))
        (is (= 1 (:cdn-gets @st)))))))

(deftest depot-provider
  (with-server [s]
    (let [[_ _ c] (logged-in s)
          pv (gog/provider c [[1 "ab12cd"] [2 "ef34ab"]])]
      (is (= ["Data" "Readme.TXT"] (map #(.-name ^Entry %) (prov/list-dir pv ""))) "manifest order")
      (is (= ["Big.bin" "Small.ini" "DLC.esp"] (map #(.-name ^Entry %) (prov/list-dir pv "data"))))
      (is (= 300000 (.-size ^Stat (prov/stat pv "data/big.bin"))) "the earlier depot wins")
      (is (= "Data/DLC.esp" (prov/spelling pv "data/dlc.esp")))
      (is (= p/read-only (prov/open pv "readme.txt" p/open-write)))
      (let [h (prov/open pv "DATA/BIG.BIN" p/open-read) buf (byte-array 150000)]
        (is (= 150000 (prov/read-into h (MemorySegment/ofArray buf) 0 50000 150000)))
        (is (Arrays/equals (Arrays/copyOfRange ^bytes big 50000 200000) buf))
        (prov/close! h)))))
