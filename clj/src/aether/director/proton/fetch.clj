(ns aether.director.proton.fetch
  "Downloading and installing a GE-Proton release (vfs-proton/src/install.rs
  install_release): the network half over proton.install's checks.

  The expected digest comes first: the caller's own (a host that pins a
  release records its sha512) or the release's .sha512sum. The tarball is then
  streamed to downloads/<tag>.tar.gz.partial, hashed as it lands, and only a
  matching one is extracted, gated on GE-Proton and renamed into
  runtimes/<tag>. A mismatch deletes the partial file."
  (:require [aether.director.proton.install :as install]
            [aether.director.proton.release :as release]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io InputStream OutputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$Builder HttpResponse$BodyHandlers)
           (java.security MessageDigest)
           (java.time Duration)
           (java.util HexFormat)))

(set! *warn-on-reflection* true)

(defn- fail [kind msg & [data]] (throw (ex-info msg (assoc data ::error kind))))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.connectTimeout (Duration/ofSeconds 30))
             (.build))))

(defn- get-stream
  "GET `url` as an InputStream; throws on anything but 200."
  ^InputStream [url headers]
  (let [^HttpRequest$Builder b (reduce-kv (fn [^HttpRequest$Builder b k v] (.header b k v))
                                          (.header (HttpRequest/newBuilder (URI/create url)) "User-Agent" release/user-agent)
                                          (or headers {}))
        req  (.build (.GET b))
        resp (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofInputStream))]
    (when-not (= 200 (.statusCode resp))
      (.close ^InputStream (.body resp))
      (fail :http (str "GET " url " answered HTTP " (.statusCode resp)) {:url url :status (.statusCode resp)}))
    (.body resp)))

(defn releases
  "The GE-Proton releases GitHub lists (release/parse-releases), newest first
  as GitHub orders them. `github-token` is optional."
  [& [github-token]]
  (let [{:keys [url headers]} (release/releases-request github-token)]
    (with-open [in (get-stream url headers)]
      (release/parse-releases (slurp in)))))

(defn latest
  "The newest release with an x86_64 tarball (release/pick)."
  [& [github-token]]
  (or (release/pick (releases github-token) nil)
      (fail :no-release "GitHub lists no GE-Proton release with an x86_64 tarball")))

(defn- download!
  "Streams `url` to `file`, returning its lowercase SHA-512; `progress` is
  called with the bytes written so far, at most every 8 MiB."
  [url file progress]
  (let [md  (MessageDigest/getInstance "SHA-512")
        buf (byte-array (* 256 1024))]
    (.mkdirs (.getParentFile (io/file file)))
    (with-open [^InputStream in (get-stream url nil)
                ^OutputStream out (io/output-stream file)]
      (loop [total 0 reported 0]
        (let [n (.read in buf)]
          (if (neg? n)
            (when progress (progress total))
            (let [total (+ total n)]
              (.update md buf 0 n)
              (.write out buf 0 n)
              (if (and progress (>= (- total reported) (* 8 1024 1024)))
                (do (progress total) (recur total total))
                (recur total reported)))))))
    (.formatHex (HexFormat/of) (.digest md))))

(defn install-release!
  "Installs `rel` ({:tag :tarball-url}, and :sha512 or :digest-url) under
  `home`, unless that tag is already installed and verified. Returns
  {:tag :dir :fresh}. Options: :progress (fn [bytes-so-far]), :pid."
  [home {:keys [tag tarball-url sha512 digest-url]} & [{:keys [progress pid]}]]
  (or (install/installed home tag)
      (let [expected (str/lower-case
                      (or sha512
                          (with-open [in (get-stream digest-url nil)] (install/parse-sha512sum (slurp in)))))
            partial  (install/partial-path home tag)]
        (try
          (let [actual (download! tarball-url partial progress)]
            (when-not (= expected actual)
              (fail :digest (str "sha512 mismatch for " tag ": expected " expected ", got " actual)
                    {:expected expected :actual actual}))
            (install/install-archive! home tag partial (cond-> {} pid (assoc :pid pid))))
          (finally (io/delete-file partial true))))))
