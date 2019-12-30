(ns clojuress.using-sessions
  (:require [tech.resource :as resource]
            [clojuress.protocols :as prot]
            [clojuress.objects-memory :as mem]
            [clojuress.util :as util]
            [clojuress.robject :refer [->RObject]])
  (:import clojuress.robject.RObject))

(defn eval-r [code session]
  (let [obj-name (util/rand-name)
        returned (->> code
                      (mem/code-that-remembers obj-name)
                      (prot/eval-r->java session))]
    (assert (->> returned
                 (prot/java->clj session)
                 (= ["ok"])))
    (-> (->RObject obj-name session code)
        (resource/track
         #(do (println [:releasing obj-name])
              (mem/forget obj-name session))
         :gc))))

(comment
  (let [s (clojuress.using-sessions/fetch-or-make-and-init {})]
    (init-memory s)
    (eval-r "1+2" s)))

(defn java->r-specified-type [java-object type session]
  (prot/java->specified-type session java-object type))


(defn r-function-on-obj [{:keys [session] :as r-object}
                         function-name return-type]
  (->> r-object
       :object-name
       mem/object-name->memory-place
       (format "%s(%s)" function-name)
       (prot/eval-r->java session)
       (#(prot/java->specified-type session % return-type))))

(comment
  (let [s (clojuress.using-sessions/fetch-or-make-and-init {})]
    (init-memory s)
    [(r-function-on-obj
     (eval-r "1+2" s)
     "sin"
     :doubles)
    (r-function-on-obj
     (eval-r "1+2" s)
     "sin"
     :strings)]))

(defn r->java [{:keys [session] :as r-object}]
  (->> r-object
       :object-name
       mem/object-name->memory-place
       (prot/get-r->java session)))

(comment
  (let [s (clojuress.using-sessions/fetch-or-make-and-init {})]
    (init-memory s)
    (r->java (eval-r "1+2" s))))

(defn java->r [java-object session]
  (if (instance? RObject java-object)
    java-object
    (let [obj-name (util/rand-name)]
      (prot/java->r-set session
                        (mem/object-name->memory-place
                         obj-name)
                        java-object)
      (->RObject obj-name session nil))))

(comment
  (let [s (clojuress.using-sessions/fetch-or-make-and-init {})]
    (init-memory s)
    (-> (eval-r "1+2" s)
        r->java
        (java->r s))))
