(ns promesa.core-tests
  (:require [cljs.test :as t]
            [promesa.core :as p]
            [cats.core :as m]))

(t/deftest promise-constructor
  ;; Constructor with value
  (let [p1 (p/promise 1)]
    (t/is (p/fulfilled? p1))
    (t/is (= (m/extract p1) 1)))

  ;; Constructor with callable
  (let [p1 (p/promise (fn [resolve] (resolve 1)))]
    (t/is (p/fulfilled? p1))
    (t/is (= (m/extract p1) 1)))

  (let [p1 (p/promise (ex-info "foo" 1))]
    (t/is (p/rejected? p1))
    (p/catch p1 (fn [x] x)))

  (let [p1 (p/resolved 1)]
    (t/is (p/fulfilled? p1)))

  (let [p1 (p/rejected 1)]
    (t/is (p/rejected? p1))
    (p/catch p1 (fn [x] x)))

  (let [p1 (p/promise (fn [_ _] 1))]
    (t/is (p/pending? p1)))
)

(t/deftest promise-async-constructor-async
  (t/async done
    (let [p1 (p/promise (fn [resolve]
                          (js/setTimeout (partial resolve 1) 0)))]
      (t/is (p/pending? p1))
      (p/then p1 (fn [v]
                   (t/is (= (m/extract p1)  1))
                   (done))))))

(t/deftest spread-all-chain
  (let [p1 (-> (p/all [(p/promise 1) (p/promise 2)])
               (p/spread (fn [x y]
                           (t/is (= x 1))
                           (t/is (= y 2))
                           (+ x y))))]
    (p/then p1 (fn [v]
                 (t/is (= v 3))))))

(t/deftest any-with-delay-chain
  (t/async done
    (let [p1 (p/any [(p/delay 300 1) (p/delay 200 2)])]
      (p/then p1 (fn [v]
                   (t/is (= v 2))
                   (done))))))

(t/deftest any-with-timeout-catch
  (t/async done
    (let [p1 (-> (p/delay 300 1)
                 (p/timeout 200))]
      (p/catch p1 :timeout (fn [v]
                             (t/is (instance? js/Error v))
                             (t/is (p/rejected? p1))
                             (done))))))

(t/deftest timeout-with-finally
  (t/async done
    (let [p1 (-> (p/delay 300 1)
                 (p/timeout 200 1))]
      (-> p1
          (p/catch #(t/is (instance? js/Error %)))
          (p/finally (fn [v]
                       (t/is (= v nil))
                       (done)))))))

(t/deftest rejected-promise
  (t/async done
    (let [p1 (p/rejected 1)]
      (t/is (p/rejected? p1))
      (p/catch p1 (fn [v]
                    (t/is (= v 1))
                    (done))))))

(t/deftest promise-as-functor
  (t/async done
    (let [rp (m/fmap inc (p/promise 2))]
      (p/then rp (fn [v]
                   (t/is (= v 3))
                   (done))))))

(t/deftest promise-as-monad
  (t/async done
    (let [p1 (m/>>= (p/promise 2) (fn [v] (m/return (inc v))))]
      (p/then p1 (fn [v]
                   (t/is (= v 3))
                   (done))))))


(t/deftest promisify
  (t/async done
    (let [func1 (fn [x cb] (cb (inc x)))
          func2 (p/promisify func1)
          p1 (func2 2)]
      (p/then p1 (fn [x]
                   (t/is (= x 3))
                   (done))))))
