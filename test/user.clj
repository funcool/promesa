(ns user
  (:require [clojure.tools.namespace.repl :as r]
            [criterium.core :refer [quick-bench with-progress-reporting]]
            [clojure.walk :refer [macroexpand-all]]
            [clojure.pprint :refer [pprint]]
            [clojure.test :as test]
            [promesa.core :as p]
            [promesa.protocols :as pt]))

(defmacro bench
  [& exprs]
  `(with-progress-reporting (quick-bench (do ~@exprs) :verbose)))

(defmacro bench'
  [& exprs]
  `(quick-bench (do ~@exprs)))

(defn- run-test
  ([] (run-test #"^promesa.tests.*"))
  ([o]
   (r/refresh)
   (cond
     (instance? java.util.regex.Pattern o)
     (test/run-all-tests o)

     (symbol? o)
     (if-let [sns (namespace o)]
       (do (require (symbol sns))
           (test/test-vars [(resolve o)]))
       (test/test-ns o)))))

(defn -main
  [& args]
  (require 'promesa.tests.test-core)
  (let [{:keys [fail]} (run-test)]
    (if (pos? fail)
      (System/exit fail)
      (System/exit 0))))

;; (def normal-var
;;   (delay 1))

;; (def ^:dynamic *dynamic-var*
;;   (delay 1))

;; (defn bench-fn1
;;   []
;;   (deref normal-var))

;; (defn bench-fn2
;;   []
;;   (deref *dynamic-var*))


