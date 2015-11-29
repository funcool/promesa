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
  (:refer-clojure :exclude [delay spread promise])
  (:require [cats.core :as m]
            [cats.context :as mc]
            [cats.protocols :as mp]
            [cats.util :as mutil]
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
              java.util.concurrent.Executors
              java.util.concurrent.ForkJoinPool
              java.util.function.Function
              java.util.function.Supplier)))

#?(:clj
   (do
     (def ^:dynamic ^:no-doc
       *executor* (ForkJoinPool/commonPool))
     (def ^:dynamic ^:no-doc
       *scheduler* (Executors/newScheduledThreadPool 1))))

(defn schedule
  {:no-doc true}
  [ms func]
  #?(:cljs (js/setTimeout func ms)
     :clj (.schedule *scheduler* func ms TimeUnit/MILLISECONDS)))

(defn submit
  {:no-doc true}
  [func]
  #?(:cljs (schedule 0 func)
     :clj (.execute *executor* func)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation detail
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare promise-context)

#?(:cljs
   (extend-type js/Promise
     mp/Contextual
     (-get-context [_] promise-context)

     mp/Printable
     (-repr [it]
       (str "#<Promise ["
            (cond
              (p/-pending? it) "pending"
              (p/-rejected? it) "rejected"
              :else "resolved")
            "]>"))

     mp/Extract
     (-extract [it]
       (if (.isRejected it)
         (.reason it)
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
       (.isPending it))))

#?(:clj
   (extend-type CompletionStage
     mp/Contextual
     (-get-context [_] promise-context)

     mp/Printable
     (-repr [it]
       (str "#<Promise ["
            (cond
              (p/-pending? it) "pending"
              (p/-rejected? it) "rejected"
              :else "resolved")
            "]>"))

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
       (.thenApplyAsync it (reify Function
                             (apply [_ v]
                               (let [result (cb v)]
                                 result)))
                        *executor*))

     (-bind [it cb]
       (.thenComposeAsync it (reify Function
                               (apply [_ v]
                                 (let [result (cb v)]
                                   (if-not (instance? CompletionStage result)
                                     (let [p (CompletableFuture.)]
                                       (.complete p result)
                                       p)
                                     result))))
                          *executor*))

     (-catch [it cb]
       (.exceptionally it (reify Function
                            (apply [_ e]
                              (if (instance? CompletionException e)
                                (cb (.getCause e))
                                (cb e))))))
     p/IState
     (-resolved? [it]
       (and (not (.isCompletedExceptionally it))
            (not (.isCancelled it))
            (.isDone it)))

     (-rejected? [it]
       (.isCompletedExceptionally it))

     (-pending? [it]
       (and (not (.isCompletedExceptionally it))
            (not (.isCancelled it))
            (not (.isDone it))))))

#?(:cljs (mutil/make-printable js/Promise)
   :clj (mutil/make-printable CompletionStage))

#?(:clj
   (extend-protocol p/IPromiseFactory
     clojure.lang.Fn
     (-promise [func]
       (let [p (CompletableFuture.)
             reject #(.completeExceptionally p %)
             resolve #(.complete p %)]
         (submit #(try
                    (func resolve reject)
                    (catch Throwable e
                      (reject e))))
         p))

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
         p))))

#?(:cljs
   (extend-protocol p/IPromiseFactory
     function
     (-promise [func]
       (js/Promise. func))

     js/Promise
     (-promise [p] p)

     js/Error
     (-promise [e]
       (.reject js/Promise e))

     object
     (-promise [v]
       (.resolve js/Promise v))

     number
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
            (.completeExceptionally p v)
            p)))

(defn promise
  "The promise constructor."
  [v]
  (p/-promise v))

;; Predicates

(defn resolved?
  "Returns true if promise `p` is already fulfilled."
  [p]
  (p/-resolved? p))

(defn rejected?
  "Returns true if promise `p` is already rejected."
  [p]
  (p/-rejected? p))

(defn pending?
  "Returns true if promise `p` is stil pending."
  [p]
  (p/-pending? p))

(defn done?
  "Returns true if promise `p` is already done."
  [p]
  (not (p/-pending? p)))

(defn then
  "A chain helper for promises."
  [p callback]
  (p/-map p callback))

(defn chain
  "A variadic chain operation."
  [p & funcs]
  (reduce #(then %1 %2) p funcs))

(defn catch
  "Catch all promise chain helper."
  ([p callback]
   (p/-catch p callback))
  ([p type callback]
   (p/-catch p (fn [e]
                 (if (instance? type e)
                   (callback e)
                   (throw e))))))

(defn branch
  [p callback errback]
  (-> p
      (p/-map callback)
      (p/-catch errback)))

(defn all
  "Given an array of promises, return a promise
  that is fulfilled  when all the items in the
  array are fulfilled."
  [promises]
  #?(:cljs (then (.all js/Promise (clj->js promises))
                 #(js->clj %))
     :clj (let [xf (map p/-promise)
                ps (into [] xf promises)]
            (then (-> (into-array CompletableFuture ps)
                      (CompletableFuture/allOf))
                  (fn [_]
                    (mapv mp/-extract ps))))))

(defn any
  "Given an array of promises, return a promise
  that is fulfilled when first one item in the
  array is fulfilled."
  [promises]
  #?(:cljs (.any js/Promise (clj->js promises))
     :clj (->> (sequence (map p/-promise) promises)
               (into-array CompletableFuture)
               (CompletableFuture/anyOf))))

(defn promisify
  "Given a nodejs like function that accepts a callback
  as the last argument and return an other function
  that returns a promise."
  [callable]
  (fn [& args]
    (promise (fn [resolve reject]
               (let [args (-> (vec args)
                              (conj resolve))]
                 (try
                   (apply callable args)
                   (catch #?(:clj Exception :cljs js/Error) e
                     (reject e))))))))

#?(:cljs
   (defn timeout
     "Returns a cancellable promise that will be fulfilled
     with this promise's fulfillment value or rejection reason.
     However, if this promise is not fulfilled or rejected
     within `ms` milliseconds, the returned promise is cancelled
     with a TimeoutError"
     ([p t] (.timeout p t))
     ([p t v] (.timeout p t v))))

(defn delay
  "Given a timeout in miliseconds and optional
  value, returns a promise that will fulfilled
  with provided value (or nil) after the
  time is reached."
  ([t] (delay t nil))
  ([t v]
   #?(:cljs (.then (js/Promise.delay t)
                   (constantly v))
      :clj (let [p (CompletableFuture.)]
             (schedule t #(.complete p v))
             p))))

(def ^{:no-doc true}
  promise-context
  (reify
    mp/Context
    (-get-level [_] mc/+level-default+)

    mp/Functor
    (-fmap [mn f mv]
      (p/-map mv f))

    mp/Monad
    (-mreturn [_ v]
      (promise v))

    (-mbind [mn mv f]
      (p/-bind mv f))

    mp/Applicative
    (-pure [_ v]
      (promise v))

    (-fapply [_ pf pv]
      (then (all [pf pv])
            (fn [[f v]]
              (f v))))

    mp/Semigroup
    (-mappend [_ mv mv']
      (p/-map (m/sequence [mv mv'])
              (fn [[mvv mvv']]
                (let [ctx (mp/-get-context mvv)]
                  (mp/-mappend ctx mvv mvv')))))))

