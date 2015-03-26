(ns promesa.core-tests
  (:require [cljs-testrunners.node :as node]
            [cljs.test :as t]
            [cats.protocols :as pt]
            [cats.monad.promise :as p]
            [cats.core :as m]))

(defn error-promise
  [x]
  (p/promise
   (fn [_ reject]
     (reject (ex-info "error" x)))))

(t/deftest promise-constructor-normal
  (let [p1 (p/promise 1)]
    (t/is (= (m/extract p1) 1))))

(t/deftest promise-constructor-function
  (t/async done
    (let [p1 (p/promise (fn [resolve] (resolve 1)))]
      (t/is (= (m/extract p1)  1))
      (done))))


(t/deftest promise-constructor-async
  (t/async done
    (let [p1 (p/promise (fn [resolve]
                          (js/setTimeout (partial resolve 1) 0)))]
      (p/then p1 (fn [v]
                   (t/is (= (m/extract p1)  1))
                   (done))))))

(t/deftest promise-rejected
  (t/async done
    (let [p1 (p/promise (fn [_ reject] (reject 1)))]
      (t/is (p/rejected? p1))
      (.catch p1 (fn [v]
                   (t/is (= v 1))
                   (done))))))

(t/deftest promise-as-functor
  (t/async done
    (let [rp (m/fmap inc (p/promise 2))]
      (.then rp (fn [v]
                  (t/is (= v 3))
                  (done))))))

(t/deftest promise-as-monad
  (t/async done
    (let [p1 (m/>>= (p/promise 2) (fn [v] (m/return (inc v))))]
      (.then p1 (fn [v]
                  (t/is (= v 3))
                  (done))))))


(defn main [] (node/run-tests))
(set! *main-cli-fn* main)

