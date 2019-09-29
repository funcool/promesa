(ns promesa.tests.test-core
  (:require #?(:cljs [cljs.test :as t]
               :clj [clojure.test :as t])
            [promesa.tests.util :refer [promise-ok promise-ko normalize-to-value]]
            [promesa.core :as p :include-macros true]
            [promesa.exec :as e])
  #?(:clj
     (:import java.util.concurrent.TimeoutException)))

;; --- Core Interface Tests

(t/deftest print-promise
  (t/is (string? (pr-str (p/promise nil)))))

(t/deftest promise-from-value
  #?(:cljs
     (t/async done
       (p/then (p/promise 1)
               (fn [v]
                 (t/is (= v 1))
                 (done))))
     :clj
     (let [p1 (p/promise 1)]
       (t/is (p/done? p1))
       (t/is (= (p/extract p1) 1)))))

(t/deftest promise-icompletable
  #?(:cljs
     (t/async done
       (let [pr (p/deferred)]
         (p/then pr (fn [v]
                     (t/is (= v 1))
                     (done)))
         (p/resolve! pr 1)))

     :clj
     (let [p (p/deferred)]
       (e/schedule! 200 #(p/resolve! p 1))
       (t/is (= @p 1)))))

(t/deftest promise-from-nil-value
  #?(:cljs
     (t/async done
       (p/then (p/promise nil)
               (fn [v]
                 (t/is (= v nil))
                 (done))))
     :clj
     @(p/then (p/promise nil)
              (fn [v]
                (t/is (= v nil))))))


(t/deftest promise-from-factory
  #?(:cljs
     (t/async done
       (-> (p/create (fn [resolve _] (resolve 1)))
           (p/then (fn [v]
                     (t/is (= v 1))
                     (done)))))
     :clj
     @(-> (p/create (fn [resolve _] (resolve 1)))
          (p/then (fn [v]
                    (t/is (= v 1)))))))

(t/deftest promise-async-factory
  #?(:cljs
     (t/async done
       (let [p1 (p/create (fn [resolve reject]
                             (e/schedule! 50 #(resolve 1))))]
         ;; (t/is (p/pending? p1))
         (p/then p1 (fn [v]
                      (t/is (= v 1))
                      (done)))))
     :clj
     (let [p1 (p/create (fn [resolve reject]
                           (e/schedule! 500 #(resolve 1))))]
       (t/is (p/pending? p1))
       (t/is (= @p1 1)))))

(t/deftest promise-from-exception
  #?(:clj
     (let [e (ex-info "foo" {})
           p1 (p/promise e)]
       (t/is (p/rejected? p1))
       (t/is (= e @@(p/catch p1 (fn [x] (reduced x))))))
     :cljs
     (t/async done
       (let [e (ex-info "foo" {})
             p1 (p/promise e)]
         (p/catch p1 (fn [x]
                       (t/is (= e x))
                       (done)))))))

(t/deftest promise-from-promise
  (let [p1 (p/promise 1)
        p2 (p/promise p1)]
    (t/is (identical? p1 p2))))

(t/deftest compose-with-all-two-promises
  (let [p1 (-> (p/all [(promise-ok 100 :ok1)
                       (promise-ok 110 :ok2)])
               (normalize-to-value))
        p2 (-> (p/all [(promise-ok 100 :ok)
                       (promise-ko 100 :fail)])
               (normalize-to-value))]

    #?(:cljs
       (t/async done
         (p/do! (p/then p1 (fn [r] (t/is (= [:ok1 :ok2] r))))
                (p/then p2 (fn [r] (t/is (= :fail r))))
                (done)))
       :clj
       (do
         (t/is (= [:ok1 :ok2] @p1))
         (t/is (= :fail @p2))))))

(t/deftest compose-with-race
  (let [p1 (-> (p/race [(promise-ok 100 :ok)
                        (promise-ko 110 :fail)])
               (normalize-to-value))
        p2 (-> (p/race [(promise-ok 200 :ok)
                        (promise-ko 190 :fail)])
               (normalize-to-value))]

    #?(:clj
       (do
         (t/is (= :ok @p1))
         (t/is (= :fail @p2)))
       :cljs
       (t/async done
         (p/do! (p/then p1 (fn [r] (t/is (= r :ok))))
                (p/then p2 (fn [r] (t/is (= r :fail))))
                (done))))))

(t/deftest compose-with-any
  (let [p1 (p/any [(promise-ko 100 :fail1)
                   (promise-ko 200 :fail2)
                   (promise-ok 150 :ok)])]
    #?(:cljs
       (t/async done
         (p/then p1 (fn [v]
                      (t/is (= v :ok))
                      (done))))
       :clj
       (t/is (= @p1 :ok)))))


(t/deftest serial-execution-with-run
  #?(:cljs
     (t/async done
       (let [state (atom [])
             func (fn [i]
                    (p/then (p/delay 100)
                            (fn [_] (swap! state conj i))))]
         (p/then (p/run! func [1 2 3 4 5 6])
                 (fn [_]
                   (t/is (= [1 2 3 4 5 6] @state))
                   (done)))))

     :clj
     (let [state (atom [])]
       @(p/run! (fn [i] (p/then (p/delay 100) (fn [_] (swap! state conj i))))
                [1 2 3 4 5 6])
       (t/is (= [1 2 3 4 5 6] @state)))))


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
       (let [p1 (promise-ok 100 2)
             p2 (p/then p1 inc)
             p3 (p/then p2 inc)]
         (p/then p3 (fn [v]
                      (t/is (= v 4))
                      (done)))))
     :clj
     (let [p1 (promise-ok 100 2)
           p2 (p/then p1 inc)
           p3 (p/then p2 inc)]
       (t/is (= @p3 4)))))

(t/deftest then-promise
  (let [p (-> (p/resolved 5)
              (p/then (comp p/resolved inc))
              (p/then (comp p/resolved inc)))]
    #?(:clj (t/is (= @p 7))
       :cljs
       (t/async done
         (p/then p (fn [v]
                     (t/is (= v 7))
                     (done)))))))

(t/deftest chaining-using-map
  (let [p1 (promise-ok 10 2)
        p2 (p/map inc p1)
        p3 (p/map inc p2)

        test #(p/then p3 (fn [res] (t/is (= res 4))))]
    #?(:cljs (t/async done (p/do! (test) (done)))
       :clj @(test))))

(t/deftest chaining-using-then'
  (let [p1 (promise-ok 10 2)
        p2 (p/then' p1 inc)
        p3 (p/then' p2 inc)
        test #(p/then p3 (fn [res] (t/is (= res 4))))]
    #?(:cljs (t/async done (p/do! (test) (done)))
       :clj @(test))))

(t/deftest chaining-using-mapcat
  (let [p1 (promise-ok 100 2)
        inc #(p/resolved (inc %))
        p2 (p/mapcat inc p1)
        p3 (p/mapcat inc p2)
        test #(p/then p3 (fn [v] (t/is (= v 4))))]

    #?(:cljs (t/async done (p/do! (test) (done)))
       :clj @(test))))

(t/deftest chaining-using-finally
  (let [p1 (promise-ok 100 2)
        st (atom 0)
        p2 (p/finally p1 (fn [v e] (swap! st inc) :foobar))
        test #(p/then p2 (fn [v]
                           (t/is (= v 2))
                           (t/is (= @st 1))))]

    #?(:cljs (t/async done (p/do! (test) (done)))
       :clj @(test))))

(t/deftest chaining-using-handle
  (let [p1 (promise-ok 100 2)
        st (atom 0)
        p2 (p/handle p1 (fn [v e] (swap! st inc) :foobar))
        test #(p/then p2 (fn [v]
                           (t/is (= v :foobar))
                           (t/is (= @st 1))))]

    #?(:cljs (t/async done (p/do! (test) (done)))
       :clj @(test))))

(t/deftest cancel-scheduled-task
  #?(:cljs
     (t/async done
       (let [value (volatile! nil)
             c1 (e/schedule! 100 #(vreset! value 1))
             c2 (e/schedule! 100 #(vreset! value 2))]
         (p/cancel! c1)
         (e/schedule! 300
                     (fn []
                       (t/is (= @value 2))
                       (t/is (realized? c2))
                       (t/is (not (realized? c1)))
                       (t/is (p/cancelled? c1))
                       (done)))))
     :clj
     (let [value (volatile! nil)
           c1 (e/schedule! 500 #(vreset! value 1))
           c2 (e/schedule! 500 #(vreset! value 2))]
       (p/cancel! c1)
       @(e/schedule! 1100 (constantly nil))
       (t/is (realized? c2))
       (t/is (not (realized? c1)))
       (t/is (p/cancelled? c1))
       (t/is (= @value 2)))))

(t/deftest timeout-test-1
  #?(:cljs
     (t/async done
       (let [prm (-> (p/delay 100 :value)
                     (p/timeout 50))]
         (p/catch prm (fn [e]
                        (t/is (instance? p/TimeoutException e))
                        (done)))))
     :clj
     (let [prm (-> (p/delay 100 :value)
                   (p/timeout 50))]
       @(p/catch prm (fn [e]
                       (t/is (instance? TimeoutException e)))))))

(t/deftest timeout-test-2
  #?(:cljs
     (t/async done
       (let [prm (-> (p/delay 200 :value)
                     (p/timeout 300))]
         (p/then prm (fn [v]
                        (t/is (= (v :value)))
                        (done)))))
     :clj
     (let [prm (-> (p/delay 200 :value)
                   (p/timeout 500))]
       @(p/then prm (fn [v]
                     (t/is (= (v :value))))))))

(t/deftest chaining-using-chain
  #?(:cljs
     (t/async done
       (let [p1 (promise-ok 100 2)
             p2 (p/chain p1 inc inc inc)]
         (p/then p2 (fn [v]
                      (t/is (= v 5))
                      (done)))))
     :clj
     (let [p1 (promise-ok 100 2)
           p2 (p/chain p1 inc inc inc)]
       (t/is (= @p2 5)))))

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
  (let [p (-> (p/create
               (fn [_ reject] (reject (ex-info "foobar" {:x ::value}))))
              (p/catch unwrap-caught))]
    #?(:cljs
       (t/async done
         (p/then p (fn [x]
                     (t/is (= x ::value))
                     (done))))
       :clj
       (t/is (= @p ::value)))))

;; --- alet (async let) tests

#?(:clj
   (t/deftest async-let
     (let [result (p/alet [a (promise-ok 50 1)
                           b 2
                           c 3
                           d (promise-ok 100 4)]
                    (+ a b c d))]
       (t/is (= @result 10)))))

#?(:cljs
   (t/deftest async-let
     (t/async done
       (let [result (p/alet [a (promise-ok 50 1)
                             b 2
                             c 3
                             d (promise-ok 100 4)
                             e (.toString c)]
                      (+ a b c d))]
         (p/then result (fn [result]
                          (t/is (= result 10))
                          (done)))))))

;; --- Do Expression tests

(t/deftest do-expression
  (let [err (ex-info "error" {})
        p1 (p/do! (throw err))
        p2 (p/do! (promise-ko 10 :ko))
        p3 (p/do! (promise-ok 10 :ok1)
                  (promise-ok 10 :ok2))

        test #(p/do!
               (-> (normalize-to-value p1)
                   (p/then (fn [res] (t/is (= res 'error)))))
               (-> (normalize-to-value p2)
                   (p/then (fn [res] (t/is (= res :ko)))))
               (-> (normalize-to-value p3)
                   (p/then (fn [res] (t/is (= res :ok2))))))]
    #?(:cljs (t/async done (p/do! (test) (done)))
       :clj @(test))))

(t/deftest run-helper
  (let [f (fn [i] (p/then (p/delay i) (constantly i)))
        p1 (run! f [10 20 30])
        test #(->> (p/race [p1 (p/delay 100)])
                   (p/map (fn [res] (t/is (= res nil)))))]
    #?(:cljs (t/async done (p/do! (test) (done)))
       :clj @(test))))

(t/deftest future-macro
  (let [p1 (p/future (+ 1 2 3))
        test #(p/then p1 (fn [res] (t/is (= res 6))))]
    #?(:cljs (t/async done (p/do! (test) (done)))
       :clj @(test))))

(t/deftest loop-and-recur
  (let [p1 (p/loop [a (p/delay 50 0)]
             (if (= a 5)
               a
               (p/recur (p/delay 50 (inc a)))))
        test #(->> (p/race [p1 (p/delay 400 10)])
                   (p/map (fn [res] (t/is (= res 5)))))]
    #?(:cljs (t/async done (p/do! (test) (done)))
       :clj @(test))))
