(ns user
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as r]
   [clojure.walk :refer [macroexpand-all]]
   [clojure.core.async :as a]
   [criterium.core :refer [quick-bench bench with-progress-reporting]]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.bulkhead :as pbh]
   [promesa.exec.csp :as sp]
   [promesa.protocols :as pt]
   [promesa.util :as pu])
  (:import
   java.util.concurrent.CompletableFuture
   java.util.concurrent.CompletionStage
   java.util.function.Function
   java.util.concurrent.atomic.AtomicLong))

(defmacro run-quick-bench
  [& exprs]
  `(with-progress-reporting (quick-bench (do ~@exprs) :verbose)))

(defmacro run-quick-bench'
  [& exprs]
  `(quick-bench (do ~@exprs)))

(defmacro run-bench
  [& exprs]
  `(with-progress-reporting (bench (do ~@exprs) :verbose)))

(defmacro run-bench'
  [& exprs]
  `(bench (do ~@exprs)))

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

(defn current-thread
  []
  (.getName (Thread/currentThread)))

(defn dbg
  [label v]
  (locking dbg
    (println "DBG[" label "/" (current-thread) "]:" (pr-str v))))
