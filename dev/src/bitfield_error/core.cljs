(ns bitfield-error.core
  (:require [cognitect.transit :as t]
            [promesa.core :as p]))

(enable-console-print!)

(defn encode
  [data]
  (let [w (t/writer :json)]
    (t/write w data)))

(defn ^:export main
  []
  (println "encoded" (encode {:foo "bar"})))
