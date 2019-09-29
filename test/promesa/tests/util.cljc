(ns promesa.tests.util
  (:require [promesa.core :as p]
            [promesa.exec :as e]
            #?(:cljs [cljs.reader :refer [read-string]])))

(defn promise-ok
  [sleep value]
  (p/create (fn [resolve reject]
               (e/schedule! sleep #(resolve value)))))

(defn promise-ko
  [sleep value]
  (p/create (fn [_ reject]
              (let [error #?(:clj (if (instance? Throwable value) value (ex-info (pr-str value) {}))
                             :cljs (if (instance? js/Error value) value (ex-info (pr-str value) {})))]
                (e/schedule! sleep #(reject error))))))

(defn normalize-to-value
  [p]
  (p/catch p (fn [exc]
               (read-string (ex-message exc)))))
