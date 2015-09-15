(ns promesa.core-tests
  (:require [cljs.test :as t]
            [cats.core :as m]
            [promesa.core :as p]))

(enable-console-print!)

(t/deftest promise-from-value
  (let [p1 (p/promise 1)]
    (t/is (p/fulfilled? p1))
    (t/is (= (m/extract p1) 1))))

(t/deftest promise-from-factory
  (let [p1 (p/promise (fn [resolve] (resolve 1)))]
    (t/is (p/fulfilled? p1))
    (t/is (= (m/extract p1) 1))))

(t/deftest promise-async-factory
  (t/async done
    (let [p1 (p/promise (fn [resolve]
                          (js/setTimeout (partial resolve 1) 0)))]
      (t/is (p/pending? p1))
      (p/then p1 (fn [v]
                   (t/is (= (m/extract p1)  1))
                   (done))))))

(t/deftest promise-from-exception
  (let [p1 (p/promise (ex-info "foo" 1))]
    (t/is (p/rejected? p1))
    (p/catch p1 (fn [x] x))))

(t/deftest promise-rejected
  (let [p1 (p/rejected 1)]
    (t/is (p/rejected? p1))
    (p/catch p1 (fn [x] x))))

(t/deftest promise-from-promise
  (let [p1 (p/promise 1)
        p2 (p/promise p1)]
    (t/is (identical? p1 p2))))

(t/deftest syncrhonize-two-promises
  (t/async done
    (let [p1 (p/all [(p/promise 1) (p/promise 2)])]
      (p/then p1 (fn [[x y]]
                   (t/is (= x 1))
                   (t/is (= y 2))
                   (done))))))

(t/deftest arbitrary-choice-with-some
  (t/async done
    (let [p1 (p/some 2 [(future 200 1)
                        (future 200 2)
                        (future 300 3)])]
      (p/then p1 (fn [[x y]]
                   (t/is (= x 1))
                   (t/is (= y 2))
                   (done))))))

(t/deftest arbitrary-choice-with-any
  (t/async done
    (let [p1 (p/any [(p/delay 300 1) (p/delay 200 2)])]
      (p/then p1 (fn [v]
                   (t/is (= v 2))
                   (done))))))

(t/deftest catch-timeout
  (t/async done
    (let [p1 (-> (p/delay 300 1)
                 (p/timeout 200))]
      (p/catch p1 :timeout (fn [v]
                             (t/is (instance? js/Error v))
                             (t/is (p/rejected? p1))
                             (done))))))

(t/deftest reject-promise-in-chain
  (t/async done
    (let [p1 (p/promise 1)
          p2 (p/then p1 (fn [v]
                          (throw (ex-info "foobar" {:msg "foo"}))))
          p3 (p/catch p2 (fn [e]
                           (:msg (ex-data e))))]
      (p/then p3 (fn [v]
                   (t/is (= v "foo"))
                   (done))))))


(defn future
  [sleep value]
  (p/promise (fn [resolve]
               (js/setTimeout #(resolve value) sleep))))

(t/deftest chaining-using-then
  (t/async done
    (let [p1 (future 200 2)
          p2 (p/then p1 inc)
          p3 (p/then p2 inc)]
      (p/then p3 (fn [v]
                   (t/is (= v 4))
                   (done))))))

(t/deftest chaining-using-chain
  (t/async done
    (let [p1 (future 200 2)
          p2 (p/chain p1 inc inc inc)]
      (p/then p2 (fn [v]
                   (t/is (= v 5))
                   (done))))))

(t/deftest chaining-using->>=
  (t/async done
    (let [p1 (future 200 2)
          p2 (m/>>= p1 inc inc inc)]
      (p/then p2 (fn [v]
                   (t/is (= v 5))
                   (done))))))

(t/deftest promise-as-functor
  (t/async done
    (let [rp (m/fmap inc (p/promise 2))]
      (p/then rp (fn [v]
                   (t/is (= v 3))
                   (done))))))

(t/deftest promise-as-applicative
  (t/async done
    (let [rp (m/fapply (p/resolved inc) (p/promise 2))]
      (p/then rp (fn [v]
                   (t/is (= v 3))
                   (done))))))

(t/deftest promise-as-monad
  (t/async done
    (let [p1 (m/>>= (p/promise 2) (fn [v] (m/return (inc v))))]
      (p/then p1 (fn [v]
                   (t/is (= v 3))
                   (done))))))

(t/deftest first-monad-law-left-identity
  (t/async done
    (let [p1 (m/pure p/promise-context 4)
          p2 (m/pure p/promise-context 4)
          vl  (m/>>= p2 #(m/pure p/promise-context %))]
      (p/then (p/all [p1 vl])
              (fn [[v1 v2]]
                (t/is (= v1 v2))
                (done))))))

(t/deftest second-monad-law-right-identity
  (t/async done
    (let [p1 (p/promise 3)
          rs  (m/>>= (p/promise 3) m/return)]
      (p/then (p/all [p1 rs])
              (fn [[v1 v2]]
                (t/is (= v1 v2))
                (done))))))


(t/deftest third-monad-law-associativity
  (t/async done
    (let [rs1 (m/>>= (m/mlet [x (p/promise 2)
                              y (p/promise (inc x))]
                       (m/return y))
                     (fn [y] (p/promise (inc y))))
          rs2 (m/>>= (p/promise 2)
                     (fn [x] (m/>>= (p/promise (inc x))
                                    (fn [y] (p/promise (inc y))))))]
      (p/then (p/all [rs1 rs2])
              (fn [[v1 v2]]
                (t/is (= v1 v2))
                (done))))))

(t/deftest promisify
  (t/async done
    (let [func1 (fn [x cb] (cb (inc x)))
          func2 (p/promisify func1)
          p1 (func2 2)]
      (p/then p1 (fn [x]
                   (t/is (= x 3))
                   (done))))))


(set! *main-cli-fn* #(t/run-tests))

(defmethod t/report [:cljs.test/default :end-run-tests]
  [m]
  (if (t/successful? m)
    (set! (.-exitCode js/process) 0)
    (set! (.-exitCode js/process) 1)))
