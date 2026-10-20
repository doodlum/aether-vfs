(ns aether.director.stage
  "Staging a launch: `CreateProcess` needs the image as a real file, and the
  loader resolves its static imports before any hook is installed. So the
  EXE and its non-system import closure are written to a scratch directory
  (rust/crates/vfs-director/src/stage.rs). Everything else stays virtual.

  An import is staged when the tree serves it beside the EXE (or at the root),
  or a fallback directory holds it (redistributables such as d3dx9_42.dll).
  System DLLs are never staged; anything found nowhere is the loader's
  business."
  (:require [aether.director.path :as path]
            [aether.director.proto :as p]
            [aether.director.provider :as prov]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (aether.director.provider Stat)
           (java.io File)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption StandardOpenOption)))

(set! *warn-on-reflection* true)

;; --- PE import tables ---------------------------------------------------------

(defn- u16 ^long [^MemorySegment s ^long off] (Short/toUnsignedLong (.get s ValueLayout/JAVA_SHORT_UNALIGNED off)))
(defn- u32 ^long [^MemorySegment s ^long off] (Integer/toUnsignedLong (.get s ValueLayout/JAVA_INT_UNALIGNED off)))

(defn pe?
  "Does `s` start like a PE image (MZ, and a PE signature where e_lfanew says)?"
  [^MemorySegment s]
  (and (> (.byteSize s) 0x40)
       (= 0x5A4D (u16 s 0))
       (let [pe (u32 s 0x3c)]
         (and (< (+ pe 24) (.byteSize s)) (= 0x4550 (u32 s pe))))))

(defn import-names
  "The DLL names in a PE image's import directory, in order. nil when `s`
  isn't a PE image or has no imports."
  [^MemorySegment s]
  (when (pe? s)
    (let [pe       (u32 s 0x3c)
          sections (u16 s (+ pe 6))
          opt      (+ pe 24)
          opt-size (u16 s (+ pe 20))
          dirs     (case (u16 s opt) 0x20b (+ opt 112) 0x10b (+ opt 96) nil)
          imp-rva  (when dirs (u32 s (+ dirs 8)))
          ;; an RVA's file offset, through the section that contains it
          offset   (fn [^long rva]
                     (some (fn [i]
                             (let [h  (+ opt opt-size (* 40 (long i)))
                                   va (u32 s (+ h 12))
                                   sz (max (u32 s (+ h 8)) (u32 s (+ h 16)))]
                               (when (and (>= rva va) (< rva (+ va sz)))
                                 (+ (- rva va) (u32 s (+ h 20))))))
                           (range sections)))
          c-string (fn [^long off]
                     (let [end (loop [i off] (if (zero? (.get s ValueLayout/JAVA_BYTE i)) i (recur (inc i))))
                           b   (byte-array (- end off))]
                       (MemorySegment/copy s ValueLayout/JAVA_BYTE off b (int 0) (alength b))
                       (String. b StandardCharsets/US_ASCII)))]
      (when-let [d (and imp-rva (pos? imp-rva) (offset imp-rva))]
        (loop [d (long d) out []]
          (let [name-rva (u32 s (+ d 12))]
            (if (zero? name-rva)
              out
              (recur (+ d 20) (if-let [o (offset name-rva)] (conj out (c-string o)) out)))))))))

;; vfs-pe's is_system_import_dll: resolved from System32, never staged. The
;; DirectX June 2010 runtime (xinput1_3, x3daudio1_7, d3dx9_*) is not part of
;; Windows, so it is staged like any other import.
(def ^:private system-dlls
  #{"kernel32.dll" "kernelbase.dll" "ntdll.dll" "user32.dll" "gdi32.dll" "gdi32full.dll" "advapi32.dll"
    "shell32.dll" "ole32.dll" "oleaut32.dll" "ws2_32.dll" "winhttp.dll" "winmm.dll" "setupapi.dll" "hid.dll"
    "d3d11.dll" "dxgi.dll" "dinput8.dll" "xinput1_4.dll" "msvcp140.dll" "vcruntime140.dll" "vcruntime140_1.dll"
    "ucrtbase.dll" "sechost.dll" "rpcrt4.dll" "combase.dll" "shlwapi.dll" "version.dll" "imm32.dll" "dwmapi.dll"
    "uxtheme.dll" "bcrypt.dll" "bcryptprimitives.dll" "crypt32.dll" "wintrust.dll"})

(defn system-dll? [^String dll]
  (let [n (str/lower-case dll)]
    (or (str/starts-with? n "api-ms-") (str/starts-with? n "ext-ms-") (contains? system-dlls n))))

;; --- staging --------------------------------------------------------------------

(defn- write! [^MemorySegment data ^File dest]
  (io/make-parents dest)
  (with-open [ch (FileChannel/open (.toPath dest) (into-array OpenOption [StandardOpenOption/CREATE
                                                                          StandardOpenOption/TRUNCATE_EXISTING
                                                                          StandardOpenOption/WRITE]))]
    (let [buf (.asByteBuffer data)]
      (while (.hasRemaining buf) (.write ch buf)))))

(defn- on-disk
  "A DLL from a fallback directory, matched case-insensitively."
  [dirs ^String dll]
  (some (fn [d] (some #(when (.equalsIgnoreCase dll (.getName ^File %)) %)
                      (.listFiles (io/file d))))
        dirs))

(defn- read-file
  "The whole file at `vp` read through provider `t`, or nil if it isn't a file."
  ^MemorySegment [t vp]
  (let [h (prov/open t vp p/open-read)]
    (when-not (number? h)
      (try
        (let [^Stat st (prov/handle-stat h)]
          (when-not (.-dir? st)
            (let [buf (MemorySegment/ofArray (byte-array (.-size st)))]
              (loop [pos 0]
                (if (>= pos (.-size st))
                  buf
                  (let [n (long (prov/read-into h buf pos pos (- (.-size st) pos)))]
                    (if (pos? n) (recur (+ pos n)) buf)))))))
        (finally (prov/close! h))))))

(defn stage
  "Writes `exe` (a vpath the tree serves) and its import closure under
  `dir`, keeping the EXE's folder. Returns {:exe <staged path> :staged
  [names] :files [written files]}. `fallback` dirs are searched for imports
  the tree doesn't serve; `also` stages more images (and their closures),
  for DLLs a program loads without importing them (SKSE's runtime)."
  [t ^String exe {:keys [dir fallback also]}]
  (let [exe-dir (path/parent (path/normalize exe))
        target  (io/file dir exe-dir)
        lookup  (fn [vp] (read-file t vp))]
    (loop [pending (vec (cons exe also)) seen #{} staged [] files []]
      (if-let [[image & more] (seq pending)]
        (let [base (path/file-name (path/normalize image))
              k    (str/lower-case base)
              data (or (lookup image)
                       (lookup (if (= "" exe-dir) base (str exe-dir "/" base)))
                       (lookup base)
                       (when-let [f (on-disk fallback base)]
                         (MemorySegment/ofArray (Files/readAllBytes (.toPath ^File f)))))]
          (cond
            (contains? seen k) (recur (vec more) seen staged files)
            (and (nil? data) (= image exe)) (throw (ex-info (str "the VFS has no " exe) {:exe exe}))
            (or (nil? data) (not (pe? data))) (recur (vec more) (conj seen k) staged files)
            :else
            (let [dest (io/file target base)]
              (write! data dest)
              (recur (into (vec more) (remove system-dll? (import-names data)))
                     (conj seen k) (conj staged base) (conj files dest)))))
        {:exe (.getCanonicalPath (io/file target (path/file-name (path/normalize exe))))
         :staged staged :files files}))))

(defn cleanup!
  "Deletes what `stage` wrote, then any directories it leaves empty up to `dir`."
  [{:keys [files]} dir]
  (doseq [^File f files] (io/delete-file f true))
  (let [stop (.getCanonicalFile (io/file dir))]
    (doseq [^File f files]
      (loop [d (.getParentFile (.getCanonicalFile f))]
        (when (and d (not= d (.getParentFile stop)) (.isDirectory d) (empty? (.list d)))
          (.delete d)
          (recur (.getParentFile d)))))))
