;; Copyright (c) 2015 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;;
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns promesa.core
  (:refer-clojure :exclude [delay spread some])
  (:require [cats.core :as m]
            [cats.context :as mc]
            [cats.protocols :as mp]
            [promesa.protocols :as p]
            #?(:cljs [org.bluebird]))
  #?(:clj
     (:import java.util.concurrent.CompletableFuture
              java.util.concurrent.CompletionStage
              java.util.concurrent.TimeoutException
              java.util.concurrent.ExecutionException
              java.util.concurrent.CompletionException
              java.util.concurrent.TimeUnit
              java.util.concurrent.Future
              java.util.concurrent.Executor
              java.util.concurrent.ForkJoinPool
              java.util.function.Function
              java.util.function.Supplier)))

#?(:clj
   (def ^{:doc "The main executor service for schedule promises."
          :dynamic true :no-doc true}
     *executor* (ForkJoinPool/commonPool)))

#?(:clj
   (defn schedule
     {:no-doc true}
     ([func]
      (schedule *executor* func))
     ([^Executor executor ^Runnable func]
      (.execute executor func))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare promise-context)

#?(:cljs
   (extend-type js/Promise
     mp/Contextual
     (-get-context [_] promise-context)

     mp/Extract
     (-extract [it]
       (if (.isRejected it)
         (.cause it)
         (.value it)))

     p/IFuture
     (-map [it cb]
       (.then it cb))
     (-bind [it cb]
       (.then it cb))
     (-catch [it cb]
       (.catch it cb))

     p/IState
     (-resolved? [it]
       (.isFulfilled it))
     (-rejected? [it]
       (.isRejected it))
     (-pending? [it]
       (.isPending it)))

   :clj
   (extend-type CompletionStage
     mp/Contextual
     (-get-context [_] promise-context)

     mp/Extract
     (-extract [it]
       (try
         (.getNow it nil)
         (catch ExecutionException e
           (.getCause e))
         (catch CompletionException e
           (.getCause e))))

     p/IFuture
     (-map [it cb]
       (.thenApplyAsync cf (function cb) *executor*))
     (-bind [it cb]
       (.thenComposeAsync cf (function cb) *executor*))
     (-catch [it cb]
       (.exceptionally it (reify Function
                            (apply [_ v] (f v)))))

     p/IState
     (-resolved? [it]
       (and (not (.isCompletedExceptionally cs))
            (not (.isCancelled cs))
            (.isDone cs)))
     (-rejected? [it]
       (.isCompletedExceptionally it))
     (-pending? [it]
       (and (not (.isCompletedExceptionally cs))
            (not (.isCancelled cs))
            (not (.isDone cs))))))

#?(:clj
   (extend-protocol p/IPromiseFactory
     clojure.lang.Fn
     (-promise [func]
       (let [cf (CompletableFuture.)
             resolve #(.completeExceptionally cf %)
             reject #(.complete cf %)]
         (schedule (fn []
                     (try
                       (func resolve reject)
                       (catch Throwable e
                         (reject e)))))
         cf))

     Throwable
     (-promise [e]
       (let [p (CompletableFuture.)]
         (.completeExceptionally p e)
         p))

     CompletionStage
     (-promise [cs] cs)

     Object
     (-promise [v]
       (let [p (CompletableFuture.)]
         (.complete p v)
         p)))

   :cljs
   (extend-protocol p/IPromiseFactory
     function
     (-promise [func]
       (js/Promise. func))

     js/Error
     (-promise [e]
       (.reject js/Promise e))

     object
     (-promise [v]
       (.resolve js/Promise v))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn resolved
  "Return a resolved promise with provided value."
  [v]
  #?(:cljs (.resolve js/Promise v)
     :clj (let [p (CompletableFuture.)]
            (.complete p v)
            p)))

(defn rejected
  "Return a rejected promise with provided reason."
  [v]
  #?(:cljs (.reject js/Promise v)
     :clj (let [p (CompletableFuture.)]
            (.completeExceptionally p e)
            p)))

(defn promise
  "The promise constructor."
  [v]
  (-promise v))

;; Predicates

(defn resolved?
  "Returns true if promise `p` is already fulfilled."
  [p]
  (p/-resolved? p)))

(defn rejected?
  "Returns true if promise `p` is already rejected."
  [p]
  (p/-rejected? p)))

(defn pending?
  "Returns true if promise `p` is stil pending."
  [p]
  (p/-pending? p)))

(defn done?
  "Returns true if promise `p` is already done."
  [p]
  (not (p/-pending? p)))


;; #?(:cljs
;; (defn then
;;   "A chain helper for promises."
;;   [p callback]
;;   (p/-then p callback)))

;; #?(:cljs
;; (defn chain
;;   "A variadic chain operation."
;;   [p & funcs]
;;   (reduce #(then %1 %2) p funcs)))

;; #?(:cljs
;; (defn catch
;;   "Catch all promise chain helper."
;;   ([p callback]
;;    (p/-catch p callback))
;;   ([p type callback]
;;    (p/-catch p type callback))))

;; ;; Helpers

;; #?(:cljs
;; (defn await
;;   "A placeholder for async macro."
;;   [& arguments]
;;   (throw (ex-info "You are using await out of async macro." {}))))

;; #?(:cljs
;; (defn all
;;   "Given an array of promises, return a promise
;;   that is fulfilled  when all the items in the
;;   array are fulfilled."
;;   [promises]
;;   (m/sequence promises)))

;; #?(:cljs
;; (defn any
;;   "Given an array of promises, return a promise
;;   that is fulfilled when first one item in the
;;   array is fulfilled."
;;   [promises]
;;   (.any js/Promise (clj->js promises))))

;; #?(:cljs
;; (defn some
;;   "Given an array of promises, return a promise
;;   that is fulfilled when `n` number of promises
;;   is fulfilled."
;;   [n promises]
;;   (then (.some js/Promise (clj->js promises) n)
;;         #(js->clj %))))


;; #?(:cljs
;; (defn promisify
;;   "Given a nodejs like function that accepts a callback
;;   as the last argument and return an other function
;;   that returns a promise."
;;   [callable]
;;   (fn [& args]
;;     (promise (fn [resolve]
;;                (let [args (-> (vec args)
;;                               (conj resolve))]
;;                  (apply callable args)))))))

;; #?(:cljs
;; (defn timeout
;;   "Returns a cancellable promise that will be fulfilled
;;   with this promise's fulfillment value or rejection reason.
;;   However, if this promise is not fulfilled or rejected
;;   within `ms` milliseconds, the returned promise is cancelled
;;   with a TimeoutError"
;;   ([p t] (.timeout p t))
;;   ([p t v] (.timeout p t v))))

;; #?(:cljs
;; (defn delay
;;   "Given a timeout in miliseconds and optional
;;   value, returns a promise that will fulfilled
;;   with provided value (or nil) after the
;;   time is reached."
;;   ([t] (delay t nil))
;;   ([t v]
;;    (.delay js/Promise v t))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monad Type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:cljs
(def ^{:no-doc true}
  promise-context
  (reify
    mp/Context
    (-get-level [_] mc/+level-default+)

    mp/Functor
    (-fmap [mn f mv]
      (then mv f))

    mp/Applicative
    (-pure [_ v]
      (promise v))

    (-fapply [_ pf pv]
      (then (all [pf pv])
            (fn [[f v]]
              (f v))))

    mp/Semigroup
    (-mappend [_ mv mv']
      (p/-then (m/sequence [mv mv'])
               (fn [[mvv mvv']]
                 (let [ctx (mp/-get-context mvv)]
                   (mp/-mappend ctx mvv mvv')))))

    mp/Monad
    (-mreturn [_ v]
      (promise v))

    (-mbind [mn mv f]
      (then mv f)))))

