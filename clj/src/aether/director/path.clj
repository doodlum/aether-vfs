(ns aether.director.path
  "Virtual paths as the ring carries them: root-relative, `/`-separated, and
  case-folded by the shim with Rust's `char::to_lowercase` (vfs_core::fold).")

(defn fold
  "Case-fold exactly like vfs_core::fold: each code point's simple lowercase.
  Not String/toLowerCase, which is context-sensitive (final sigma) and
  locale-aware. U+0130 is the one code point whose lowercase is two."
  ^String [^String s]
  (let [n (.length s)]
    (loop [i 0]
      (if (= i n)
        s                                   ; already folded: no allocation
        (let [cp (.codePointAt s i)]
          (if (and (= cp (Character/toLowerCase cp)) (not= cp 0x130))
            (recur (+ i (Character/charCount cp)))
            (let [sb (StringBuilder. n)]
              (.append sb s 0 i)
              (loop [i i]
                (if (= i n)
                  (.toString sb)
                  (let [cp (.codePointAt s i)]
                    (if (= cp 0x130)
                      (.append sb "i̇")
                      (.appendCodePoint sb (Character/toLowerCase cp)))
                    (recur (+ i (Character/charCount cp)))))))))))))

(defn normalize
  "A vpath as the director keys it: `\\` as `/`, no leading or trailing `/`,
  no `.` segments, `..` resolved. The root is \"\". nil when `..` would leave
  the root."
  [^String p]
  (if (and (neg? (.indexOf p (int \\)))
           (not (.startsWith p "/"))
           (not (.endsWith p "/"))
           (neg? (.indexOf p "//"))
           (not= p ".")
           (not (.startsWith p "./"))
           (neg? (.indexOf p "/."))
           (not (.startsWith p "..")))
    p                                       ; the shim's usual shape: nothing to do
    (loop [[seg & more] (.split (.replace p \\ \/) "/") out []]
      (cond
        (nil? seg)                   (String/join "/" ^Iterable out)
        (or (= "" seg) (= "." seg))  (recur more out)
        (= ".." seg)                 (when (seq out) (recur more (pop out)))
        :else                        (recur more (conj out seg))))))

(defn parent ^String [^String p]
  (let [i (.lastIndexOf p (int \/))] (if (neg? i) "" (subs p 0 i))))

(defn file-name ^String [^String p]
  (subs p (inc (.lastIndexOf p (int \/)))))
