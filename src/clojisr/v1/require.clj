(ns clojisr.v1.require
  (:require [clojisr.v1.session :as session]
            [clojisr.v1.using-sessions :as using-sessions]
            [clojisr.v1.eval :as evl]
            [clojisr.v1.protocols :as prot]
            [clojisr.v1.util :as util
             :refer [clojurize-r-symbol]]
            [clojisr.v1.impl.common
             :refer [strange-symbol-name?]]
            [cambium.core :as log]))

(defn package-r-object [package-symbol object-symbol]
  (evl/r (format "{%s::`%s`}"
                 (name package-symbol)
                 (name object-symbol))
         (session/fetch-or-make nil)))

(defn package-symbol->nonstrange-r-symbols [package-symbol]
  (let [session (session/fetch-or-make nil)]
    (->> (prot/package-symbol->r-symbol-names
          session package-symbol)
         (remove strange-symbol-name?)
         (map symbol))))

(defn all-r-symbols-map [package-symbol]
  (->> package-symbol
       package-symbol->nonstrange-r-symbols
       (map (fn [r-symbol]
              [r-symbol (try
                          (package-r-object package-symbol r-symbol)
                          (catch Exception e
                            (log/warn [::failed-requiring {:package-symbol package-symbol
                                                           :r-symbol r-symbol
                                                           :cause (-> e Throwable->map :cause)}])))]))
       (filter second)
       (into {})))

(defn find-or-create-ns [ns-symbol]
  (or (find-ns ns-symbol)
      (create-ns ns-symbol)))

(def ^:private empty-symbol (symbol ""))

(defn- r-object->arglists-raw
  "Fetch function aruments using `formals` and produce `:arglists` meta tag value and conforming clojisr R function call style."
  [{:keys [code class]}]
  (when (= class ["function"])
    (let [sess (session/fetch-or-make nil)
          args (->> sess
                    (evl/r (format "formals(%s)" code))
                    (using-sessions/r->java)
                    (prot/java->clj sess))
          {:keys [obl opt]} (reduce-kv (fn [m k v]
                                         (let [selector (if (and (= empty-symbol v)
                                                                 (not (seq (:obl m)))) :obl :opt)]
                                           (update m selector conj (symbol k))))
                                       {:obl [] :opt []}
                                       args)]
      (cond
        (and (seq obl)
             (seq opt)) (list (conj obl '& {:keys opt}))
        (seq obl) (list obl)
        (seq opt) (list ['& {:keys opt}])
        :else '([])))))

(def r-object->arglists (memoize r-object->arglists-raw))

(defn r-symbol->clj-symbol [r-symbol r-object]
  (let [clj-symbol (clojurize-r-symbol r-symbol)]
    (if-let [arglists (r-object->arglists r-object)]
      (vary-meta clj-symbol assoc :arglists arglists)
      clj-symbol)))

(defn add-to-ns [ns-symbol r-symbol r-object]
  (intern ns-symbol
          (r-symbol->clj-symbol r-symbol r-object)
          r-object))

(defn symbols->add-to-ns [ns-symbol r-symbols]
  (doseq [[r-symbol r-object] r-symbols]
    (add-to-ns ns-symbol r-symbol r-object)))

(defn require-r-package [[package-symbol & {:keys [as refer]}]]
  (try
    (let [session (session/fetch-or-make nil)]
      (evl/eval-form ['library
                      package-symbol]
                     session))
    (let [r-ns-symbol (->> package-symbol
                           (str "r.")
                           symbol)
          r-symbols (all-r-symbols-map package-symbol)]

      ;; r.package namespace
      (find-or-create-ns r-ns-symbol)
      (symbols->add-to-ns r-ns-symbol r-symbols)

      ;; alias namespace
      (when as
        (find-or-create-ns as)
        (symbols->add-to-ns as r-symbols))

      ;; inject symbol into current namespace
      (when refer
        (let [this-ns-symbol (-> *ns* str symbol)]
          (symbols->add-to-ns this-ns-symbol
                              (if (= refer :all)
                                r-symbols
                                (select-keys r-symbols refer))))))
    (catch Exception e
      (log/warn (format "Failed to load %s package. Please ensure it's installed." package-symbol)))))

(defn require-r [& packages]
  (run! require-r-package packages))
