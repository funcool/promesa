;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns ^:no-doc promesa.impl
  "Implementation of promise protocols."
  (:require
   [clojure.core :as c]
   [promesa.protocols :as pt]
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
      java.util.concurrent.Future
      java.util.concurrent.TimeUnit
      java.util.concurrent.TimeoutException
      java.util.function.Function
      java.util.function.Supplier)))

;; --- Global Constants

#?(:clj (set! *warn-on-reflection* true))

(defn promise?
  "Return true if `v` is a promise instance."
  [v]
  (satisfies? pt/IPromise v))

(defn deferred?
  "Return true if `v` is a deferred instance."
  [v]
  (satisfies? pt/ICompletable v))

(defn resolved
  [v]
  #?(:cljs (impl/resolved v)
     :clj (CompletableFuture/completedFuture v)))

(defn rejected
  [v]
  #?(:cljs (impl/rejected v)
     :clj (let [p (CompletableFuture.)]
            (.completeExceptionally ^CompletableFuture p v)
            p)))

#?(:cljs
   (defn coerce
     "Coerce a thenable to built-in promise impl type."
     [v]
     (impl/coerce v)))

(defn all
  [promises]
  #?(:cljs (-> (impl/all (into-array promises))
               (pt/-fmap vec))
     :clj (let [promises (map pt/-promise promises)]
            (-> (CompletableFuture/allOf (into-array CompletableFuture promises))
                (pt/-fmap (fn [_] (mapv pt/-extract promises)))))))

(defn race
  [promises]
  #?(:cljs (impl/race (into-array (map pt/-promise promises)))
     :clj (CompletableFuture/anyOf (into-array CompletableFuture (map pt/-promise promises)))))

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
       (-promise [p] (impl/coerce p)))))


#?(:cljs (extend-promise! js/Promise))
#?(:cljs (extend-promise! impl/PromiseImpl))

#?(:cljs
   (extend-type impl/PromiseImpl
     pt/IPromiseFactory
     (-promise [p] p)

     pt/IPromise
     (-fmap
       ([it f] (.fmap it #(f %)))
       ([it f e] (.fmap it #(f %))))

     (-mcat
       ([it f] (.fbind it #(f %)))
       ([it f executor] (.fbind it #(f %))))

     (-hmap
       ([it f] (.fmap it #(f % nil) #(f nil %)))
       ([it f e] (.fmap it #(f % nil) #(f nil %))))

     (-merr
       ([it f] (.fbind it pt/-promise #(f %)))
       ([it f e] (.fbind it pt/-promise #(f %))))

     (-fnly
       ([it f] (.handle it f) it)
       ([it f executor] (.handle it f) it))

     (-then
       ([it f] (.then it #(f %)))
       ([it f executor] (.then it #(f %))))

     pt/ICompletable
     (-resolve! [it v]
       (.resolve ^js it v))
     (-reject! [it v]
       (.reject ^js it v))

     pt/ICancellable
     (-cancel! [it]
       (.cancel it))
     (-cancelled? [it]
       (.isCancelled it))

     cljs.core/IDeref
     (-deref [it]
       (let [value (unchecked-get it "value")]
         (if (.isRejected it)
           (throw value)
           value)))

     pt/IState
     (-extract
       ([it]
        (unchecked-get it "value"))
       ([it default]
        (if (.isPending it)
          default
          (unchecked-get it "value"))))

     (-resolved? [it]
       (.isResolved it))

     (-rejected? [it]
       (.isRejected it))

     (-pending? [it]
       (.isPending it))))

(defn- unwrap
  ([v]
   (if (promise? v)
     (pt/-mcat v unwrap)
     (pt/-promise v)))
  ([v executor]
   (if (promise? v)
     (pt/-mcat v unwrap executor)
     (pt/-promise v))))

#?(:clj
   (extend-protocol pt/IPromise
     CompletionStage
     (-fmap
       ([it f]
        (.thenApply ^CompletionStage it
                    ^Function (pu/->Function f)))

       ([it f executor]
        (.thenApplyAsync ^CompletionStage it
                         ^Function (pu/->Function f)
                         ^Executor (exec/resolve-executor executor))))

     (-mcat
       ([it f]
        (.thenCompose ^CompletionStage it
                      ^Function (pu/->Function f)))

       ([it f executor]
        (.thenComposeAsync ^CompletionStage it
                           ^Function (pu/->Function f)
                           ^Executor (exec/resolve-executor executor))))

     (-hmap
       ([it f]
        (.handle ^CompletionStage it
                 ^BiFunction (pu/->Function2 f)))

       ([it f executor]
        (.handleAsync ^CompletionStage it
                      ^BiFunction (pu/->Function2 f)
                      ^Executor (exec/resolve-executor executor))))

     (-merr
       ([it f]
        (-> ^CompletionStage it
            (.handle ^BiFunction (pu/->Function2 #(if %2 (f %2) it)))
            (.thenCompose ^Function pu/f-identity)))

       ([it f executor]
        (-> ^CompletionStage it
            (.handleAsync ^BiFunction (pu/->Function2 #(if %2 (f %2) it))
                          ^Executor (exec/resolve-executor executor))
            (.thenCompose ^Function pu/f-identity))))

     (-then
       ([it f]
        (pt/-mcat it (fn [v] (unwrap (f v)))))

       ([it f executor]
        (pt/-mcat it (fn [v] (unwrap (f v) executor)) executor)))

     (-fnly
       ([it f]
        (.whenComplete ^CompletionStage it
                       ^BiConsumer (pu/->Consumer2 f)))

       ([it f executor]
        (.whenCompleteAsync ^CompletionStage it
                            ^BiConsumer (pu/->Consumer2 f)
                            ^Executor (exec/resolve-executor executor))))

     ))

#?(:clj
   (extend-type Future
     pt/ICancellable
     (-cancel! [it]
       (.cancel it true))
     (-cancelled? [it]
       (.isCancelled it))))

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
     (-extract
       ([it]
        (try
          (.getNow it nil)
          (catch ExecutionException e
            (.getCause e))
          (catch CompletionException e
            (.getCause e))))
       ([it default]
        (try
          (.getNow it default)
          (catch ExecutionException e
            (.getCause e))
          (catch CompletionException e
            (.getCause e)))))

     (-resolved? [it]
       (and (.isDone it)
            (not (.isCompletedExceptionally it))))

     (-rejected? [it]
       (and (.isDone it)
            (.isCompletedExceptionally it)))

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
       ([it]
        (try
          (.get ^CompletableFuture it)
          (catch ExecutionException e
            (throw (.getCause e)))
          (catch CompletionException e
            (throw (.getCause e)))))

       ([it duration]
        (let [ms (if (instance? Duration duration) (inst-ms duration) duration)]
          (try
            (.get ^CompletableFuture it (int ms) TimeUnit/MILLISECONDS)
            (catch ExecutionException e
              (throw (.getCause e)))
            (catch CompletionException e
              (throw (.getCause e)))))))

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
  "#<js/Promise[~]>")

#?(:clj
   (defmethod print-method java.util.concurrent.CompletionStage
     [p ^java.io.Writer writer]
     (let [status (cond
                    (pt/-pending? p)   "pending"
                    (pt/-cancelled? p) "cancelled"
                    (pt/-rejected? p)  "rejected"
                    :else              "resolved")]
       (.write writer ^String (format "#<CompletableFuture[%s:%d]>" status (hash p))))))

#?(:cljs
   (extend-type js/Promise
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer "#<js/Promise[~]>"))))

#?(:cljs
   (extend-type impl/PromiseImpl
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer (str "#<Promise["
                           (cond
                             (pt/-pending? p)   "pending"
                             (pt/-cancelled? p) "cancelled"
                             (pt/-rejected? p)  "rejected"
                             :else              "resolved")
                           ":" (hash p)
                           "]>")))))
