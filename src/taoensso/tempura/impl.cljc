(ns taoensso.tempura.impl
  "Private implementation details."
  (:require
   [clojure.string  :as str]
   [taoensso.encore :as enc :refer [have have?]]))

(defn str->?arg-idx [s]
  (case s
    ;; % 0 ; No, prefer minimizing alternatives + allows % literal pass through
    ;; "%" (throw (ex-info "`%` is invalid, please use `%1`"))
    "%0" (throw (ex-info "`%0` is invalid, starts at `%1`" {:s s}))
    "%1" 0 "%2" 1, "%3" 2, "%4" 3, "%5" 4, "%6" 5, "%7" 6, "%8" 7, "%9" 8,
    "%10" 9, "%11" 10, "%12" 11, "%13" 12,
    nil))

(def ^:private re-clojure-arg #_#"%\d?" #"%\d{1,2}")

(defn str->split-args
  "Checks string for simple Clojure-style (%d) args and returns a vector of
  string parts and int arg indexes for later joining."
  [s]
  (have? string? s)
  (if-not (and (enc/str-contains? s "%") (re-find re-clojure-arg s))
    [s] ; Optimize common case
    (let [uuid-esc (enc/uuid-str)
          s        (enc/str-replace s "`%" uuid-esc) ; Protect escaped %'s
          ?arg-seq (re-seq re-clojure-arg s)]

      (if (empty? ?arg-seq) ; May have had escaped %, etc.
        [(enc/str-replace s uuid-esc "%")]
        (let [arg-idxs (mapv str->?arg-idx ?arg-seq)
              splits   (str/split s re-clojure-arg)
              splits   (mapv (fn [s] (enc/str-replace s uuid-esc "%")) splits)]

          (enc/vinterleave-all splits arg-idxs))))))

(comment
  (str->split-args "hello %1, how are %1 %2? %% `% ``%")
  (str->split-args "hello %1") ; => ["hello " 0]
  (str->split-args "%1") ; => [0]
  )

(defn str->vargs-fn
  "Returns a (fn [args-vector]) which replaces simple Clojure-style (%n) args
  in string with `(str (?argval-fn <corresponding-vector-arg>))`.
  Optimized for fn runtime, not fn creation."
  ([s] (str->vargs-fn s nil))
  ([s argval-fn]
   (have? string? s)
   (let [parts (str->split-args s)
         ;; Why the undefined check? Vestigial?
         argval-fn (or argval-fn #?(:clj identity :cljs #(if (undefined? %) nil %)))]

     (if (= (count parts) 1) ; Optimize common-case:
       (let [[p1] parts]
         (enc/cond!
           (string?  p1) (fn [vargs] p1)
           (integer? p1) (fn [vargs] (str (argval-fn (get vargs p1))))))

       (fn [vargs]
         (let [sb (enc/str-builder)]
           (run!
             (fn [p]
               (if (string? p)
                 (enc/sb-append sb p)
                 (enc/sb-append sb (str (argval-fn (get vargs p))))))
             parts)
           (str sb)))))))

(comment :see-tests)
(comment
  (str->split-args "hello %1 %2")
  ((str->vargs-fn "")            ["a" "b"]) ; ""
  ((str->vargs-fn "hello %1 %2") ["a" "b"]) ; "hello a b"
  ((str->vargs-fn "hello") ["a" "b"]) ; "hello"
  ((str->vargs-fn "%1")    ["a" "b"]) ; "a"
  ((str->vargs-fn "%2")    ["a" "b"]) ; "b"
  (let [f1 (fn [vargs] (apply format "hello %s %s" vargs))
        f2 (str->vargs-fn "hello %1 %2")]
    (enc/qb 1e5
      (f1 ["a1" "a2"])
      (f2 ["a1" "a2"]))))

(defn- mapv-nested     [f v] (mapv (fn f1 [in] (if (vector? in) (mapv f1 in) (f in))) v))
(defn- reducev-nested [rf v]
  (reduce
    (fn rf1 [acc in]
      (if (vector? in)
        (conj acc (reduce rf1 [] in))
        (rf acc in)))
    [] v))

(comment (mapv-nested keyword ["a" "b" ["c" "d"]]))

#_
(defn- reduce-kv-nested [rf m]
  (reduce-kv
    (fn rf1 [acc k v]
      (if (map? v)
        (assoc acc k (reduce-kv rf1 {} v))
        (rf acc k v)))
    {} m))

(defn node-paths ; Also in tl-core
  ([          m      ] (node-paths associative? m nil))
  ([node-pred m      ] (node-paths node-pred    m nil))
  ([node-pred m basis]
   (let [basis (or basis [])]
     (reduce-kv
       (fn [acc k v]
         (if-not (node-pred v)
           (conj acc (conj basis k v))
           (let [paths-from-basis (node-paths node-pred v (conj basis k))]
             (into acc paths-from-basis))))
       [] m))))

(comment
  (node-paths associative? {:a1 :A1 :a2 {:b1 :B1 :b2 {:c1 :C1 :c2 :C2}}} [:h])
  (node-paths [:a1 :a2 [:b1 :b2 [:c1 :c2] :b3] :a3 :a4]))

(defn vec->vargs-fn
  "Returns a (fn [args-vector]) which replaces simple Clojure-style (%n) arg
  elements with `(?argval-fn <corresponding-vector-arg>)`.
  Optimized for fn runtime, not fn creation."
  ([v] (vec->vargs-fn v nil))
  ([v argval-fn]
   (have? vector? v)
   (let [node-paths (node-paths v)
         idxs->arg-idxs ; {2 0, 3 {2 1}, 5 {1 {1 0}}}, etc.
         (reduce
           (fn [acc in]
             (let [el (peek in)]
               (if-not (symbol? el)
                 acc
                 (if-let [idx (str->?arg-idx (name el))]
                   (assoc-in acc (pop in) idx)
                   acc))))
           {} node-paths)]

     (if (empty? idxs->arg-idxs)
       (fn [vargs] v) ; Common case
       (let [argval-fn (or argval-fn identity)]
         (fn [vargs]
           (reduce-kv
             (fn rf1 [acc k v]
               (if (map? v)
                 (assoc acc k (reduce-kv rf1 (get acc k) v))
                 (assoc acc k (argval-fn (get vargs v)))))
             v idxs->arg-idxs)))))))

(comment :see-tests)

;;;;

(defn attrs-explode-args-in-strs [v] ; TODO Non-recursive
  (have? vector? v)
  (let [[v1 ?attrs] v]
    (if-not (map? ?attrs)
      v
      (let [attrs
            (enc/map-vals
              (fn [v]
                (if (string? v)
                  (let [parts (str->split-args v)]
                    (if (> (count parts) 1)
                      parts
                      v))
                  v))
              ?attrs)]
        ;; TODO Could immediately make this a vargs fn?
        (if (= attrs ?attrs)
          v
          (assoc v 1 attrs))))))

(comment
  (attrs-explode-args-in-strs [:a     {:foo "hi"    :bar "baz"} "hello"])
  (attrs-explode-args-in-strs [:a     {:foo "hi %1" :bar "baz"} "hello"])
  (attrs-explode-args-in-strs [:a [:b {:foo "hi %1" :bar "baz"} "hello"]]))

(defn vec-explode-args-in-strs [v]
  (have? vector? v)
  (reducev-nested
    (fn [acc in]
      (if-not (string? in)
        (conj acc in)
        (let [parts (str->split-args in)
              parts (mapv (fn [p] (if (string? p) p (symbol (str "%" (inc (long p))))))
                      parts)]
          (into acc parts))))
    v))

(comment
  (vec-explode-args-in-strs
    [:a [:b [:c "hi %1" "boo"] "hi %1 %1" [:strong "My name is %3"] "%1"]]))

;;;;

(defn str->split-styles [s]
  (have? string? s)
  (let [matches_ (volatile! {})
        replace-matches
        (fn [s regex tag]
          (enc/str-replace s regex
            (fn [[_ _ content]]
              (let [uuid (enc/uuid-str)]
                (vswap! matches_ assoc uuid [tag content])
                uuid))))

        uuid-esc*      (enc/uuid-str)
        uuid-esc_      (enc/uuid-str)
        uuid-esc-tilde (enc/uuid-str)

        ;; TODO Reddit also escapes everything between \* and \*,
        ;; Ref. https://www.reddit.com/wiki/commenting. Duplicate that or
        ;; something like it? Probably overkill for our (mostly 1-line) needs.

        s (enc/str-replace s "`*" uuid-esc*)
        s (enc/str-replace s "`_" uuid-esc_)
        s (enc/str-replace s "`~" uuid-esc-tilde)

        ;;; Intentionally _very_ simple/conservative styling capabilities
        s (replace-matches s #"(\*\*)([^\*\r\n]+)\1" :strong)
        s (replace-matches s   #"(__)([^_\r\n]+)\1"  :b)
        s (replace-matches s   #"(\*)([^\*\r\n]+)\1" :em)
        s (replace-matches s    #"(_)([^_\r\n]+)\1"  :i)

        ;;; Specials (for arbitrary inline styling, etc.)
        s (replace-matches s   #"(~~)([^~\r\n]+)\1"  :mark)
        s (replace-matches s  #"(~1~)([^~\r\n]+)\1"  :span.tspecl1)
        s (replace-matches s  #"(~2~)([^~\r\n]+)\1"  :span.tspecl2)

        s (enc/str-replace s uuid-esc*      "*")
        s (enc/str-replace s uuid-esc_      "_")
        s (enc/str-replace s uuid-esc-tilde "~")

        matches @matches_]

    (if (empty? matches)
      [s]
      (let [ordered-match-ks (sort-by #(enc/str-?index s %) (keys matches))
            ordered-match-vs (mapv #(get matches %) ordered-match-ks)
            splits (str/split s (re-pattern (str/join "|" ordered-match-ks)))]
        (enc/vinterleave-all splits ordered-match-vs)))))

(comment (str->split-styles "_hello_ **there** this is a _test_ `*yo`* ~1~hello~1~"))

(defn vec->vtag
  "[\"foo\"] -> [:span \"foo\"] as a convenience."
  ([v            ] (vec->vtag v :span))
  ([v default-tag]
   (have? vector? v)
   (let [[v1] v]
     (if-not (keyword? v1)
       (into [default-tag] v)
       v))))

(comment
  (vec->vtag [:div.special "foo"]) ; Allow control of tag type
  (vec->vtag ["foo"]) ; But default to :span
  (vec->vtag []))

(defn vec-explode-styles-in-strs
  ([v] (vec-explode-styles-in-strs v str->split-styles))
  ([v str-splitter]
   (have? vector? v)
   (reducev-nested
     (fn [acc in]
       (if-not (string? in)
         (conj acc in)
         (into acc (str-splitter in))))
     v)))

(comment (vec-explode-styles-in-strs [:a "hello there **this** is a test"]))

;;;;

(defn escape-html ; Modified from `tl-core/html-esc`
  [s]
  (-> s
    (enc/str-replace    "&"  "&amp;") ; First!
    (enc/str-replace    "<"  "&lt;")
    (enc/str-replace    ">"  "&gt;")
    ;; (enc/str-replace "'"  "&#39;") ; NOT &apos;
    (enc/str-replace    "\"" "&quot;")))

(comment
  (html-escape "Hello, x>y & the cat's hat's fuzzy. <boo> \"Hello there\""))

(defn vec-escape-html-in-strs [v]
  (have? vector? v)
  (mapv-nested (fn [x] (if (string? x) (escape-html x) x)) v))

(comment (vec-escape-html-in-strs [:div "Hello there " [:strong "& goodbye"]]))

;;;;

(defn expand-locale
  ":en-GB-var1 -> [:en-GB-var1 :en-GB :en], etc."
  [locale]
  (let [parts (str/split (str/lower-case (name locale)) #"[_-]")]
    (if (== (count parts) 1)
      [locale]
      (loop [[p0 & pn] parts, sb nil, acc ()]

        (let [sb  (if sb (enc/sb-append sb "-" p0) (enc/str-builder p0))
              acc (conj acc (keyword (str sb)))]

          (if pn
            (recur pn sb acc)
            (vec         acc)))))))

(comment (enc/qb 1e5 (expand-locale :en-GB-var1))) ; 91.72

(defn expand-locales
  [locales]

  ;; TODO Note that this fallback preference approach might not be
  ;; sophisticated enough for use with BCP 47, etc. -
  ;; Ref. https://github.com/ptaoussanis/tower/issues/65
  ;;
  ;; Punting on the issue for now; we can always swap out the fallback
  ;; strategy later. Indeed might not be necessary if consumers can provide
  ;; an appropriately prepared input for this fn.

  (if (== (count locales) 1)
    [(expand-locale (nth locales 0))]
    (let [[acc _]
          (reduce
            (fn [[acc seen] in]
              (let [lvars (expand-locale in)
                    lbase (peek lvars)]

                (if (seen lbase)
                  [acc seen]
                  [(conj acc lvars) (conj seen lbase)])))

            [[] #{}]
            locales)]
      acc)))

(comment (enc/qb 1e4 (expand-locales [:en-US-var1 :fr-FR :fr :en-GD :DE-de]))) ; 38.0

(comment :see-tests)

#?(:clj (def ^:private cached-read-edn (enc/fmemoize enc/read-edn)))
(defn load-resource [rname]
  #?(:clj
     (if-let [edn (enc/slurp-file-resource rname)]
       (try
         (cached-read-edn edn) ; Ref transparent
         (catch Exception e
           (throw
             (ex-info "Failed to load dictionary resource"
               {:rname rname} e))))
       ;; nil ; Silent failure, lean on :missing
       (throw
         (ex-info "Failed to load dictionary resource (not found)"
           {:rname rname})))

     :cljs
     (throw
       (ex-info "Runtime resource loading not possible for cljs dictionaries. See `tempura/load-resource-at-compile-time` as an alternative."
         {:rname rname}))))

(comment (load-resource "foo.edn"))

(let [preprocess ; For pointers and slurps, etc.
      (fn [dict]
        (reduce-kv
          (fn rf1 [acc k v]
            (cond
              (keyword? v) ; Pointer
              (let [path (enc/explode-keyword v)]
                (assoc acc k (get-in dict (mapv keyword path))))

              (map? v)
              (if-let [io-res (:__load-resource v)]
                (assoc acc k (load-resource io-res))
                (assoc acc k (reduce-kv rf1 {} v)))

              :else (assoc acc k v)))
          {} dict))

      as-paths ; For locale normalization, lookup speed, etc.
      (enc/fmemoize ; Ref. transparent, limited domain
        (fn [dict]
          (reduce
            (fn [acc in]
              (let [[locale] in
                    normed-locale (str/lower-case (name locale))
                    in (assoc in 0 normed-locale)]
                (assoc acc (enc/merge-keywords (pop in)) (peek in))))
            {} (node-paths map? dict))))]

  (defn compile-dictionary [dict] (-> dict preprocess as-paths)))

(comment
  (enc/qb 1e4
    (compile-dictionary nil
      {:en-GB
       {:example {:foo "foo"
                  :bar "bar"
                  :baz :en.example/bar}
        :example-copy :en/example
        :missing "hi"
        :import-example
        {:__load-resource "resources/i18n.clj"}}})))

(defn vargs [x]
  (if (map? x)
    (let [^long max-idx (reduce #(enc/max* ^long %1 ^long %2) 0 (keys x))]
      (assert (nil? (get x 0)) "All arg map keys must be +ive non-zero ints")
      (mapv (fn [idx] (get x idx)) (range 1 (inc max-idx))))
    (have vector? x)))

(comment (enc/qb 1e4 (vargs {1 :a 2 :b 3 :c 5 :d})))

;;;;

(def scoped
  (enc/fmemoize
    (fn [locale ?scope resid]
      (enc/merge-keywords [locale ?scope resid]))))

(comment (scoped :en :scope :resid))

(defn search-resids
  "loc1 res1 var1 var2 ... base
        res2 var1 var2 ... base
        ...
   loc2 res1 var1 var2 ... base
        res2 var1 var2 ... base
        ..."
  [dict locale-splits ?scope resids]
  (reduce
    (fn [acc locale-split]
      (reduce
        (fn [acc resid]
          (reduce
            (fn [acc lvar]
              ;; (debugf "Searching: %s" (scoped lvar ?scope resid))
              (when-let [res (get dict (scoped lvar ?scope resid))]
                (reduced (reduced (reduced #_[res resid] res)))))
            acc locale-split))
        acc resids))
    nil locale-splits))

;; #?(:clj
;;    (defmacro vargs "Experimental. Compile-time `impl/vargs`."
;;      [x]
;;      (if (map? x)
;;        (do
;;          (assert (enc/revery? enc/pos-int? (keys x))
;;            "All arg map keys must be +ive non-zero ints")
;;          (impl/vargs x))
;;        (have vector? x))))

#_(comment (macroexpand '(vargs {1 (do "1") 2 (do "2")})))
