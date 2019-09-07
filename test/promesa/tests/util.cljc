(ns promesa.tests.util
  (:require [promesa.core :as p]
            #?(:cljs [cljs.reader :refer [read-string]])))

(defn promise-ok
  [sleep value]
  (p/promise (fn [resolve reject]
               (p/schedule sleep #(resolve value)))))

(defn promise-ko
  [sleep value]
  (p/promise (fn [_ reject]
               (let [error #?(:clj (if (instance? Throwable value) value (ex-info (pr-str value) {}))
                              :cljs (if (instance? js/Error value) value (ex-info (pr-str value) {})))]
                 (p/schedule sleep #(reject error))))))

(defn normalize-to-value
  [p]
  (p/catch p (fn [exc]
               (read-string (ex-message exc)))))
