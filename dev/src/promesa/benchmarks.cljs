(ns promesa.benchmarks
  (:require [promesa.core :as p])
  (:import goog.Promise))

(enable-console-print!)

(defn benchmark-promesa-promise
  [ops]
  (let [items (vec (range 1 ops))
        _     (js/console.time "promesa")
        pm    (reduce (fn [acc item]
                        (p/then acc (fn [n]
                                      (p/then (p/all (mapv p/promise (range n)))
                                              (constantly item)))))
                      (p/resolved 0)
                      items)]
    (p/finally pm (fn [result]
                    (js/console.timeEnd "promesa")))))

(defn benchmark-promesa-raw-promise
  [ops]
  (let [items (vec (range 1 ops))
        _     (js/console.time "promesa-raw")
        pm    (reduce (fn [acc item]
                        (.then acc (fn [n]
                                     (.then (.all p/Promise (into-array (range n)))
                                            (constantly item)))))
                      (.resolve p/Promise 0)
                      items)]
    (p/finally pm (fn [result]
                    (js/console.timeEnd "promesa-raw")))))

(defn benchmark-es6-promise
  [ops]
  (let [items (vec (range 1 ops))
        _     (js/console.time "es6")
        pm    (reduce (fn [acc item]
                        (.then acc (fn [n]
                                     (.then (.all js/Promise (into-array (range n)))
                                            (constantly item)))))
                      (js/Promise.resolve 0)
                      items)]
    (.then pm (fn [result]
                    (js/console.timeEnd "es6")))))

(defn benchmark-goog-promise
  [ops]
  (let [items (vec (range 1 ops))
        _     (js/console.time "goog")
        pm    (reduce (fn [acc item]
                        (.then acc (fn [n]
                                     (.then (.all goog.Promise (into-array (range n)))
                                            (constantly item)))))
                      (.resolve goog.Promise 0)
                      items)]
    (.then pm (fn [result]
                    (js/console.timeEnd "goog")))))

(defn main
  [& args]
  (->> (p/promise nil)
       (p/mapcat (fn [_]
                   (println "lib=promesa number=500")
                   (benchmark-promesa-promise 500)))
       (p/mapcat (fn [_]
                   (println "lib=promesa-raw number=500")
                   (benchmark-promesa-raw-promise 500)))
       (p/mapcat (fn [_]
                   (println "lib=es6 number=500")
                   (benchmark-es6-promise 500)))
       (p/mapcat (fn [_]
                   (println "lib=goog number=500")
                   (benchmark-goog-promise 500)))
       (p/map (fn [_]
                 (println "end")))))

;; (set! *main-cli-fn* main)
(main)
