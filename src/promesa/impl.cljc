;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
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

(ns ^:no-doc promesa.impl
  "Implementation of promise protocols."
  (:require [promesa.protocols :as pt]
            [promesa.util :as pu]
            [promesa.exec :as exec])
  #?(:clj (:import
           java.util.concurrent.CompletableFuture
           java.util.concurrent.CompletionStage
           java.util.concurrent.TimeoutException
           java.util.concurrent.ExecutionException
           java.util.concurrent.CompletionException
           java.util.concurrent.Executor
           java.util.function.Function
           java.util.function.Supplier)))

;; --- Global Constants

#?(:cljs (def ^:dynamic *default-promise* js/Promise))

(defn resolved
  [v]
  #?(:cljs (.resolve *default-promise* v)
     :clj (CompletableFuture/completedFuture v)))

(defn rejected
  [v]
  #?(:cljs (.reject *default-promise* v)
     :clj (let [p (CompletableFuture.)]
            (.completeExceptionally ^CompletableFuture p v)
            p)))

;; --- Promise Impl

(defn deferred
  []
  #?(:clj (CompletableFuture.)
     :cljs
     (let [state #js {}
           obj (new *default-promise*
                    (fn [resolve reject]
                      (set! (.-resolve state) resolve)
                      (set! (.-reject state) reject)))]
       (specify! obj
         pt/ICompletable
         (-resolve! [_ v]
           (.resolve state v))
         (-reject! [_ v]
           (.reject state v))))))

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
       (-then
         ([it f] (.then it #(f %)))
         ([it f e] (.then it #(f %))))
       (-mapErr
         ([it f] (.catch it #(f %)))
         ([it f e] (.catch it #(f %))))
       (-thenErr
         ([it f] (.catch it #(f %)))
         ([it f e] (.catch it #(f %))))
       (-handle
         ([it f] (.then it #(f % nil) #(f nil %)))
         ([it f e] (.then it #(f % nil) #(f nil %))))
       (-finally
         ([it f] (.then it #(f % nil) #(f nil %)) it)
         ([it f executor] (.then it #(f % nil) #(f nil %)) it)))))

#?(:cljs
   (extend-promise! js/Promise))

;; This code allows execute `then`, `map` and all the other promise
;; chaining functions on any object if the object is a thenable but
;; does not inherit from js/Promise, this code will automatically
;; coerce it to a js/Promise instance.

#?(:cljs
   (extend-type default
     pt/IPromise
     (-map
       ([it f] (pt/-map (pt/-promise it) f))
       ([it f e] (pt/-map (pt/-promise it) f e)))
     (-bind
       ([it f] (pt/-bind (pt/-promise it) f))
       ([it f e] (pt/-bind (pt/-promise it) f e)))
     (-then
       ([it f] (pt/-then (pt/-promise it) f))
       ([it f e] (pt/-then (pt/-promise it) f e)))
     (-mapErr
       ([it f] (pt/-mapErr (pt/-promise it) f))
       ([it f e] (pt/-mapErr (pt/-promise it) f e)))
     (-thenErr
       ([it f] (pt/-thenErr (pt/-promise it) f))
       ([it f e] (pt/-thenErr (pt/-promise it) f e)))
     (-handle
       ([it f] (pt/-handle (pt/-promise it) f))
       ([it f e] (pt/-handle (pt/-promise it) f e)))
     (-finally
       ([it f] (pt/-finally (pt/-promise it) f))
       ([it f e] (pt/-finally (pt/-promise it) f e)))))

#?(:clj (def fw-identity (pu/->FunctionWrapper identity)))

#?(:clj
   (extend-protocol pt/IPromise
     CompletionStage
     (-map
       ([it f]
        (.thenApply ^CompletionStage it
                    ^Function (pu/->FunctionWrapper f)))

       ([it f executor]
        (.thenApplyAsync ^CompletionStage it
                         ^Function (pu/->FunctionWrapper f)
                         ^Executor (exec/resolve-executor executor))))

     (-bind
       ([it f]
        (.thenCompose ^CompletionStage it
                      ^Function (pu/->FunctionWrapper f)))

       ([it f executor]
        (.thenComposeAsync ^CompletionStage it
                           ^Function (pu/->FunctionWrapper f)
                           ^Executor (exec/resolve-executor executor))))

     (-then
       ([it f]
        (.thenCompose ^CompletionStage it
                      ^Function (pu/->FunctionWrapper (comp pt/-promise f))))

       ([it f executor]
        (.thenComposeAsync ^CompletionStage it
                           ^Function (pu/->FunctionWrapper (comp pt/-promise f))
                           ^Executor (exec/resolve-executor executor))))

     (-mapErr
       ([it f]
        (letfn [(handler [e]
                  (if (instance? CompletionException e)
                    (f (.getCause ^Exception e))
                    (f e)))]
          (.exceptionally ^CompletionStage it
                          ^Function (pu/->FunctionWrapper handler))))

       ([it f executor]
        (letfn [(handler [e]
                  (if (instance? CompletionException e)
                    (f (.getCause ^Exception e))
                    (f e)))]
          ;; ONLY on JDK >= 12 it is there but in jdk<12 will throw an
          ;; error
          (.exceptionallyAsync ^CompletionStage it
                               ^Function (pu/->FunctionWrapper handler)
                               ^Executor (exec/resolve-executor executor)))))

     (-thenErr
       ([it f]
        (letfn [(handler [v e]
                  (if e
                    (if (instance? CompletionException e)
                      (pt/-promise (f (.getCause ^Exception e)))
                      (pt/-promise (f e)))
                    it))]
          (as-> ^CompletionStage it $$
            (.handle $$ ^BiFunction (pu/->BiFunctionWrapper handler))
            (.thenCompose $$ ^Function fw-identity))))

       ([it f executor]
        (letfn [(handler [v e]
                  (if e
                    (if (instance? CompletionException e)
                      (pt/-promise (f (.getCause ^Exception e)))
                      (pt/-promise (f e)))
                    (pt/-promise v)))]
          (as-> ^CompletionStage it $$
            (.handleAsync $$
                          ^BiFunction (pu/->BiFunctionWrapper handler)
                          ^Executor (exec/resolve-executor executor))
            (.thenCompose $$ ^Function fw-identity)))))

     (-handle
       ([it f]
        (as-> ^CompletionStage it $$
          (.handle $$ ^BiFunction (pu/->BiFunctionWrapper (comp pt/-promise f)))
          (.thenCompose $$ ^Function fw-identity)))

       ([it f executor]
        (as-> ^CompletionStage it $$
          (.handleAsync $$
                        ^BiFunction (pu/->BiFunctionWrapper (comp pt/-promise f))
                        ^Executor (exec/resolve-executor executor))
          (.thenCompose $$ ^Function fw-identity))))

     (-finally
       ([it f]
        (.whenComplete ^CompletionStage it
                       ^BiConsumer (pu/->BiConsumerWrapper f)))

       ([it f executor]
        (.whenCompleteAsync ^CompletionStage it
                            ^BiConsumer (pu/->BiConsumerWrapper f)
                            ^Executor (exec/resolve-executor executor))))


     Object
     (-map
       ([it f] (pt/-map (pt/-promise it) f))
       ([it f e] (pt/-map (pt/-promise it) f e)))
     (-bind
       ([it f] (pt/-bind (pt/-promise it) f))
       ([it f e] (pt/-bind (pt/-promise it) f e)))
     (-then
       ([it f] (pt/-then (pt/-promise it) f))
       ([it f e] (pt/-then (pt/-promise it) f e)))
     (-handle
       ([it f] (pt/-handle (pt/-promise it) f))
       ([it f e] (pt/-handle (pt/-promise it) f e)))
     (-mapErr
       ([it f] (pt/-mapErr (pt/-promise it) f))
       ([it f e] (pt/-mapErr (pt/-promise it) f e)))
     (-thenErr
       ([it f] (pt/-thenErr (pt/-promise it) f))
       ([it f e] (pt/-thenErr (pt/-promise it) f e)))
     (-finally
       ([it f] (pt/-finally (pt/-promise it) f))
       ([it f e] (pt/-finally (pt/-promise it) f e)))

     nil
     (-map
       ([it f] (pt/-map (pt/-promise it) f))
       ([it f e] (pt/-map (pt/-promise it) f e)))
     (-bind
       ([it f] (pt/-bind (pt/-promise it) f))
       ([it f e] (pt/-bind (pt/-promise it) f e)))
     (-then
       ([it f] (pt/-then (pt/-promise it) f))
       ([it f e] (pt/-then (pt/-promise it) f e)))
     (-mapErr
       ([it f] (pt/-mapErr (pt/-promise it) f))
       ([it f e] (pt/-mapErr (pt/-promise it) f e)))
     (-thenErr
       ([it f] (pt/-thenErr (pt/-promise it) f))
       ([it f e] (pt/-thenErr (pt/-promise it) f e)))
     (-handle
       ([it f] (pt/-handle (pt/-promise it) f))
       ([it f e] (pt/-handle (pt/-promise it) f e)))
     (-finally
       ([it f] (pt/-finally (pt/-promise it) f))
       ([it f e] (pt/-finally (pt/-promise it) f e)))))

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
       (and (not (.isCompletedExceptionally it))
            (not (.isCancelled it))
            (not (.isDone it))))))

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
