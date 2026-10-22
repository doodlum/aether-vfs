(ns aether.director.proton.install-test
  "vfs-proton/src/install.rs's tests: the sha512sum and digest checks, the tar
  extractor and its traversal guards, the GE gate and the rename, over
  tarballs these tests build byte by byte."
  (:require [aether.director.proton.install :as install]
            [aether.director.proton.layout :as layout]
            [aether.director.test-support :as ts]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream File)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute)
           (java.util.zip GZIPOutputStream)))

(defn- temp-dir ^String []
  (str (Files/createTempDirectory "aether-proton-inst" (make-array FileAttribute 0))))

(defn- error-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

;; --- a tar writer, for the tests ------------------------------------------------------

(defn- octal! [^bytes h off len n]
  (let [s (format (str "%0" (dec len) "o") (long n))]
    (System/arraycopy (utf8 s) 0 h off (dec len))))

(defn- header
  "One 512-byte header. `fmt` :gnu (magic \"ustar  \\0\") or :ustar
  (\"ustar\\0\" \"00\", with a prefix field)."
  ^bytes [{:keys [name type size link mode fmt prefix cksum size-bytes]
           :or {type \0 size 0 mode 0644 fmt :gnu}}]
  (let [h (byte-array 512)
        put (fn [off ^bytes b] (System/arraycopy b 0 h off (alength b)))]
    (put 0 (utf8 name))
    (octal! h 100 8 mode)
    (octal! h 108 8 0)
    (octal! h 116 8 0)
    (if size-bytes (put 124 size-bytes) (octal! h 124 12 size))
    (octal! h 136 12 0)
    (aset-byte h 156 (byte (int type)))
    (when link (put 157 (utf8 link)))
    (case fmt
      :gnu   (put 257 (utf8 "ustar  \u0000"))
      :ustar (do (put 257 (utf8 "ustar\u0000")) (put 263 (utf8 "00"))))
    (when prefix (put 345 (utf8 prefix)))
    (java.util.Arrays/fill h 148 156 (byte 32))
    (let [sum (reduce + (map #(bit-and 0xff %) h))]
      (put 148 (utf8 (format "%06o\u0000 " (long (or cksum sum))))))
    h))

(defn- member
  "A header and its padded data. `entry` as header's, plus :data (a string or
  bytes)."
  [{:keys [data] :as entry}]
  (let [d   (cond (string? data) (utf8 data) data data :else (byte-array 0))
        out (ByteArrayOutputStream.)]
    (.write out (header (assoc entry :size (or (:size entry) (alength ^bytes d)))))
    (.write out ^bytes d)
    (.write out (byte-array (mod (- (alength ^bytes d)) 512)))
    (.toByteArray out)))

(defn- tar ^bytes [& entries]
  (let [out (ByteArrayOutputStream.)]
    (doseq [e entries] (.write out ^bytes (member e)))
    (.write out (byte-array 1024))
    (.toByteArray out)))

(defn- gz ^bytes [^bytes b]
  (let [out (ByteArrayOutputStream.)]
    (with-open [z (GZIPOutputStream. out)] (.write z b))
    (.toByteArray out)))

(defn- tar-gz-file [dir name & entries]
  (let [f (io/file dir name)]
    (io/copy (gz (apply tar entries)) f)
    (str f)))

(defn- extract [archive into]
  (with-open [in (io/input-stream archive)] (install/extract! (install/gunzip in) into)))

(def ^:private ge-version {:name "GE-Proton11-6-x86_64/version" :data "1787951532 GE-Proton11-6\n"})

;; --- sha512sum and digest ---------------------------------------------------------------

(deftest parses-a-publisher-sha512sum-line
  ;; exactly the format GE ships: "<128 hex>  <filename>"
  (let [line (str "543e3af57bb138b1be5a5b98bba4d39ca59340bfa34ec8c12144f3e16d7434ed"
                  "75bd7a68eafc228b16695884629595af0905156e5227c1898f93cdbc92cb5fcb  GE-Proton11-6-x86_64.tar.gz")]
    (is (= 128 (count (install/parse-sha512sum line))))
    (is (str/starts-with? (install/parse-sha512sum line) "543e3af5"))
    (is (= (install/parse-sha512sum line) (install/parse-sha512sum (str "\n  \n" (str/upper-case line) "\n")))
        "the first non-blank line, lower-cased")))

(deftest rejects-a-sha512sum-that-is-not-128-hex-chars
  ;; a truncated body or an HTML error page must not become an expected digest
  (doseq [bad ["" "deadbeef  f.tar.gz" "<html>404</html>" "zzzz  f" (apply str (repeat 128 "g"))]]
    (is (= :bad-sha512-line (::install/error (error-of #(install/parse-sha512sum bad)))) (pr-str bad))))

(def ^:private abc-sha512
  (str "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
       "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"))

(deftest verify-digest-accepts-the-true-hash-and-rejects-a-wrong-one
  (is (= abc-sha512 (install/sha512-hex (ByteArrayInputStream. (utf8 "abc")))))
  (is (nil? (install/verify-digest (ByteArrayInputStream. (utf8 "abc")) abc-sha512)))
  (is (nil? (install/verify-digest (ByteArrayInputStream. (utf8 "abc")) (str/upper-case abc-sha512))))
  (let [e (error-of #(install/verify-digest (ByteArrayInputStream. (utf8 "abc")) (apply str (repeat 128 "0"))))]
    (is (= :digest (::install/error e)))
    (is (= abc-sha512 (:actual e)))))

;; --- member paths and link targets (pure) -----------------------------------------------

(deftest member-paths-are-plain-components-only
  (is (= "a/b/c" (install/member-path "a/b/c")))
  (is (= "a/b" (install/member-path "./a/./b/")))
  (is (= "" (install/member-path "./")))
  (is (= "GE/files/share/default_pfx/dosdevices/c:" (install/member-path "GE/files/share/default_pfx/dosdevices/c:"))
      "Proton's prefix template has a c: link")
  (doseq [evil ["../x" "a/../../x" "/etc/passwd" "a/.." "C:/x" "c:x" "..\\x" "a\\..\\..\\x"]]
    (is (= :traversal (::install/error (error-of #(install/member-path evil)))) (pr-str evil))))

(deftest link-targets-resolve-lexically-inside-the-tree
  (is (= "GE/version" (install/resolve-link "GE/files/lib" "../../version")))
  (is (= "" (install/resolve-link "GE" "..")))
  (is (= "GE/x" (install/resolve-link "GE" "./x")))
  (is (nil? (install/resolve-link "GE" "../..")))
  (is (nil? (install/resolve-link "GE" "/etc/passwd")))
  (is (nil? (install/resolve-link "" "../../../../etc/passwd")))
  (is (nil? (install/resolve-link "GE" "C:\\Windows"))))

;; --- the extractor ------------------------------------------------------------------------

(deftest extract-returns-the-single-top-level-directory
  (let [dir (temp-dir) into (str dir "/into")]
    (.mkdirs (io/file into))
    (let [top (extract (tar-gz-file dir "ok.tar.gz" ge-version) into)]
      (is (= "GE-Proton11-6-x86_64" (.getName (io/file top))))
      (is (= "1787951532 GE-Proton11-6" (str/trim (slurp (io/file top "version"))))))))

(deftest extract-refuses-an-archive-with-a-traversing-member
  (let [dir (temp-dir) into (str dir "/into")]
    (.mkdirs (io/file into))
    (is (= :traversal (::install/error (error-of #(extract (tar-gz-file dir "evil.tar.gz"
                                                                       {:name "../escaped.txt" :data "pwn"})
                                                          into)))))
    (is (not (.exists (io/file dir "escaped.txt"))) "nothing may be written outside")))

(deftest extract-refuses-a-symlink-whose-target-escapes
  (let [dir (temp-dir) into (str dir "/into")]
    (.mkdirs (io/file into))
    (doseq [target ["../../../../etc/passwd" "/etc/passwd"]]
      (is (= :traversal (::install/error (error-of #(extract (tar-gz-file dir "sym.tar.gz"
                                                                         {:name "GE-Proton11-6-x86_64/escape"
                                                                          :type \2 :link target})
                                                            into))))
          target))))

(deftest extract-refuses-a-hard-link-whose-target-escapes
  (let [dir (temp-dir) into (str dir "/into")]
    (.mkdirs (io/file into))
    (is (= :traversal (::install/error (error-of #(extract (tar-gz-file dir "hard.tar.gz"
                                                                       {:name "GE-Proton11-6-x86_64/escape"
                                                                        :type \1 :link "../../../../etc/passwd"})
                                                          into)))))))

(deftest extract-allows-a-relative-symlink-that-stays-inside-the-tree
  ;; GE's tree has relative links that climb before they descend
  (let [dir (temp-dir) into (str dir "/into")
        archive (tar-gz-file dir "sym.tar.gz" ge-version
                             {:name "GE-Proton11-6-x86_64/files/lib/alias" :type \2 :link "../../version"})]
    (.mkdirs (io/file into))
    ;; creating a symlink needs privilege on Windows
    (ts/requires [(ts/linux)]
      (let [top (extract archive into)]
        (is (= "GE-Proton11-6-x86_64" (.getName (io/file top))))
        (is (= "../../version" (str (Files/readSymbolicLink (.toPath (io/file top "files/lib/alias"))))))))))

(deftest extract-makes-hard-links-and-directories
  (let [dir (temp-dir) into (str dir "/into")
        top (extract (tar-gz-file dir "links.tar.gz"
                                  {:name "GE/" :type \5 :mode 0755}
                                  {:name "GE/empty/" :type \5}
                                  {:name "GE/bin/wine" :data "#!wine" :mode 0755}
                                  {:name "GE/bin/wine64" :type \1 :link "GE/bin/wine"}
                                  {:name "GE/fifo" :type \6})
                     into)]
    (is (.isDirectory (io/file top "empty")))
    (is (= "#!wine" (slurp (io/file top "bin/wine64"))))
    (is (not (.exists (io/file top "fifo"))) "no device nodes or fifos")
    (when (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix")
      (is (= "rwxr-xr-x" (java.nio.file.attribute.PosixFilePermissions/toString
                           (Files/getPosixFilePermissions (.toPath (io/file top "bin/wine"))
                                                          (make-array LinkOption 0))))))))

(deftest extract-reads-long-names-every-way-tar-writes-them
  (let [dir  (temp-dir) into (str dir "/into")
        deep (str "GE/" (str/join "/" (repeat 12 "directory")) "/a-file-whose-name-is-long.txt")
        pax  (fn [k v] (let [rec (str " " k "=" v "\n")
                             n   (loop [n (+ (count rec) 1)]
                                   (let [m (+ (count rec) (count (str n)))] (if (= m n) n (recur m))))]
                         (str n rec)))
        top  (extract (tar-gz-file dir "long.tar.gz"
                                   {:name "././@LongLink" :type \L :data (str deep "\u0000")}
                                   {:name (subs deep 0 99) :data "gnu"}
                                   {:name "a-file-whose-name-is-long.txt" :fmt :ustar :data "ustar"
                                    :prefix (str "GE/" (str/join "/" (repeat 11 "directory")))}
                                   {:name "PaxHeaders/x" :type \x :data (str (pax "path" (str deep ".pax"))
                                                                             (pax "mtime" "1.5"))}
                                   {:name "short" :data "pax"}
                                   {:name "GE/bin/wine" :data "w"}
                                   {:name "././@LongLink" :type \K :data "GE/bin/wine\u0000"}
                                   {:name "GE/bin/long-link" :type \1 :link "truncated"}
                                   {:name "GE/glob" :type \g :data (pax "comment" "ignored")})
                      into)]
    (is (= "gnu" (slurp (io/file into deep))))
    (is (= "ustar" (slurp (io/file top (str/join "/" (repeat 11 "directory")) "a-file-whose-name-is-long.txt"))))
    (is (= "pax" (slurp (io/file into (str deep ".pax")))))
    (is (= "w" (slurp (io/file top "bin/long-link"))) "a GNU long link name")
    (is (not (.exists (io/file into "short"))))
    (is (not (.exists (io/file into "GE/glob"))))))

(deftest extract-refuses-malformed-archives
  (let [dir (temp-dir) into (str dir "/into") kind #(::install/error (error-of %))]
    (.mkdirs (io/file into))
    (is (= :archive (kind #(extract (tar-gz-file dir "two.tar.gz" ge-version {:name "other/x" :data "x"}) into)))
        "one top-level directory per archive")
    (is (= :archive (kind #(extract (tar-gz-file dir "empty.tar.gz") into))) "empty")
    (is (= :archive (kind #(extract (tar-gz-file dir "file.tar.gz" {:name "version" :data "1 GE-Proton1-1"}) into)))
        "a single file at the top is not a runtime")
    (is (= :archive (kind #(extract (tar-gz-file dir "sum.tar.gz" (assoc ge-version :cksum 1)) into)))
        "a bad header checksum")
    (let [f (io/file dir "short.tar.gz")]
      (io/copy (gz (java.util.Arrays/copyOf (tar ge-version) 700)) f)
      (is (= :archive (kind #(extract (str f) into))) "truncated mid-entry"))))

(deftest extract-reads-a-base-256-size
  (let [dir (temp-dir) into (str dir "/into")
        size (doto (byte-array 12) (aset-byte 0 (unchecked-byte 0x80)) (aset-byte 11 (byte 3)))
        top (extract (tar-gz-file dir "b256.tar.gz" {:name "GE/x" :data "abc" :size-bytes size}) into)]
    (is (= "abc" (slurp (io/file top "x"))))))

(deftest bad-sizes-are-archive-errors
  (let [dir (temp-dir) into (str dir "/into") kind #(::install/error (error-of %))
        huge (doto (byte-array 12 (unchecked-byte 0xff)) (aset-byte 0 (unchecked-byte 0x80)))
        pax  (fn [v] (let [rec (str " size=" v "\n") n (+ (count rec) 2)] (str n rec)))]
    (is (= :archive (kind #(extract (tar-gz-file dir "big.tar.gz" {:name "GE/x" :data "" :size-bytes huge}) into)))
        "a base-256 size past a long")
    (doseq [v ["-5" "x"]]
      (is (= :archive (kind #(extract (tar-gz-file dir "neg.tar.gz" {:name "p" :type \x :data (pax v)}
                                                   {:name "GE/x" :data "abc"})
                                      into)))
          (str "a PAX size of " v)))))

(deftest a-hard-link-source-must-really-be-inside-the-tree
  ;; GE/sub/up -> ../.. is lexically the root, so GE/sub/up/../secret is
  ;; GE/secret; really it is the root's sibling
  (let [dir (temp-dir) into (str dir "/into")]
    (spit (io/file dir "secret") "s")
    (ts/requires [(ts/linux)]
      (is (= :traversal (::install/error (error-of #(extract (tar-gz-file dir "hl.tar.gz"
                                                                          {:name "GE/" :type \5}
                                                                          {:name "GE/sub/" :type \5}
                                                                          {:name "GE/sub/up" :type \2 :link "../.."}
                                                                          {:name "GE/h" :type \1 :link "GE/sub/up/../secret"})
                                                             into))))))))

(deftest extract-replaces-a-link-rather-than-writing-through-it
  (let [dir (temp-dir) into (str dir "/into") outside (io/file dir "outside.txt")]
    (spit outside "keep")
    (.mkdirs (io/file into "GE"))
    (ts/requires [(ts/linux)]
      (Files/createSymbolicLink (.toPath (io/file into "GE/f")) (.toPath outside) (make-array FileAttribute 0))
      (extract (tar-gz-file dir "over.tar.gz" {:name "GE/f" :data "new"}) into)
      (is (= "keep" (slurp outside)))
      (is (= "new" (slurp (io/file into "GE/f")))))))

(deftest extract-refuses-to-write-beneath-a-link-that-leaves-the-tree
  ;; GE/b -> .. is the root, and lexically GE/a -> b/.. is GE; really it is
  ;; the root's parent: the real-path check catches what the lexical one can't
  (let [dir (temp-dir) into (str dir "/into")]
    (ts/requires [(ts/linux)]
      (is (= :traversal (::install/error (error-of #(extract (tar-gz-file dir "chain.tar.gz"
                                                                          {:name "GE/" :type \5}
                                                                          {:name "GE/b" :type \2 :link ".."}
                                                                          {:name "GE/a" :type \2 :link "b/.."}
                                                                          {:name "GE/a/escaped" :data "pwn"})
                                                             into)))))
      (is (not (.exists (io/file dir "escaped")))))))

;; --- the gate, the plan, the rename -------------------------------------------------------

(deftest extract-then-gate-rejects-a-stock-proton-tarball
  ;; the right bytes and GE-Proton are separate claims
  (let [dir (temp-dir) into (str dir "/into")
        top (extract (tar-gz-file dir "stock.tar.gz" {:name "proton-9.0-4/version" :data "1234567890 proton-9.0\n"})
                     into)]
    (is (= :not-ge (::install/error (error-of #(install/gate top)))))
    (is (= :not-ge (::install/error (error-of #(install/gate (temp-dir))))) "no version file")
    (is (= "GE-Proton11-6" (install/gate (extract (tar-gz-file dir "ge.tar.gz" ge-version) (str dir "/into2")))))))

(deftest the-plan-names-every-path-and-refuses-a-bad-tag
  (is (= {:runtimes  "/h/runtimes"
          :downloads "/h/downloads"
          :final     "/h/runtimes/GE-Proton11-6"
          :partial   "/h/downloads/GE-Proton11-6.tar.gz.partial"
          :tmp       "/h/runtimes/.tmp-GE-Proton11-6-42"
          :aside     "/h/runtimes/.old-GE-Proton11-6-42"}
         (install/plan "/h" "GE-Proton11-6" 42)))
  (is (= "/h/downloads/GE-Proton11-6.tar.gz.partial" (install/partial-path "/h" "GE-Proton11-6")))
  (is (= :traversal (::install/error (error-of #(install/plan "/h" "../../escaped" 1))))))

(deftest the-rename-plan-moves-an-old-runtime-aside-first
  (let [p (install/plan "/h" "GE-Proton11-6" 7)]
    (is (= {:before [] :steps [[:move "/t/top" "/h/runtimes/GE-Proton11-6"]] :undo [] :after []}
           (install/rename-plan p "/t/top" false)))
    (is (= {:before [[:delete "/h/runtimes/.old-GE-Proton11-6-7"]]
            :steps  [[:move "/h/runtimes/GE-Proton11-6" "/h/runtimes/.old-GE-Proton11-6-7"]
                     [:move "/t/top" "/h/runtimes/GE-Proton11-6"]]
            :undo   [[:move "/h/runtimes/.old-GE-Proton11-6-7" "/h/runtimes/GE-Proton11-6"]]
            :after  [[:delete "/h/runtimes/.old-GE-Proton11-6-7"]]}
           (install/rename-plan p "/t/top" true)))))

(deftest a-failed-rename-puts-the-old-runtime-back
  (let [home (temp-dir) p (install/plan home "GE-Proton11-6" 7)]
    (spit (doto (io/file (:final p) "version") io/make-parents) "1 GE-Proton11-6\n")
    (is (thrown? java.io.IOException (install/rename! (install/rename-plan p (str home "/missing-top") true))))
    (is (= "1 GE-Proton11-6\n" (slurp (io/file (:final p) "version"))))
    (is (not (.exists (io/file (:aside p)))))))

(deftest install-archive-extracts-gates-and-renames-last
  (let [home (temp-dir) dir (temp-dir)]
    (testing "a fresh install"
      (let [got (install/install-archive! home "GE-Proton11-6" (tar-gz-file dir "a.tar.gz" ge-version) {:pid 1})]
        (is (= {:tag "GE-Proton11-6" :dir (layout/runtime-dir home "GE-Proton11-6") :fresh true} got))
        (is (= "1787951532 GE-Proton11-6\n" (slurp (io/file (:dir got) "version"))))
        (is (= ["GE-Proton11-6"] (vec (.list (io/file (layout/runtimes home))))) "no .tmp- or .old- left")))
    (testing "already installed: nothing to do"
      (is (= {:tag "GE-Proton11-6" :dir (layout/runtime-dir home "GE-Proton11-6") :fresh false}
             (install/installed home "GE-Proton11-6")))
      (is (nil? (install/installed home "GE-Proton11-7"))))
    (testing "a stock tarball leaves the working runtime as it was"
      (is (= :not-ge (::install/error
                      (error-of #(install/install-archive!
                                  home "GE-Proton11-6"
                                  (tar-gz-file dir "s.tar.gz" {:name "GE-Proton11-6-x86_64/version" :data "1 proton-9.0\n"})
                                  {:pid 2})))))
      (is (= "1787951532 GE-Proton11-6\n" (slurp (io/file (layout/runtime-dir home "GE-Proton11-6") "version"))))
      (is (= ["GE-Proton11-6"] (vec (.list (io/file (layout/runtimes home)))))))
    (testing "a forced reinstall replaces it"
      (install/install-archive! home "GE-Proton11-6"
                                (tar-gz-file dir "b.tar.gz" (assoc ge-version :data "2 GE-Proton11-6\n")) {:pid 3})
      (is (= "2 GE-Proton11-6\n" (slurp (io/file (layout/runtime-dir home "GE-Proton11-6") "version"))))
      (is (= ["GE-Proton11-6"] (vec (.list (io/file (layout/runtimes home)))))))))

(deftest a-tag-that-would-escape-the-runtimes-directory-is-refused-before-any-io
  (let [home (str (temp-dir) "/home")]
    (is (= :traversal (::install/error (error-of #(install/install-archive! home "../../escaped" "nowhere.tar.gz" {})))))
    (is (= :traversal (::install/error (error-of #(install/installed home "../x")))))
    (is (not (.exists (io/file home))) "a refused tag creates no directory")))
