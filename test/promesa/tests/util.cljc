(ns promesa.tests.util
  (:require [promesa.core :as p]))

(defn future-ok
  [sleep value]
  (p/promise (fn [resolve reject]
               (p/schedule sleep #(resolve value)))))

(defn future-fail
  [sleep value]
  (p/promise (fn [_ reject]
               (p/schedule sleep #(reject value)))))
