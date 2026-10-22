(ns aether.director.proton.release
  "GE-Proton releases from GitHub's API (vfs-proton/src/release.rs): parsing a
  response body and picking one. Pure; the request is data, and fetching it is
  the caller's. Errors are ex-info with ::error :json."
  (:require [aether.director.proton.runtime :as runtime]
            [charred.api :as json]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def releases-url
  "GloriousEggroll/proton-ge-custom's releases (RELEASES_URL, release.rs:47-48)."
  "https://api.github.com/repos/GloriousEggroll/proton-ge-custom/releases?per_page=30")

(def user-agent
  "Sent on every request: GitHub refuses one without (release.rs:142, install.rs:37)."
  "aether-vfs (vfs-proton)")

(defn releases-request
  "The releases query as {:url :headers}. A non-empty `github-token` (the
  ecosystem's GITHUB_TOKEN) is sent as a bearer token, and only here: the
  unauthenticated API allows 60 requests an hour per IP, and the tarball,
  served elsewhere, needs no credential (fetch_releases, release.rs:139-146)."
  [github-token]
  {:url     releases-url
   :headers (cond-> {"User-Agent" user-agent}
              (seq github-token) (assoc "Authorization" (str "Bearer " github-token)))})

(defn- fail [kind msg] (throw (ex-info msg {::error kind})))

(defn parse-releases
  "The releases in a releases API body that have an x86_64 asset pair, in the
  body's order: [{:tag :tarball-url :digest-url :size}]. For each element,
  tag_name and the assets whose names end -x86_64.tar.gz and
  -x86_64.sha512sum; a release missing either is skipped, never an error (GE
  has published incomplete and aarch64-only releases). An ex-info (::error
  :json) when the body isn't a JSON array (parse_releases, release.rs:59-103)."
  [body]
  (let [v (try (json/read-json body) (catch Exception e (fail :json (str "json error: " (ex-message e)))))]
    (when-not (sequential? v) (fail :json "json error: expected a top-level JSON array"))
    (vec (for [entry v
               :when (map? entry)
               :let [tag    (get entry "tag_name")
                     assets (get entry "assets")]
               :when (and (string? tag) (sequential? assets))
               :let [usable  (for [a assets
                                   :when (map? a)
                                   :let [n (get a "name") url (get a "browser_download_url")]
                                   :when (and (string? n) (string? url))]
                               [n url (get a "size")])
                     tarball (last (filter #(str/ends-with? (first %) "-x86_64.tar.gz") usable))
                     digest  (last (filter #(str/ends-with? (first %) "-x86_64.sha512sum") usable))]
               :when (and tarball digest)
               :let [size (nth tarball 2)]]
           {:tag         tag
            :tarball-url (second tarball)
            :digest-url  (second digest)
            :size        (if (and (integer? size) (not (neg? size))) (long size) 0)}))))

(defn tag-major
  "The N of a GE-ProtonN-M tag, or nil (tag_major, release.rs:118-122)."
  [tag]
  (when-let [[_ n] (re-matches #"GE-Proton(\d+)-.*" (str tag))] (parse-long n)))

(defn pick
  "The newest of `releases` by runtime/cmp-tags, within major series `major`
  when given; nil when none (pick, release.rs:107-115)."
  [releases major]
  (let [rs (cond->> releases major (filter #(= major (tag-major (:tag %)))))]
    (when (seq rs)
      (reduce (fn [a b] (if (pos? (runtime/cmp-tags (:tag a) (:tag b))) a b)) rs))))
