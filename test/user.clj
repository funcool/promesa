(ns user
  (:require [clojure.tools.namespace.repl :as r]
            [clojure.walk :refer [macroexpand-all]]
            [clojure.pprint :refer [pprint]]
            [clojure.test :as test]))

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


