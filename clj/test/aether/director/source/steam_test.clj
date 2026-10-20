(ns aether.director.source.steam-test
  "aether-steam's offline tests: the codecs, the CDN pool and readers
  against fake CDN hosts, content caching with a fake session, the provider."
  (:require [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [aether.director.source.archive-test :refer [build-zip lcg pattern]]
            [aether.director.source.fake-server :as fake :refer [with-server]]
            [aether.director.source.net :as net]
            [aether.director.source.steam :as steam]
            [aether.director.source.steam.cm :as cm]
            [aether.director.source.steam.codec :as codec]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (aether.director.provider Entry Stat)
           (java.io ByteArrayOutputStream)
           (java.lang.foreign MemorySegment)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.util Arrays Base64)
           (java.util.zip Deflater)))

(def key* (byte-array 32 (byte 0x42)))
(defn- tmp [] (.toFile (Files/createTempDirectory "aether-steam" (make-array java.nio.file.attribute.FileAttribute 0))))

;; --- fixtures ------------------------------------------------------------------------------------

(defn- varint [^ByteArrayOutputStream o ^long v]
  (loop [v v] (if (< v 0x80) (.write o (int v)) (do (.write o (int (bit-or 0x80 (bit-and v 0x7f)))) (recur (unsigned-bit-shift-right v 7))))))

(defn- pb
  "A protobuf message from [[field type value] …]: :varint, :fixed32, :bytes, :string, :msg."
  ^bytes [fields]
  (let [o (ByteArrayOutputStream.)]
    (doseq [[f t v] fields :when (some? v)]
      (case t
        :varint (do (varint o (bit-shift-left f 3)) (varint o (long v)))
        :fixed32 (do (varint o (bit-or (bit-shift-left f 3) 5))
                     (.write o (.array (.putInt (.order (ByteBuffer/allocate 4) ByteOrder/LITTLE_ENDIAN) (unchecked-int v)))))
        (:bytes :string :msg) (let [^bytes b (case t :string (.getBytes ^String v StandardCharsets/UTF_8) v)]
                                (varint o (bit-or (bit-shift-left f 3) 2)) (varint o (alength b)) (.write o b))))
    (.toByteArray o)))

(defn- encrypt-name [^String n]
  (.encodeToString (Base64/getEncoder) (codec/encrypt key* (byte-array 16 (byte 0x29)) (.getBytes (str n "\u0000") StandardCharsets/UTF_8))))

(defn- section ^bytes [magic ^bytes body]
  (let [bb (.order (ByteBuffer/allocate (+ 8 (alength body))) ByteOrder/LITTLE_ENDIAN)]
    (.putInt bb (unchecked-int magic)) (.putInt bb (alength body)) (.put bb body) (.array bb)))

(defn- deflate ^bytes [^bytes b] (let [d (Deflater. 6 true) out (byte-array (+ 64 (* 2 (alength b))))]
                                   (.setInput d b) (.finish d) (let [n (.deflate d out)] (.end d) (Arrays/copyOf out n))))

(defn manifest-body
  "A CDN manifest body: files {name [chunk-meta …]} (chunks stored reversed),
  dirs, names encrypted; in a one-entry zip. Options: :no-metadata."
  [depot gid files dirs & [{:keys [no-metadata]}]]
  (let [mapping (fn [name size flags chunks]
                  [1 :msg (pb [[1 :string (encrypt-name name)] [2 :varint size] [3 :varint flags]
                               [5 :bytes (byte-array 20 (byte 1))]
                               ;; reversed: Steam doesn't promise chunk order
                               ])])
        payload (pb (concat (for [[name chunks] files]
                              [1 :msg (pb (concat [[1 :string (encrypt-name name)] [2 :varint (reduce + (map :len chunks))] [3 :varint 0]
                                                   [5 :bytes (byte-array 20 (byte 1))]]
                                                  (for [{:keys [id offset len adler]} (reverse chunks)]
                                                    [6 :msg (pb [[1 :bytes id] [2 :fixed32 adler] [3 :varint offset] [4 :varint len] [5 :varint len]])])))])
                            (for [d dirs] [1 :msg (pb [[1 :string (encrypt-name d)] [2 :varint 0] [3 :varint 0x40]])])))
        meta (pb [[1 :varint depot] [2 :varint gid] [4 :varint 1]])
        raw (byte-array (concat (section 0x71F617D0 payload)
                                (when-not no-metadata (section 0x1F4812BE meta))
                                (.array (.putInt (.order (ByteBuffer/allocate 4) ByteOrder/LITTLE_ENDIAN) (unchecked-int 0x32C415AB)))))
        z (deflate raw)]
    (build-zip [{:name "z" :method 8 :data z :usize (alength raw)}])))

(defn- split-file
  "Chunks of `data` in `size` pieces: [[raw-chunk meta] …] with offsets."
  [^bytes data size]
  (vec (for [a (range 0 (alength data) size)]
         (let [[raw m] (codec/encode-chunk key* (Arrays/copyOfRange data (int a) (int (min (alength data) (+ a size)))))]
           [raw (assoc m :offset a)]))))

(defmacro ^:private failure [form]
  `(try ~form nil (catch clojure.lang.ExceptionInfo e# {:kind (net/kind e#) :msg (ex-message e#)})))

;; --- codecs ------------------------------------------------------------------------------------------

(deftest checksums-and-names
  (is (= (bit-or (bit-shift-left 0x24A 16) 0x126) (codec/adler32 (.getBytes "abc"))) "Adler-32 seeded with 0: b = 97 + 195 + 294")
  (is (= 0 (codec/adler32 (byte-array 0))))
  (let [big (lcg 100000)
        ref (let [a (java.util.zip.Adler32.)] (.update a big) (.getValue a))]
    (is (not= ref (codec/adler32 big)) "differs from java.util.zip's (seed 1)"))
  (is (= "Data/Skyrim.esm" (codec/decrypt-name key* (encrypt-name "Data/Skyrim.esm"))))
  (is (= :integrity (:kind (failure (codec/decrypt-name (byte-array 32) (encrypt-name "x")))))))

(deftest chunks
  (let [data (lcg 5000)
        [[raw m]] (split-file data 10000)]
    (is (Arrays/equals data (codec/decode-chunk key* m raw)))
    (testing "a wrong key, Adler, id, length, or truncation"
      (is (= :integrity (:kind (failure (codec/decode-chunk (byte-array 32) m raw)))))
      (is (= :integrity (:kind (failure (codec/decode-chunk key* (update m :adler inc) raw)))))
      (is (= :integrity (:kind (failure (codec/decode-chunk key* (assoc m :id (byte-array 20)) raw)))))
      (is (= :integrity (:kind (failure (codec/decode-chunk key* (update m :len inc) raw)))))
      (is (= :integrity (:kind (failure (codec/decode-chunk key* m (Arrays/copyOf ^bytes raw 20)))))))
    (testing "a chunk declared over the cap"
      (is (= :integrity (:kind (failure (codec/decode-chunk key* (assoc m :len (inc codec/max-chunk)) raw))))))))

(deftest manifests
  (let [a (split-file (lcg 2500) 1000) b (split-file (pattern 300) 1000)
        body (manifest-body 481 7 {"Data\\A.bin" (map second a) "B.TXT" (map second b)} ["Data"])
        m (codec/parse-manifest body 481 7 key*)]
    (is (= ["Data\\A.bin" "B.TXT"] (map :path (:files m))) "the directory is skipped")
    (is (= [[0 1000] [1000 1000] [2000 500]] (map (juxt :offset :len) (:chunks (first (:files m))))) "sorted by offset")
    (is (= [1 3] (codec/chunks-for (first (:files m)) 1500 2000)))
    (is (= [0 0] (codec/chunks-for (first (:files m)) 2500 10)))
    (testing "refusals"
      (is (= :integrity (:kind (failure (codec/parse-manifest body 482 7 key*)))) "another depot")
      (is (= :integrity (:kind (failure (codec/parse-manifest body 481 8 key*)))) "another manifest")
      (is (= :integrity (:kind (failure (codec/parse-manifest (manifest-body 481 7 {"x" (map second a)} [] {:no-metadata true}) 481 7 key*)))))
      (is (str/includes? (:msg (failure (codec/parse-manifest body 481 7 (byte-array 32)))) "file names:"))
      (is (= :integrity (:kind (failure (codec/parse-manifest (manifest-body 481 7 {"x" (map second (rest a))} []) 481 7 key*)))) "a gap")
      (is (= :integrity (:kind (failure (codec/parse-manifest (manifest-body 481 7 {"x" (map second a) "X" (map second b)} []) 481 7 key*)))) "a duplicate"))))

(deftest server-selection
  (is (= [{:host "a" :port 443 :https true} {:host "b" :port 80 :https false} {:host "isp-cache" :port 80 :https false} {:host "lists-our-app" :port 80 :https false}]
         (cm/select-servers [{:type "CDN" :host "b:80" :weighted-load 2}
                             {:type "CDN" :host "x" :vhost "a" :https true :weighted-load 1}
                             {:type "SteamCache" :host "isp-cache" :weighted-load 3}
                             {:type "CDN" :host "china" :china-only true :weighted-load 0}
                             {:type "CDN" :host "proxy" :proxy true :weighted-load 0}
                             {:type "CS" :host "cs" :weighted-load 0}
                             {:type "CDN" :host "other-app" :allowed [7] :weighted-load 0}
                             {:type "CDN" :host "lists-our-app" :allowed [480 481] :weighted-load 4}]
                            480))))

;; --- the CDN and readers -----------------------------------------------------------------------------

(defn- cdn!
  "Serves the chunks (and manifests) on `s`: mode (atom) :ok, :status n, :corrupt, :token."
  [s chunks manifests mode]
  (fake/route! s #"/depot/\d+/chunk/[0-9a-f]+"
               (fn [{:keys [path query]}]
                 (let [id (last (str/split path #"/"))
                       raw (get chunks id)]
                   (case (first @mode)
                     :status [(second @mode) {} "no"]
                     :corrupt [200 {} (doto (aclone ^bytes raw) (aset-byte (dec (alength ^bytes raw)) (unchecked-byte 0)))]
                     :token (if (= query "token=t") [200 {} raw] [403 {} "no token"])
                     [200 {} raw]))))
  (fake/route! s #"/depot/\d+/manifest/\d+/5(/\d+)?"
               (fn [{:keys [path]}] (if-let [b (get manifests path)] [200 {} b] [404 {} ""]))))

(defn- server [s] (let [u (java.net.URI. (:url s))] {:host (.getHost u) :port (.getPort u) :https false}))
(def ^:private fast {:max-attempts 4 :cooldown-base-ms 10 :cooldown-max-ms 50 :probe-timeout-ms 200})

(deftest reads-fail-over-and-share-fetches
  (let [data (lcg 10000) parts (split-file data 1000)
        chunks (into {} (for [[raw m] parts] [(codec/hex (:id m)) raw]))
        file {:path "f" :size 10000 :chunks (mapv second parts)}]
    (with-server [s1]
      (with-server [s2]
        (let [m1 (atom [:ok]) m2 (atom [:ok])]
          (cdn! s1 chunks {} m1) (cdn! s2 chunks {} m2)
          (let [h (net/http)
                rd (steam/reader h (steam/pool [(server s1) (server s2)] fast) 480 481 key*)
                read (fn [rd pos n] (let [b (byte-array n)] (steam/read-file! rd file pos n (MemorySegment/ofArray b) 0) b))]
            (testing "a range read fetches only its chunks; a second read is cached"
              (fake/clear-log! s1) (fake/clear-log! s2)
              (is (Arrays/equals (Arrays/copyOfRange data 1500 3500) (read rd 1500 2000)))
              (is (= 3 (+ (count (fake/requests s1 "/depot")) (count (fake/requests s2 "/depot")))))
              (read rd 1600 100)
              (is (= 3 (+ (count (fake/requests s1 "/depot")) (count (fake/requests s2 "/depot"))))))
            (doseq [mode [[:status 503] [:corrupt]]]
              (testing (str "fails over on " mode)
                (let [rd (steam/reader h (steam/pool [(server s1) (server s2)] fast) 480 481 key*)]
                  (reset! m1 mode)
                  (is (Arrays/equals (Arrays/copyOfRange data 0 3000) (read rd 0 3000)))
                  (reset! m1 [:ok]))))
            (testing "gives up after max attempts, without a URL in the error"
              (reset! m1 [:status 500]) (reset! m2 [:status 500])
              (let [rd (steam/reader h (steam/pool [(server s1) (server s2)] fast) 480 481 key*)
                    f (failure (read rd 0 10))]
                (is (= :cdn (:kind f)))
                (is (str/includes? (:msg f) "HTTP 500"))
                (is (not (str/includes? (:msg f) "http://"))))
              (reset! m1 [:ok]) (reset! m2 [:ok]))
            (testing "a 403 asks for a CDN token"
              (reset! m1 [:token]) (reset! m2 [:token])
              (let [asked (atom 0)
                    rd (steam/reader h (steam/pool [(server s1)] fast) 480 481 key* (fn [_] (swap! asked inc) "token=t"))]
                (is (Arrays/equals (Arrays/copyOf data 100) (read rd 0 100)))
                (is (= 1 @asked)))
              (reset! m1 [:ok]) (reset! m2 [:ok]))
            (testing "concurrent readers of one chunk fetch it once"
              (fake/clear-log! s1) (fake/clear-log! s2)
              (let [rd (steam/reader h (steam/pool [(server s1) (server s2)] fast) 480 481 key*)
                    fs (doall (repeatedly 8 #(future (read rd 9000 1000))))]
                (is (every? #(Arrays/equals (Arrays/copyOfRange data 9000 10000) ^bytes @%) fs))
                (is (= 1 (+ (count (fake/requests s1 "/depot")) (count (fake/requests s2 "/depot")))))))))))))

;; --- content and the provider ------------------------------------------------------------------------------

(defn- fake-session
  "A stand-in for cm sessions: counts its calls."
  [calls srv]
  (fn [_login]
    {:fake true}))

(deftest content-caches-and-provider
  (let [a-data (lcg 3000) b-data (pattern 700) shared (.getBytes "first")
        a (split-file a-data 1000) b (split-file b-data 1000) sh (split-file shared 1000) sh2 (split-file (.getBytes "second!") 1000)
        chunks (into {} (for [[raw m] (concat a b sh sh2)] [(codec/hex (:id m)) raw]))
        m481 (manifest-body 481 7 {"Data\\A.bin" (map second a) "shared.txt" (map second sh)} [])
        m482 (manifest-body 482 9 {"B.TXT" (map second b) "Shared.TXT" (map second sh2)} [])]
    (with-server [s]
      (cdn! s chunks {"/depot/481/manifest/7/5/77" m481 "/depot/482/manifest/9/5/77" m482} (atom [:ok]))
      (let [calls (atom [])
            dir (tmp)]
        (with-redefs [cm/depot-key (fn [_ app depot] (swap! calls conj [:key depot]) key*)
                      cm/manifest-request-code (fn [_ app depot gid] (swap! calls conj [:code depot]) 77)
                      cm/cdn-servers (fn [_ app] (swap! calls conj [:servers]) [(server s)])
                      cm/cdn-token (fn [& _] nil)]
          (testing "a cold start asks the session once per thing, then the cache needs no session"
            (let [c (steam/content (net/http) dir nil {:session-fn (fn [_] {:fake true}) :cdn fast})
                  pv (steam/provider c 480 [[481 7] [482 9]])]
              (is (= #{[:key 481] [:key 482] [:code 481] [:code 482] [:servers]} (set @calls)))
              (is (= ["Data" "shared.txt" "B.TXT"] (map #(.-name ^Entry %) (prov/list-dir pv ""))) "manifest order")
              (is (= 5 (.-size ^Stat (prov/stat pv "shared.txt"))) "the earlier depot wins")
              (is (= "Data/A.bin" (prov/spelling pv "data/a.bin")))
              (is (= p/read-only (prov/open pv "b.txt" p/open-write)))
              (let [h (prov/open pv "DATA/A.BIN" p/open-read) buf (byte-array 3000)]
                (is (= 2999 (prov/read-into h (MemorySegment/ofArray buf) 0 1 3000)) "short at EOF")
                (is (Arrays/equals (Arrays/copyOfRange a-data 1 3000) (Arrays/copyOf buf 2999))))))
          (reset! calls [])
          (testing "a fresh instance reads manifests from the cache"
            (let [c (steam/content (net/http) dir nil {:session-fn (fn [_] (throw (ex-info "no session" {}))) :cdn fast})
                  pv (steam/provider c 480 [[481 7] [482 9]])]
              (is (empty? @calls))
              (is (= 700 (.-size ^Stat (prov/stat pv "b.txt")))))))))))

(deftest logins
  (let [f (java.io.File. (tmp) "login.json")
        jwt (fn [exp] (str "h." (.encodeToString (Base64/getUrlEncoder) (.getBytes (str "{\"exp\":" exp "}"))) ".s"))]
    (steam/save-login! f {:account "me" :refresh-token (jwt 4102444800) :guard-data "g"})
    (is (= {:account "me" :refresh-token (jwt 4102444800) :guard-data "g"} (steam/load-login f)))
    (is (= 4102444800 (steam/token-expires-at (jwt 4102444800))))
    (is (nil? (steam/token-expires-at "opaque")))
    (testing "an expired login is reported without touching the network"
      (steam/save-login! f {:account "me" :refresh-token (jwt 1)})
      (is (= :login-expired (:kind (failure (steam/content (net/http) (tmp) f))))))
    (testing "a damaged file says how to fix it"
      (spit f "nope")
      (is (str/includes? (:msg (failure (steam/load-login f))) "log in to Steam again")))))
