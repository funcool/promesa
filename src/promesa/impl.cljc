;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns ^:no-doc promesa.impl
  "Implementation of promise protocols."
  (:require [promesa.protocols :as pt]
            [promesa.util :as pu]
            [promesa.exec :as exec]
            #?(:cljs [promesa.impl.promise :as impl]))

  #?(:clj
     (:import
      java.time.Duration
      java.util.concurrent.CompletableFuture
      java.util.concurrent.CompletionException
      java.util.concurrent.CompletionStage
      java.util.concurrent.CountDownLatch
      java.util.concurrent.ExecutionException
      java.util.concurrent.Executor
      java.util.concurrent.TimeUnit
      java.util.concurrent.TimeoutException
      java.util.function.Function
      java.util.function.Supplier)))

;; --- Global Constants

#?(:clj (set! *warn-on-reflection* true))
#?(:cljs (def ^:dynamic *default-promise* impl/PromiseImpl))

(defn resolved
  [v]
  #?(:cljs (.resolve ^js *default-promise* v)
     :clj (CompletableFuture/completedFuture v)))

(defn rejected
  [v]
  #?(:cljs (.reject ^js *default-promise* v)
     :clj (let [p (CompletableFuture.)]
            (.completeExceptionally ^CompletableFuture p v)
            p)))

;; --- Promise Impl

(defn deferred
  []
  #?(:clj (CompletableFuture.)
     :cljs (impl/deferred)))

#?(:cljs
   (defn extend-promise!
     [t]
     (extend-type t
       pt/IPromiseFactory
       (-promise [p] p)

       pt/IPromise
       (-map
         ([it f] (.then it #(f %)))
         ([it f e] (.then it #(f %))))
       (-bind
         ([it f] (.then it #(f %)))
         ([it f e] (.then it #(f %))))
       (-catch
         ([it f] (.catch it #(f %)))
         ([it f e] (.catch it #(f %))))
       (-handle
         ([it f] (.then it #(f % nil) #(f nil %)))
         ([it f e] (.then it #(f % nil) #(f nil %))))
       (-finally
         ([it f] (.then it #(f % nil) #(f nil %)) it)
         ([it f executor] (.then it #(f % nil) #(f nil %)) it)))))

#?(:cljs (extend-promise! js/Promise))
#?(:cljs (extend-promise! impl/PromiseImpl))

#?(:cljs
   (extend-type impl/PromiseImpl
     cljs.core/IDeref
     (-deref [it]
       (let [state (unchecked-get it "state")
             value (unchecked-get it "value")]
         (if (identical? state impl/REJECTED)
           (throw value)
           value)))

     pt/IState
     (-extract [it]
       (unchecked-get it "value"))

     (-resolved? [it]
       (let [state (unchecked-get it "state")]
         (identical? state impl/FULFILLED)))

     (-rejected? [it]
       (let [state (unchecked-get it "state")]
         (identical? state impl/REJECTED)))

     (-pending? [it]
       (let [state (unchecked-get it "state")]
         (identical? state impl/PENDING)))))

#?(:cljs
   (extend-type impl/DeferredImpl
     pt/ICompletable
     (-resolve! [it v]
       (.resolve ^js it v))
     (-reject! [it v]
       (.reject ^js it v))))

#?(:clj
   (extend-protocol pt/IPromise
     CompletionStage
     (-map
       ([it f]
        (.thenApply ^CompletionStage it
                    ^Function (pu/->Function f)))

       ([it f executor]
        (.thenApplyAsync ^CompletionStage it
                         ^Function (pu/->Function f)
                         ^Executor (exec/resolve-executor executor))))

     (-bind
       ([it f]
        (.thenCompose ^CompletionStage it
                      ^Function (pu/->Function f)))

       ([it f executor]
        (.thenComposeAsync ^CompletionStage it
                           ^Function (pu/->Function f)
                           ^Executor (exec/resolve-executor executor))))

     (-catch
       ([it f]
        (-> ^CompletionStage it
            (.handle ^BiFunction (pu/->Function2 #(if %2 (f %2) it)))
            (.thenCompose ^Function pu/f-identity)))

       ([it f executor]
        (-> ^CompletionStage it
            (.handleAsync ^BiFunction (pu/->Function2 #(if %2 (f %2) it))
                          ^Executor (exec/resolve-executor executor))
            (.thenCompose ^Function pu/f-identity))))

     (-handle
       ([it f]
        (.handle ^CompletionStage it
                 ^BiFunction (pu/->Function2 f)))
       ([it f executor]
        (.handleAsync ^CompletionStage it
                      ^BiFunction (pu/->Function2 f)
                      ^Executor (exec/resolve-executor executor))))

     (-finally
       ([it f]
        (.whenComplete ^CompletionStage it
                       ^BiConsumer (pu/->Consumer2 f)))

       ([it f executor]
        (.whenCompleteAsync ^CompletionStage it
                            ^BiConsumer (pu/->Consumer2 f)
                            ^Executor (exec/resolve-executor executor))))

     ))

#?(:clj
   (extend-type CompletableFuture
     pt/ICancellable
     (-cancel! [it]
       (.cancel it true))
     (-cancelled? [it]
       (.isCancelled it))

     pt/ICompletable
     (-resolve! [f v] (.complete f v))
     (-reject! [f v] (.completeExceptionally f v))

     pt/IState
     (-extract [it]
       (try
         (.getNow it nil)
         (catch ExecutionException e
           (.getCause e))
         (catch CompletionException e
           (.getCause e))))

     (-resolved? [it]
       (and (not (.isCompletedExceptionally it))
            (not (.isCancelled it))
            (.isDone it)))

     (-rejected? [it]
       (.isCompletedExceptionally it))

     (-pending? [it]
       (not (.isDone it)))))



#?(:clj
   (extend-protocol pt/IAwaitable
     Thread
     (-await!
       ([it] (.join ^Thread it))
       ([it duration]
        (if (instance? Duration duration)
          (.join ^Thread it ^Duration duration)
          (.join ^Thread it (int duration)))))

     CountDownLatch
     (-await!
       ([it]
        (.await ^CountDownLatch it))
       ([it duration]
        (if (instance? Duration duration)
          (.await ^CountDownLatch it (long (inst-ms duration)) TimeUnit/MILLISECONDS)
          (.await ^CountDownLatch it (long duration) TimeUnit/MILLISECONDS))))

     CompletableFuture
     (-await!
       ([it] (.get ^CompletableFuture it))
       ([it duration]
        (let [ms (if (instance? Duration duration) (inst-ms duration) duration)]
          (.get ^CompletableFuture it (int ms) TimeUnit/MILLISECONDS))))

     CompletionStage
     (-await!
       ([it]
        (pt/-await! (.toCompletableFuture ^CompletionStage it)))
       ([it duration]
        (pt/-await! (.toCompletableFuture ^CompletionStage it) duration)))))

;; --- Promise Factory

;; This code is responsible of coercing the incoming value to a valid
;; promise type. In some cases we will receive a valid promise object,
;; in this case we return it as is. This is useful when you want to
;; `then` or `map` over a plain value that can be o can not be a
;; promise object

#?(:clj
   (extend-protocol pt/IPromiseFactory
     CompletionStage
     (-promise [cs] cs)

     Throwable
     (-promise [e]
       (rejected e))

     Object
     (-promise [v]
       (resolved v))

     nil
     (-promise [v]
       (resolved v)))

   :cljs
   (extend-protocol pt/IPromiseFactory
     js/Error
     (-promise [e]
       (rejected e))

     default
     (-promise [v]
       (resolved v))))

;; --- Pretty printing

(defn promise->str
  [p]
  "#<Promise[~]>")

#?(:clj
   (defmethod print-method java.util.concurrent.CompletionStage
     [p ^java.io.Writer writer]
     (let [status (cond
                    (pt/-pending? p) "pending"
                    (pt/-rejected? p) "rejected"
                    :else "resolved")]
       (.write writer ^String (format "#object[java.util.concurrent.CompletableFuture 0x%h \"%s\"]" (hash p) status)))))

#?(:cljs
   (extend-type js/Promise
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer (promise->str p)))))
