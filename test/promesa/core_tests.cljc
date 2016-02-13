(ns promesa.core-tests
  (:require #?(:cljs [cljs.test :as t]
               :clj [clojure.test :as t])
            [cats.core :as m]
            [promesa.monad :as pm]
            [promesa.core :as p :include-macros true]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn future-ok
  [sleep value]
  (p/promise (fn [resolve reject]
               (p/schedule sleep #(resolve value)))))

(defn future-fail
  [sleep value]
  (p/promise (fn [_ reject]
               (p/schedule sleep #(reject value)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core Interface Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest promise-from-value
  (let [p1 (p/promise 1)]
    (t/is (p/done? p1))
    (t/is (= (p/extract p1) 1))))

(t/deftest promise-from-factory
  (let [p1 (p/promise (fn [resolve _] (resolve 1)))]
    #?(:clj (deref p1))
    (t/is (p/done? p1))
    (t/is (= (p/extract p1) 1))))

(t/deftest promise-async-factory
  #?(:cljs
     (t/async done
       (let [p1 (p/promise (fn [resolve reject]
                             (p/schedule 100 #(resolve 1))))]
         (t/is (p/pending? p1))
         (p/then p1 (fn [v]
                      (t/is (= v 1))
                      (done)))))
     :clj
     (let [p1 (p/promise (fn [resolve reject]
                           (p/schedule 100 #(resolve 1))))]
       (t/is (p/pending? p1))
       (t/is (= @p1 1)))))

(t/deftest promise-from-exception
  (let [e (ex-info "foo" {})
        p1 (p/promise e)]
    (t/is (p/rejected? p1))
    (p/catch p1 (fn [x]
                  (t/is (= x e))))))

(t/deftest promise-rejected
  (let [e1 (ex-info "foobar" {})
        p1 (p/rejected e1)
        p2 (p/catch p1 (constantly nil))]
    (t/is (p/rejected? p1))
    (t/is (= e1 (p/extract p1)))))

(t/deftest promise-from-promise
  (let [p1 (p/promise 1)
        p2 (p/promise p1)]
    (t/is (identical? p1 p2))))

(t/deftest syncrhonize-two-promises
  #?(:cljs
     (t/async done
       (let [p1 (p/all [(p/promise 1) (p/promise 2)])]
         (p/then p1 (fn [[x y]]
                      (t/is (= x 1))
                      (t/is (= y 2))
                      (done)))))
     :clj
     (let [p1 (p/all [(p/promise 1) (p/promise 2)])
           [x y] @p1]
       (t/is (= x 1))
       (t/is (= y 2)))))

(t/deftest arbitrary-choice-with-any
  #?(:cljs
     (t/async done
       (let [p1 (p/any [(p/delay 300 1) (p/delay 200 2)])]
         (p/then p1 (fn [v]
                      (t/is (= v 2))
                      (done)))))
     :clj
     (let [p1 (p/any [(p/delay 300 1) (p/delay 200 2)])]
       (t/is (= @p1 2)))))

(t/deftest reject-promise-in-chain
  #?(:cljs
     (t/async done
       (let [p1 (p/promise 1)
             p2 (p/then p1 (fn [v]
                             (throw (ex-info "foobar" {:msg "foo"}))))
             p3 (p/catch p2 (fn [e]
                              (:msg (ex-data e))))]
         (p/then p3 (fn [v]
                      (t/is (= v "foo"))
                      (done)))))
     :clj
     (let [p1 (p/promise 1)
           p2 (p/then p1 (fn [v]
                           (throw (ex-info "foobar" {:msg "foo"}))))
           p3 (p/catch p2 (fn [e]
                            (:msg (ex-data e))))]
       (t/is (= @p3 "foo")))))

(t/deftest chaining-using-then
  #?(:cljs
     (t/async done
       (let [p1 (future-ok 200 2)
             p2 (p/then p1 inc)
             p3 (p/then p2 inc)]
         (p/then p3 (fn [v]
                      (t/is (= v 4))
                      (done)))))
     :clj
     (let [p1 (future-ok 200 2)
           p2 (p/then p1 inc)
           p3 (p/then p2 inc)]
       (t/is @p3 4))))

(t/deftest chaining-using-chain
  #?(:cljs
     (t/async done
       (let [p1 (future-ok 200 2)
             p2 (p/chain p1 inc inc inc)]
         (p/then p2 (fn [v]
                      (t/is (= v 5))
                      (done)))))
     :clj
     (let [p1 (future-ok 200 2)
           p2 (p/chain p1 inc inc inc)]
       (t/is (= @p2 5)))))

(t/deftest branching-using-branch-1
  #?(:cljs
     (t/async done
       (let [p1 (future-ok 200 2)
             p2 (p/branch p1 #(inc %) (constantly nil))]
         (p/then p2 #(do
                       (t/is (= % 3))
                       (done)))))
     :clj
     (let [p1 (future-ok 200 2)
           p2 (p/branch p1 #(inc %) (constantly nil))]
       (t/is (= @p2 3)))))

(t/deftest branching-using-branch-2
  #?(:cljs
     (t/async done
       (let [e (ex-info "foobar" {})
             p1 (future-fail 200 e)
             p2 (p/branch p1 (constantly nil) identity)]
         (p/then p2 #(do
                       (t/is (= % e))
                       (done)))))
     :clj
     (let [e (ex-info "foobar" {})
           p1 (future-fail 200 e)
           p2 (p/branch p1 (constantly nil) (constantly 1))]
       (t/is (= @p2 1)))))

(t/deftest promisify
  #?(:cljs
     (t/async done
       (let [func1 (fn [x cb] (cb (inc x)))
             func2 (p/promisify func1)
             p1 (func2 2)]
         (p/then p1 (fn [x]
                      (t/is (= x 3))
                      (done)))))
     :clj
     (let [func1 (fn [x cb] (cb (inc x)))
           func2 (p/promisify func1)
           p1 (func2 2)]
       @(p/then p1 (fn [x]
                     (t/is (= x 3)))))))

(t/deftest then-with-ifn
  (let [p (-> {:x ::value} p/promise (p/then :x))]
    #?(:cljs
       (t/async done
         (p/then p (fn [x]
                     (t/is (= x ::value))
                     (done))))
       :clj
       (t/is (= @p ::value)))))

(defmulti unwrap-caught identity)
(defmethod unwrap-caught :default [x]
  (-> x ex-data :x))

(t/deftest catch-with-ifn
  (let [p (-> (p/promise
               (fn [_ reject] (reject (ex-info "foobar" {:x ::value}))))
              (p/catch unwrap-caught))]
    #?(:cljs
       (t/async done
         (p/then p (fn [x]
                     (t/is (= x ::value))
                     (done))))
       :clj
       (t/is (= @p ::value)))))


#?(:clj
   (t/deftest async-let
     (let [result (p/alet [a (p/await (future-ok 100 1))
                           b 2
                           c 3
                           d (p/await (future-ok 200 4))]
                    (+ a b c d))]
       (t/is (= @result 10)))))

#?(:cljs
   (t/deftest async-let
     (t/async done
       (let [result (p/alet [a (p/await (future-ok 100 1))
                             b 2
                             c 3
                             d (p/await (future-ok 200 4))]
                      (+ a b c d))]
         (p/then result (fn [result]
                          (t/is (= result 10))
                          (done)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cats Integration Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest bind-with-ifn
  (let [p (-> (p/promise {:x ::value})
              (m/>>= :x))]
    #?(:cljs
       (t/async done
         (p/then p (fn [x]
                     (t/is (= x ::value))
                     (done))))
       :clj
       (t/is (= @p ::value)))))

#?(:cljs
   (t/deftest extract-from-rejected-promise
     (let [p1 (p/rejected 42)]
       (t/is (p/rejected? p1))
       (t/is (= (p/extract p1) 42)))))

(t/deftest chaining-using-bind
  #?(:cljs
     (t/async done
       (let [p1 (future-ok 200 2)
             p2 (m/>>= p1 inc inc inc)]
         (p/then p2 (fn [v]
                      (t/is (= v 5))
                      (done)))))
     :clj
     (let [p1 (future-ok 200 2)
           p2 (m/>>= p1 inc inc inc)]
       (t/is (= @p2 5)))))

(t/deftest promise-as-functor
  #?(:cljs
     (t/async done
       (let [rp (m/fmap inc (p/promise 2))]
         (p/then rp (fn [v]
                      (t/is (= v 3))
                      (done)))))

     :clj
     (let [rp (m/fmap inc (p/promise 2))]
       @(p/then rp (fn [v]
                     (t/is (= v 3)))))))

(t/deftest promise-as-bifunctor
  #?(:cljs
     (t/async done
       (let [rp (m/bimap #(ex-info "Oh no" {}) inc (p/promise 2))]
         (p/then rp (fn [v]
                      (t/is (= v 3))
                      (done)))))

     :clj
     (let [rp (m/bimap #(ex-info "Oh no" {}) inc (p/promise 2))]
       @(p/then rp (fn [v]
                     (t/is (= v 3)))))))

(t/deftest promise-as-applicative
  #?(:cljs
     (t/async done
       (let [rp (m/fapply (p/resolved inc) (p/promise 2))]
         (p/then rp (fn [v]
                      (t/is (= v 3))
                      (done)))))
     :clj
     (let [rp (m/fapply (p/resolved inc) (p/promise 2))]
       @(p/then rp (fn [v]
                     (t/is (= v 3)))))))

(t/deftest promise-as-monad
  #?(:cljs
     (t/async done
       (let [p1 (m/>>= (p/promise 2) (fn [v] (m/return (inc v))))]
         (p/then p1 (fn [v]
                      (t/is (= v 3))
                      (done)))))
     :clj
     (let [p1 (m/>>= (p/promise 2) (fn [v] (m/return (inc v))))]
       @(p/then p1 (fn [v]
                     (t/is (= v 3)))))))

(t/deftest first-monad-law-left-identity
  #?(:cljs
     (t/async done
       (let [p1 (m/pure pm/promise-context 4)
             p2 (m/pure pm/promise-context 4)
             vl  (m/>>= p2 #(m/pure pm/promise-context %))]
         (p/then (p/all [p1 vl])
                 (fn [[v1 v2]]
                   (t/is (= v1 v2))
                   (done)))))
     :clj
     (let [p1 (m/pure pm/promise-context 4)
           p2 (m/pure pm/promise-context 4)
           vl  (m/>>= p2 #(m/pure pm/promise-context %))]
       @(p/then (p/all [p1 vl])
                (fn [[v1 v2]]
                  (t/is (= v1 v2)))))))

(t/deftest second-monad-law-right-identity
  #?(:cljs
     (t/async done
       (let [p1 (p/promise 3)
             rs  (m/>>= (p/promise 3) m/return)]
         (p/then (p/all [p1 rs])
                 (fn [[v1 v2]]
                   (t/is (= v1 v2))
                   (done)))))
     :clj
     (let [p1 (p/promise 3)
           rs  (m/>>= (p/promise 3) m/return)]
       @(p/then (p/all [p1 rs])
                (fn [[v1 v2]]
                  (t/is (= v1 v2)))))))

(t/deftest third-monad-law-associativity
  #?(:cljs
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
                   (done)))))
     :clj
     (let [rs1 (m/>>= (m/mlet [x (p/promise 2)
                               y (p/promise (inc x))]
                        (m/return y))
                      (fn [y]
                        (p/promise (inc y))))
           rs2 (m/>>= (p/promise 2)
                      (fn [x] (m/>>= (p/promise (inc x))
                                     (fn [y] (p/promise (inc y))))))
           [v1 v2] @(p/all [rs1 rs2])]
       (t/is (= v1 v2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


#?(:cljs (enable-console-print!))
#?(:cljs (set! *main-cli-fn* #(t/run-tests)))
#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests]
     [m]
     (if (t/successful? m)
       (set! (.-exitCode js/process) 0)
       (set! (.-exitCode js/process) 1))))
