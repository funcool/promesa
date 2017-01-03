(ns promesa.core-tests
  (:require #?(:cljs [cljs.test :as t]
               :clj [clojure.test :as t])
            #?(:clj [promesa.async :refer [async]]
               :cljs [promesa.async-cljs :refer-macros [async]])
            #?(:cljs [promesa.issue-36 :refer [async-let-issue-36]])
            [promesa.test-helpers :refer [future-ok future-fail]]
            [promesa.core :as p]))

;; --- Core Interface Tests

(t/deftest print-promise
  (t/is (string? (pr-str (p/promise nil)))))

(t/deftest promise-from-value
  (let [p1 (p/promise 1)]
    (t/is (p/done? p1))
    (t/is (= (p/extract p1) 1))))

(t/deftest promise-from-boolean-value
  (let [p1 (p/promise true)]
    (t/is (p/done? p1))
    (t/is (= (p/extract p1) true))))

(t/deftest promise-from-string-value
  (let [p1 (p/promise "hello")]
    (t/is (p/done? p1))
    (t/is (= (p/extract p1) "hello"))))

(t/deftest promise-from-nil-value
  (let [p1 (p/promise nil)]
    (t/is (p/done? p1))
    (t/is (nil? (p/extract p1)))))

(t/deftest promise-from-factory
  (let [p1 (p/promise (fn [resolve _] (resolve 1)))]
    #?(:clj (deref p1))
    (t/is (p/done? p1))
    (t/is (= (p/extract p1) 1))))

(t/deftest promise-async-factory
  #?(:cljs
     (t/async done
       (let [p1 (p/promise (fn [resolve reject]
                             (p/schedule 50 #(resolve 1))))]
         (t/is (p/pending? p1))
         (p/then p1 (fn [v]
                      (t/is (= v 1))
                      (done)))))
     :clj
     (let [p1 (p/promise (fn [resolve reject]
                           (p/schedule 50 #(resolve 1))))]
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
       (t/is (p/rejected? p1))
       (p/catch p1 (fn [x]
                     (t/is (= e x))
                     (done)))))))

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
       (let [p1 (p/any [(p/delay 300 1) (p/delay 100 2)])]
         (p/then p1 (fn [v]
                      (t/is (= v 2))
                      (done)))))
     :clj
     (let [p1 (p/any [(p/delay 300 1) (p/delay 100 2)])]
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
       (let [p1 (future-ok 100 2)
             p2 (p/then p1 inc)
             p3 (p/then p2 inc)]
         (p/then p3 (fn [v]
                      (t/is (= v 4))
                      (done)))))
     :clj
     (let [p1 (future-ok 100 2)
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
  #?(:cljs
     (t/async done
       (let [p1 (future-ok 100 2)
             p2 (p/map inc p1)
             p3 (p/map inc p2)]
         (p/then p3 (fn [v]
                      (t/is (= v 4))
                      (done)))))
     :clj
     (let [p1 (future-ok 100 2)
           p2 (p/map inc p1)
           p3 (p/map inc p2)]
       (t/is (= @p3 4)))))

(t/deftest chaining-using-mapcat
  #?(:cljs
     (t/async done
       (let [p1 (future-ok 100 2)
             inc #(p/resolved (inc %))
             p2 (p/mapcat inc p1)
             p3 (p/mapcat inc p2)]
         (p/then p3 (fn [v]
                      (t/is (= v 4))
                      (done)))))
     :clj
     (let [p1 (future-ok 100 2)
           inc #(p/resolved (inc %))
           p2 (p/mapcat inc p1)
           p3 (p/mapcat inc p2)]
       (t/is (= @p3 4)))))

(t/deftest cancel-scheduled-task
  #?(:cljs
     (t/async done
       (let [value (volatile! nil)
             c1 (p/schedule 100 #(vreset! value 1))
             c2 (p/schedule 100 #(vreset! value 2))]
         (p/cancel! c1)
         (p/schedule 300
                     (fn []
                       (t/is (= @value 2))
                       (t/is (realized? c2))
                       (t/is (not (realized? c1)))
                       (t/is (p/cancelled? c1))
                       (done)))))
     :clj
     (let [value (volatile! nil)
           c1 (p/schedule 500 #(vreset! value 1))
           c2 (p/schedule 500 #(vreset! value 2))]
       (p/cancel! c1)
       @(p/schedule 1100 (constantly nil))
       (t/is (realized? c2))
       (t/is (not (realized? c1)))
       (t/is (p/cancelled? c1))
       (t/is (= @value 2)))))

(t/deftest chaining-using-chain
  #?(:cljs
     (t/async done
       (let [p1 (future-ok 100 2)
             p2 (p/chain p1 inc inc inc)]
         (p/then p2 (fn [v]
                      (t/is (= v 5))
                      (done)))))
     :clj
     (let [p1 (future-ok 100 2)
           p2 (p/chain p1 inc inc inc)]
       (t/is (= @p2 5)))))

(t/deftest branching-using-branch-1
  #?(:cljs
     (t/async done
       (let [p1 (future-ok 100 2)
             p2 (p/branch p1 #(inc %) (constantly nil))]
         (p/then p2 #(do
                       (t/is (= % 3))
                       (done)))))
     :clj
     (let [p1 (future-ok 100 2)
           p2 (p/branch p1 #(inc %) (constantly nil))]
       (t/is (= @p2 3)))))

(t/deftest branching-using-branch-2
  #?(:cljs
     (t/async done
       (let [e (ex-info "foobar" {})
             p1 (future-fail 100 e)
             p2 (p/branch p1 (constantly nil) identity)]
         (p/then p2 #(do
                       (t/is (= % e))
                       (done)))))
     :clj
     (let [e (ex-info "foobar" {})
           p1 (future-fail 100 e)
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

;; --- alet (async let) tests

#?(:clj
   (t/deftest async-let
     (let [result (p/alet [a (p/await (future-ok 50 1))
                           b 2
                           c 3
                           d (p/await (future-ok 100 4))]
                    (+ a b c d))]
       (t/is (= @result 10)))))

#?(:cljs
   (t/deftest async-let
     (t/async done
       (let [result (p/alet [a (p/await (future-ok 50 1))
                             b 2
                             c 3
                             d (p/await (future-ok 100 4))]
                      (+ a b c d))]
         (p/then result (fn [result]
                          (t/is (= result 10))
                          (done)))))))

#?(:clj
   (t/deftest async-let-await-binding
     ;; Test for https://github.com/funcool/promesa/issues/36
     (let [result (p/alet [x (agent {})
                           _ (send-off x #(assoc % :done true))
                           _ (clojure.core/await x)
                           z (p/await (p/promise @x))]
                          z)]
       (t/is (= @result {:done true})))))

;; ;; --- Do Expression tests

;; (t/deftest do-expression
;;   #?(:cljs
;;      (t/async done
;;        (let [error (ex-info "foo" {})
;;              result (p/do* (throw error))]
;;          (p/catch result (fn [e]
;;                            (t/is (= e error))
;;                            (done)))))
;;      :clj
;;      (let [error (ex-info "foo" {})
;;            result (p/do* (throw error))
;;            result @(p/catch result (fn [e]
;;                                      (assert (= e error))
;;                                      nil))]
;;        (t/is (= nil result)))))


;; ;; --- `async` macro tests

(defn my-func
  [i]
  (async
    (loop [sum 0
           c 0]
      (if (< c i)
        (do
          (p/await (p/delay 10))
          (recur (+ sum i) (inc c)))
        sum))))


(t/deftest async-macro
  (letfn [(do-stuff [i]
            (async
              (loop [sum 0
                     c 0]
                (if (< c i)
                  (do
                    (p/await (p/delay 10))
                    (recur (+ sum i) (inc c)))
                  sum))))]
    #?(:cljs
       (t/async done
         (p/then (do-stuff 10)
                 (fn [result]
                   (t/is (= 100 result))
                   (done))))
       :clj
       (t/is (= 100 @(do-stuff 10))))))

;; --- Entry Point

#?(:cljs (enable-console-print!))
#?(:cljs (set! *main-cli-fn* #(t/run-tests
                               *ns*
                               'promesa.issue-36)))
#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests]
     [m]
     (if (t/successful? m)
       (set! (.-exitCode js/process) 0)
       (set! (.-exitCode js/process) 1))))
