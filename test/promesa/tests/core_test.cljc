(ns promesa.tests.core-test
  (:require
   [clojure.test :as t]
   [promesa.tests.util :refer [promise-ok promise-ko normalize-to-value]]
   [promesa.core :as p :include-macros true]
   [promesa.protocols :as pt]
   [promesa.impl :as impl]
   [promesa.util :as pu]
   [promesa.exec :as px])
  #?(:cljs
     (:import goog.Promise)))

;; --- Core Interface Tests

(t/deftest predicates
  (t/is (string? (pr-str (p/promise nil))))
  (t/is (false? (p/promise? {})))
  (t/is (false? (p/promise? nil)))
  (t/is (p/promise? (pt/-promise nil)))
  (t/is (false? (p/promise? #?(:cljs #js {} :clj (Object.)))))
  (t/is (false? (p/promise? [])))
  (t/is (false? (p/promise? #{})))
  (t/is (true? (p/promise? (p/promise nil))))
  (t/is (true? (p/promise? (p/promise 1)))))

(t/deftest inspect
  (let [p (p/promise :foo)]
    (t/is (p/promise? p))
    (t/is (= :foo @p)))

  (let [d (p/deferred)]
    (t/is (p/promise? d))
    (t/is (p/deferred? d))
    (t/is (p/pending? d))
    (t/is (nil? #?(:clj (deref d 200 nil)
                   :cljs (deref d)))))

  (let [d (p/deferred)]
    (t/is (= :no-val (p/extract d :no-val)))
    (p/resolve! d :foo)
    (t/is (not (p/pending? d)))
    (t/is (p/done? d))
    (t/is (= :foo (deref d)))
    (t/is (= :foo (p/extract d))))
  )

(t/deftest rejected-and-catch
  (let [p1 (-> (p/promise (ex-info "foo" {}))
               (p/catch ex-message))]
    #?(:clj
       (t/is (= "foo" @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= "foo" v))
                         (done)))))))

#?(:cljs
   (t/deftest catch-and-finally-1
     (t/async done
       (let [p1 (p/deferred)
             p3 (p/finally p1 (fn [a b]
                                (t/is (= b "boom"))
                                ))
             p2 (p/catch p1 (fn [v]
                              (t/is (= v "boom"))
                              v))
             ]

         (p/finally p2
                    (fn [v c]
                      (t/is (nil? c))
                      (t/is (= v "boom"))))

         (->> (p/wait-all p2 p1)
              (p/fnly done))

         (p/reject! p1 "boom")))))

#?(:cljs
   (t/deftest catch-and-finally-2
     (t/async done
       (let [p1 (p/deferred)
             p2 (p/catch p1 (fn [e]
                              (let [msg (str "wrap (" (ex-message e) ")")]
                                (throw (ex-info msg {})))))
             p3 (p/catch p2 (fn [e]
                              (let [msg (str "wrap (" (ex-message e) ")")]
                                (p/rejected (ex-info msg {})))))
             ]

         (p/finally p3 (fn [v c]
                         (t/is (= (ex-message c) "wrap (wrap (boom))"))
                         (done)))

         (p/reject! p1 (ex-info "boom" {}))))))

(t/deftest rejected-and-wrap-on-catch
  (let [p1 (p/rejected (ex-info "foobar" {}))
        p2 (p/catch p1 (fn [cause]
                         (p/resolved (ex-message cause))))
        p3 (p/then p2 (fn [message]
                        (str message "111")))
        p4 (p/then p3 (fn [message]
                        (str "111" message)))]

    #?(:clj
       (do
         (t/is (= "foobar" @p2))
         (t/is (= "foobar111" @p3))
         (t/is (= "111foobar111" @p4)))

       :cljs
       (t/async done
         (->> (p/all [p2 p3 p4])
              (p/fnly (fn [[v2 v3 v4] c]
                        (t/is (nil? c))
                        (t/is (= "foobar" v2))
                        (t/is (= "foobar111" v3))
                        (t/is (= "111foobar111" v4))
                        (done))))))))


(t/deftest throw-on-then
  (let [p1 (-> (p/promise 111)
               (p/then #(throw (ex-info "foo" {:v %})))
               (p/catch ex-message))]

    #?(:clj
       (t/is (= "foo" @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= "foo" v))
                         (done)))))))

(t/deftest throw-on-fmap-and-catch-with-merr
  (let [p1 (->> (p/promise 1)
                (p/fmap #(throw (ex-info "foo" {:v %})))
                (p/merr (fn [cause]
                          (p/resolved (ex-message cause)))))]
    #?(:clj
       (t/is (= "foo" @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= "foo" v))
                         (done)))))))

(t/deftest throw-on-fmap-and-catch-with-merr-2
  (let [p1 (->> (p/promise 1)
                (p/fmap #(throw (ex-info "foo" {:v %})))
                (p/merr (fn [cause]
                          (ex-message cause))))]
    #?(:clj
       (let [v1 (p/await p1)]
         (t/is (instance? Throwable v1)))

       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (instance? js/TypeError c))
                         (t/is (= "expected thenable" (ex-message c)))
                         (t/is (nil? v))
                         (done)))))))


(t/deftest promise-multiple-wrapping-and-mcat
  (let [p1 (->> (p/promise 1)
                (p/fmap (fn [v] (p/resolved (p/resolved (p/resolved (inc v))))))
                (p/mcat identity)
                (p/mcat identity)
                (p/mcat identity))]

    #?(:clj
       (t/is (= 2 @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 2 v))
                         (done)))))))

(t/deftest hmap-with-wraped-value
  (let [p1 (->> (p/promise 1)
                (p/hmap (fn [v c] (p/resolved (+ 1 v))))
                (p/mcat identity))]

    #?(:clj
       (t/is (= 2 @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 2 v))
                         (done)))))))


(t/deftest hmap-with-wrapped-exception
  (let [p1 (->> (p/promise 1)
                (p/hmap (fn [v c]
                          (p/rejected (ex-info "foobar" {}))))
                (p/mcat identity)
                (p/merr (fn [cause]
                          (p/resolved (ex-message cause)))))]

    #?(:clj
       (t/is (= "foobar" @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= "foobar" v))
                         (done)))))))

(t/deftest with-dispatch-with-plain-value
  (let [p1 (px/with-dispatch :default
             (+ 1 1))]

    #?(:clj
       (t/is (= 2 @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 2 v))
                         (done)))))))


(t/deftest with-dispatch-with-wrapped-value
  (let [p1 (px/with-dispatch :default
             (p/resolved (+ 1 1)))]
    #?(:clj
       (t/is (= 2 @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 2 v))
                         (done)))))))


(t/deftest with-dispatch-with-throw
  (let [p1 (-> (px/with-dispatch :default
                 (throw (ex-info "foobar" {})))
               (p/catch ex-message))]
    #?(:clj
       (t/is (= "foobar" @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= "foobar" v))
                         (done)))))))


(t/deftest deferred
  (let [p1 (p/deferred)]
    #?(:cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= v 1))
                         (done)))
         (p/resolve! p1 1))

       :clj
       (do
         (px/schedule! 200 #(p/resolve! p1 1))
         (t/is (= 1 (deref p1 1000 nil)))))))

(t/deftest promise-from-nil-value
  (let [p1 (p/promise nil)]
    #?(:clj
       (t/is (= nil @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (nil? v))
                         (done)))))))


(t/deftest promise-from-factory-resolve
  (let [p1 (p/create (fn [resolve _] (resolve 1)))]
    #?(:clj
       (t/is (= 1 @p1))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 1 v))
                         (done)))))))


(t/deftest promise-from-factory-reject
  (let [p1 (p/create (fn [_ reject] (reject (ex-info "foobar" {}))))]
    #?(:clj
       (let [v1 (p/await p1)]
         (t/is (p/rejected? p1))
         (t/is (pu/execution-exception? v1))
         (t/is (= "clojure.lang.ExceptionInfo: foobar {}" (ex-message v1)))
         (t/is (= "foobar" (ex-message (ex-cause v1)))))
       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? v))
                         (t/is (instance? cljs.core.ExceptionInfo c))
                         (t/is (instance? js/Error c))
                         (t/is (p/rejected? p1))
                         (t/is (= "foobar" (ex-message c)))
                         (done)))))))

(t/deftest promise-from-async-factory
  (let [p1 (p/create (fn [resolve _]
                       (px/schedule! 100 #(resolve 1))))]
    #?(:clj
       (do
         (t/is (p/pending? p1))
         (t/is (= 1 @p1)))
       :cljs
       (t/async done
         (t/is (p/pending? p1))
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 1 v))
                         (done)))))))


(t/deftest promise-from-promise
  (let [p1 (p/promise 1)
        p2 (p/promise p1)]
    (t/is (identical? p1 p2))))

(t/deftest promise-wrapping
  (let [p1 (p/promise 1)
        p2 (p/resolved p1)]
    (t/is (not (identical? p1 p2)))
    (t/is (identical? p1 @p2))))

(t/deftest compose-with-all-two-promises-1
  (let [p1 (-> (p/all [(promise-ok 100 "ok1")
                       (promise-ok 110 "ok2")])
               (normalize-to-value))]
    #?(:cljs
       (t/async done
         (->> (p/then p1
                      (fn [r]
                        (t/is (= ["ok1" "ok2"] r))))
              (p/fnly done)))
       :clj
       (t/is (= ["ok1" "ok2"] @p1)))))

(t/deftest compose-with-all-two-promises-2
  (let [p1 (-> (p/all [(promise-ok 100 :ok)
                       (promise-ko 100 :fail)])
               (normalize-to-value))]

    #?(:cljs
       (t/async done
         (->> (p/then p1
                      (fn [r]
                        (t/is (= :fail r))))
              (p/fnly done)))
       :clj
       (t/is (= :fail @p1)))))

(t/deftest compose-with-race-1
  (let [p1 (-> (p/race [(promise-ok 100 :ok)
                        (promise-ko 120 :fail)])
               (normalize-to-value))]

    #?(:clj (t/is (= :ok @p1))
       :cljs
       (t/async done
         (->> p1
              (p/fmap (fn [r]
                        (t/is (= r :ok))))
              (p/fnly done))))))

(t/deftest compose-with-race-2
  (let [p1 (-> (p/race [(promise-ok 200 :ok)
                        (promise-ko 190 :fail)])
               (normalize-to-value))]
    #?(:clj
       (t/is (= :fail @p1))
       :cljs

       (t/async done
         (->> p1
              (p/fmap (fn [r]
                        (t/is (= r :fail))))
              (p/fnly done))))))

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
  (let [state (atom [])
        f     (fn [i]
                (p/then (p/delay 1)
                        (fn [_]
                          (swap! state conj i)
                          nil)))
        p1    (p/run! f [1 2 3 4 5 6])]
    #?(:clj
       (do
         @p1
         (t/is (= [1 2 3 4 5 6] @state)))

       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? v))
                         (t/is (nil? c))
                         (t/is (= [1 2 3 4 5 6] @state))
                         (done)))))))

(t/deftest chain-with-then-with-unwrapping
  (let [p (-> (p/resolved 5)
              (p/then (comp p/resolved inc))
              (p/then (comp p/resolved inc)))]
    #?(:clj (t/is (= @p 7))
       :cljs
       (t/async done
         (p/finally p (fn [v]
                        (t/is (= v 7))
                        (done)))))))

(t/deftest chain-with-then-with-unwrapping-2
  (let [p (-> (p/resolved 5)
              (p/then (fn [v] (-> v p/resolved p/resolved p/resolved))))]
    #?(:clj (t/is (= @p 5))
       :cljs
       (t/async done
         (p/finally p (fn [v]
                        (t/is (= v 5))
                        (done)))))))

(t/deftest chain-with-fmap-and-map
  (let [p (->> (p/resolved 5)
               (p/fmap inc)
               (p/map inc))]
    #?(:clj (t/is (= @p 7))
       :cljs
       (t/async done
         (p/finally p (fn [v]
                        (t/is (= v 7))
                        (done)))))))

(t/deftest chain-with-then'
  (let [p (-> (p/resolved 5)
              (p/then' inc)
              (p/then' inc))]
    #?(:clj (t/is (= @p 7))
       :cljs
       (t/async done
         (p/finally p (fn [v]
                        (t/is (= v 7))
                        (done)))))))

(t/deftest chain-with-mapcat-and-mcat-and-fnly
  (let [p (->> (p/resolved 5)
               (p/mcat (comp p/wrap inc))
               (p/mapcat (comp p/wrap inc)))]
    #?(:clj (t/is (= @p 7))
       :cljs
       (t/async done
         (p/fnly (fn [v]
                   (t/is (= v 7))
                   (done))
                 p)))))

(t/deftest chain-with-handle
  (let [p (-> (p/resolved 5)
              (p/handle (fn [v _] (inc v)))
              (p/handle (fn [v _] (inc v))))]
    #?(:clj (t/is (= @p 7))
       :cljs
       (t/async done
         (p/finally p (fn [v]
                        (t/is (= v 7))
                        (done)))))))


(t/deftest chain-with-hmap
  (let [p (->> (p/resolved 5)
               (p/hmap (fn [v _] (inc v)))
               (p/hmap (fn [v _] (inc v))))]
    #?(:clj (t/is (= @p 7))
       :cljs
       (t/async done
         (p/finally p (fn [v]
                        (t/is (= v 7))
                        (done)))))))

(t/deftest chain-with-hcat
  (let [p (->> (p/resolved 5)
               (p/hcat (fn [v _]
                         (p/resolved (inc v))))
               (p/hcat (fn [v _]
                         (p/resolved (inc v)))))]
    #?(:clj (t/is (= @p 7))
       :cljs
       (t/async done
         (p/finally p (fn [v]
                        (t/is (= v 7))
                        (done)))))))


(t/deftest simple-delay
  (let [p (p/delay 100 5)]
    #?(:clj (t/is (= @p 5))
       :cljs
       (t/async done
         (p/fnly (fn [v c]
                   (t/is (nil? c))
                   (t/is (= v 5))
                   (done))
                 p)))))


(t/deftest timeout-test-1
  (let [p1 (-> (p/delay 100 :value)
               (p/timeout 50))]
    #?(:clj
       (let [v1 (p/await p1)]
         (t/is (pu/execution-exception? v1))
         (t/is (pu/timeout-exception? (ex-cause v1))))

       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? v))
                         (t/is (instance? p/TimeoutException c))
                         (done)))))))


(t/deftest chain-with-chain
  (let [p (-> (p/resolved 5)
              (p/chain inc inc inc inc inc))]
    #?(:clj (t/is (= @p 10))
       :cljs
       (t/async done
         (p/finally p (fn [v c]
                        (t/is (nil? c))
                        (t/is (= v 10))
                        (done)))))))


(t/deftest chain-with-chain-2
  (let [f (fn [v] (p/resolved (inc v)))
        p (-> (p/resolved 5)
              (p/chain f f f f f))]
    #?(:clj (t/is (= @p 10))
       :cljs
       (t/async done
         (p/finally p (fn [v c]
                        (t/is (nil? c))
                        (t/is (= v 10))
                        (done)))))))

(t/deftest chain-with-chain'
  (let [p (-> (p/resolved 5)
              (p/chain' inc inc inc inc inc))]
    #?(:clj (t/is (= @p 10))
       :cljs
       (t/async done
         (p/finally p (fn [v c]
                        (t/is (nil? c))
                        (t/is (= v 10))
                        (done)))))))

(t/deftest promisify
  (let [func1 (fn [x cb] (cb (inc x)))
        func2 (p/promisify func1)
        p1    (func2 2)]
  #?(:clj
     (t/is (= 3 @p1))
     :cljs
     (t/async done
       (p/finally p1 (fn [v c]
                       (t/is (nil? c))
                       (t/is (= v 3))
                       (done)))))))

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

;; --- let (async let) tests

(t/deftest async-let
  (let [p1 (p/let [a (promise-ok 50 1)
                   b 2
                   c 3
                   d (promise-ok 100 4)]
             (+ a b c d))]

  #?(:clj
     (t/is (= @p1 10))
     :cljs
     (t/async done
       (p/finally p1 (fn [v c]
                       (t/is (nil? c))
                       (t/is (= v 10))
                       (done)))))))

;; --- Do Expression tests

(t/deftest do-expression
  (let [err (ex-info "error" {})
        p2  (p/do! (promise-ko 10 :ko))
        p3  (p/do! (promise-ok 10 :ok1)
                   (promise-ok 10 :ok2))]
    #?(:clj
       (do
         @(p/wait-all p2 p3)
         (t/is (p/rejected? p2))
         (t/is (p/resolved? p3)))

       :cljs
       (->> (p/wait-all p2 p3)
            (p/fnly (fn [_ _]
                      (t/is (p/rejected? p2))
                      (t/is (p/resolved? p3))))))))

(t/deftest run-helper
  (let [s (atom [])
        f (fn [i]
            (->> (p/delay i)
                 (p/fmap (constantly i))
                 (p/fnly (fn [v c]
                           (swap! s conj v)))))
        p (p/run! f [10 20 30])]

  #?(:clj
     (do
       @p
       (t/is (= @s [10 20 30])))

     :cljs
     (t/async done
       (p/finally p (fn [v c]
                      (t/is (nil? c))
                      (t/is (nil? v))
                      (t/is (= [10 20 30] @s))
                      (done)))))))

(t/deftest reduce-operation
  (let [f (fn [result i]
            (->> (p/delay i)
                 (p/fmap (constantly i))
                 (p/fmap (fn [i]
                           (conj result (inc i))))))
        p (p/reduce f [] [101 201 301 401])]

  #?(:clj
     (let [res (p/await p)]
       (t/is (= res [102 202 302 402])))

     :cljs
     (t/async done
       (p/finally p (fn [res]
                      (t/is (= res [102 202 302 402]))
                      (done)))))))

(t/deftest future-macro
  (let [p1 (p/future (+ 1 2 3))]
    #?(:clj
       (t/is (= 6 @p1))

       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 6 v))
                         (done)))))))

(t/deftest loop-and-recur
  (let [p1 (p/loop [a (p/delay 50 0)]
             (if (= a 5)
               a
               (p/recur (p/delay 50 (inc a)))))
        p2 (p/race [p1 (p/delay 1000 10)])]

    #?(:clj
       (do
         (t/is (= 5 @p1))
         (t/is (= 5 @p2)))

       :cljs
       (t/async done
         (->> (p/all [p1 p2])
              (p/fnly (fn [[v1 v2] c]
                        (t/is (nil? c))
                        (t/is (= 5 v1))
                        (t/is (= 5 v2))
                        (done))))))))


;; --- Threading tests

(defn future-inc [x]
  (p/future (inc x)))

(t/deftest thread-first-macro
  (let [p1 (p/-> (p/future (+ 1 2 3)) (* 2) future-inc)]
    #?(:clj
       (t/is (= 13 @p1))

       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 13 v))
                         (done)))))))

(t/deftest thread-last-macro
  (let [p1 (p/->> (p/future [1 2 3]) (map inc))]
    #?(:clj
       (t/is (= [2 3 4] @p1))

       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= [2 3 4] v))
                         (done)))))))


(t/deftest thread-as-last-macro
  (let [p1 (p/as-> (p/future [1 2 3]) <>
             (reduce + 8 <>)
             (/ <> 2)
             (future-inc <>))]

    #?(:clj
       (t/is (= 8 @p1))

       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 8 v))
                         (done)))))))

;; Create some test variables and a test fn
;; These must be defined here as locals do not work

(def redefs-var :original)
(defn redefs-async-fn
  []
  (p/resolved "original"))

(t/deftest with-redefs-macro
  (let [original-fn redefs-async-fn
        p1 (p/with-redefs [redefs-async-fn  (fn [] (p/resolved "mocked"))
                           redefs-var :mocked]
             (p/then (redefs-async-fn)
                     (fn [v]
                       ;; NOTE: this value is unused - should it be removed?
                       ;; Reported by clj-kondo
                       (not= redefs-async-fn original-fn)
                       [v redefs-var])))]

    #?(:clj
       (let [res @p1]
         (t/is (= res ["mocked" :mocked]))
         (t/is (= redefs-async-fn original-fn)))

       :cljs
       (t/async done
         (p/finally p1 (fn [v c]
                         (t/is (= v ["mocked" :mocked]))
                         (t/is (= redefs-async-fn original-fn))
                         (t/is (nil? c))
                         (done)))))))

(t/deftest doseq-test
  (let [s (atom [])
        p (p/doseq [x [10 20 30]]
            (p/delay (- 100 x))
            (swap! s conj x))]

    #?(:clj
       (do
         @p
         (t/is (= [10 20 30] @s)))

       :cljs
       (t/async done
         (p/finally p (fn [v c]
                        (t/is (nil? c))
                        (t/is (nil? v))
                        (t/is (= [10 20 30] @s))
                        (done)))))))

#?(:cljs
   (t/deftest native-promise-coerce
     (let [p1 (js/Promise. (fn [resolve reject]
                             (px/schedule! 100 #(resolve 1))))
           p2 (p/promise p1)]
       (t/is (not (identical? p1 p2))))))

#?(:cljs
   (t/deftest native-promise-coerce-2
     (let [p1 (js/Promise. (fn [resolve reject]
                             (px/schedule! 100 #(resolve 1))))
           p2 (->> p1
                   (p/fmap inc)
                   (p/mcat #(p/resolved (inc %))))]
       (t/async done
         (p/finally p2 (fn [v c]
                         (t/is (nil? c))
                         (t/is (= 3 v))
                         (t/is (p/resolved? p2))
                         (done)))))))

#?(:cljs
   (t/deftest async-let-with-undefined
     (let [f (constantly (js* "void 0"))
           p (p/let [a (p/resolved 1)
                     b (p/resolved 2)
                     c (f 3)]
               (+ a b))]
       (p/finally p (fn [v c]
                      (t/is (nil? c)))))))

#?(:bb nil
   :clj
   (t/deftest let-syntax-test
     (t/is (thrown? clojure.lang.Compiler$CompilerException
                    (eval `(p/let* [uneven#]))))
     (t/is (thrown? clojure.lang.Compiler$CompilerException
                    (eval `(p/let [uneven#]))))
     (t/is (thrown? clojure.lang.Compiler$CompilerException
                    (eval `(p/plet [uneven#]))))))


(t/deftest cancel-scheduled-task
  (let [value (volatile! nil)
        c1 (px/schedule! 200 #(vreset! value 1))
        c2 (px/schedule! 200 #(vreset! value 2))]
    (p/cancel! c1)

    #?(:clj
       (let [v1 (p/await c1)
             v2 (p/await c2)]

         (t/is (p/cancelled? c1))
         (t/is (p/resolved? c2))
         (t/is (p/rejected? c1))
         (t/is (= @c2 2))
         (t/is (= v2 2))
         (t/is (pu/cancellation-exception? v1)))

       :cljs
       (t/async done
         (->> (p/wait-all c1 c2)
              (p/fnly (fn [v c]
                        (t/is (nil? v))
                        (t/is (nil? c))
                        (t/is (p/cancelled? c1))
                        (t/is (p/resolved? c2))
                        (t/is (p/rejected? c1))
                        (t/is (= @c2 2))
                        (done))))))))

#?(:cljs
   (impl/extend-promise! Promise))

#?(:cljs
   (t/deftest check-thenable-compatibility
     (t/async done
       (let [thenable (new Promise (fn [resolve reject]
                                     (resolve 1)))]
         (->> thenable
              (p/fmap inc)
              (p/fnly (fn [r]
                        (t/is (= r 2))
                        (done))))))))
