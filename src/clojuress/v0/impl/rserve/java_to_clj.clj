(ns clojuress.v0.impl.rserve.java-to-clj
  (:require [clojure.pprint :as pp]
            [clojure.walk :as walk]
            [com.rpl.specter :as specter]
            [tech.ml.dataset :as dataset]
            [tech.v2.datatype :as dtype]
            [tech.v2.datatype.protocols :as dtype-prot :refer [->array-copy]]
            [clojure.math.combinatorics :refer [cartesian-product]])
  (:import (org.rosuda.REngine REXP REXPGenericVector REXPString REXPLogical REXPFactor REXPSymbol REXPDouble REXPInteger REXPLanguage RList REXPNull)
           (java.util Map List Collection)
           (clojure.lang Named)))

(defn java->specified-type
  [^REXP java-obj typ]
  (case typ
    :ints    (.asIntegers ^REXP java-obj)
    :doubles (.asDoubles ^REXP java-obj)
    :strings (.asStrings ^REXP java-obj)))

(defn java->naive-clj
  [^REXP java-obj]
  (->> {:attr  (->> java-obj
                    (._attr)
                    (.asNativeJavaObject))
        :value (->> java-obj
                    (.asNativeJavaObject))}
       (walk/prewalk (fn [v]
                       (if (instance? Map v)
                         (->> v
                              (into {})
                              (specter/transform [specter/MAP-KEYS]
                                                 keyword))
                         v)))))


(extend-type REXPDouble
  dtype-prot/PToArray
  (->array-copy [item]
    ;; NA maps to REXPDouble/NA.
    (.asDoubles item)))

(extend-type REXPInteger
  dtype-prot/PToArray
  (->array-copy [item]
    ;; NA has to be handled explicitly.
    (let [n      (.length item)
          values (.asIntegers item)
          na?    (.isNA item)]
      (if (every? false? na?)
        values
        (let [target (make-array Double (.length item))]
          (dotimes [i n]
            (aset target i
                  (if (aget na? i)
                    REXPDouble/NA
                    (double (aget values i)))))
          target)))))

(extend-type REXPString
  dtype-prot/PToArray
  (->array-copy [item]
    ;; NA maps to nil.
    (.asStrings item)))

(extend-type REXPLogical
  dtype-prot/PToArray
  (->array-copy [item]
    ;; NA,TRUE,FALSE have to be handled explicitly.
    (let [n      (.length item)
          na? (.isNA item)
          true? (.isTRUE item)
          target (make-array Boolean (.length item))]
      (dotimes [i n]
        (aset target i
              (or (aget true? i)
                  (if (aget na? i)
                    nil
                    false))))
      target)))


(defn java-factor? [^REXP java-obj]
  (-> java-obj
      (.getAttribute "class")
      ->array-copy
      (->> (some (partial = "factor")))))

(defn java-factor->clj-info [^REXPFactor java-factor]
  (when (not (java-factor? java-factor))
    (throw (ex-info "Expected a factor, got something else." {:class (-> java-factor
                                                                         (.getAttribute "class")
                                                                         ->array-copy
                                                                         vec)})))
  (let [levels  (-> java-factor
                    (.getAttribute "levels")
                    ->array-copy)
        indices (-> java-factor
                    (.asFactor)
                    (.asIntegers))]
    {:levels  levels
     :indices indices}))

(defn java-data-frame? [^REXP java-obj]
  (some-> java-obj
          (.getAttribute "class")
          ->array-copy
          (->> (some (partial = "data.frame")))))

(defn java-data-frame->clj-dataset [^REXP java-df]
  (let [^RList columns-named-list (.asList java-df)]
    (->> columns-named-list
         (map ->array-copy)
         (map vector (.names columns-named-list))
         dataset/name-values-seq->dataset)))


(defprotocol Clojable
  (-java->clj [this]))

(defn java->clj
  [java-obj]
  (some-> java-obj
          -java->clj))


(extend-type Object
  Clojable
  (-java->clj [this] this))

(extend-type REXPDouble
  Clojable
  (-java->clj [java-obj]
    (-> java-obj ->array-copy vec)))

(defn table? [^REXP java-obj]
  (some-> java-obj
          (.getAttribute "class")
          -java->clj
          (= ["table"])))

(defn table->clj [^REXPInteger java-obj]
  (let [dimnames (-> java-obj
                     (.getAttribute "dimnames")
                     (.asList)
                     (->> (map -java->clj)))
        counts   (->array-copy java-obj)]
    (-> dimnames
         (->> reverse
              (apply cartesian-product)
              (map vec))
         (interleave counts)
         (->> (apply array-map)))))

(extend-type REXPInteger
  Clojable
  (-java->clj [java-obj]
    (if (table? java-obj)
      (table->clj java-obj)
      (-> java-obj ->array-copy vec))))

(extend-type REXPString
  Clojable
  (-java->clj [java-obj]
    (-> java-obj ->array-copy vec)))

(extend-type REXPLogical
  Clojable
  (-java->clj [java-obj]
    (-> java-obj ->array-copy vec)))

(extend-type REXPFactor
  Clojable
  (-java->clj [java-factor]
    (let [{:keys [levels indices]}
          (java-factor->clj-info
           java-factor)]
      (mapv (fn [i]
              (when-not
                  (= i Integer/MIN_VALUE) ; which means a missing value
                (->> i
                     dec
                     (aget levels))))
            indices))))


(extend-type REXPGenericVector
  Clojable
  (-java->clj [java-obj]
    (let [names  (some-> java-obj
                          (.getAttribute "names")
                          ->array-copy
                          (->> (map keyword)))
          values (->> java-obj
                      (.asList)
                      ;; Convert list elements recursively.
                      (map java->clj))]
      (if (nil? names)
        ;; unnamed list
        (vec values)
        ;; else -- assume all names are available
        (do (if (some (partial = "") names)
              (throw (ex-info "Partially named lists are not supported yet. " {:names names})))
            (let [list-as-map
                  (->> values
                       (interleave names)
                       (apply array-map))]
              (if (java-data-frame? java-obj)
                ;; a data  frame
                (dataset/name-values-seq->dataset list-as-map)
                ;; else -- assume a regular list
                list-as-map)))))))

(extend-type REXPSymbol
  Clojable
  (-java->clj [java-obj]
    (-> java-obj
        (.asString)
        symbol)))

(extend-type REXPLanguage
  Clojable
  (-java->clj [java-obj]
    (->> java-obj
         (.asList)
         (mapv java->clj))))


