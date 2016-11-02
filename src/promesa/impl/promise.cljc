;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
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

(ns promesa.impl.promise
  (:require #?(:cljs [org.bluebird])
            [promesa.impl.proto :as pt])
  #?(:clj (:import java.util.concurrent.CompletableFuture
                   java.util.concurrent.CompletionStage
                   java.util.concurrent.TimeoutException
                   java.util.concurrent.ExecutionException
                   java.util.concurrent.CompletionException
                   java.util.concurrent.Executor
                   java.util.concurrent.Executors
                   java.util.concurrent.ForkJoinPool
                   java.util.function.Function
                   java.util.function.Supplier)))

;; --- Global Constants

#?(:cljs
   (def ^:const Promise (js/Promise.noConflict)))

#?(:cljs
   (.config Promise #js {:cancellation true
                         :warnings false}))

#?(:clj
   (def ^:redef +executor+
     (ForkJoinPool/commonPool)))

;; --- Promise Impl

#?(:cljs
   (extend-type Promise
     pt/ICancellable
     (-cancel [it]
       (.cancel it))
     (-cancelled? [it]
       (.isCancelled it))

     pt/IPromise
     (-map [it cb]
       (.then it #(cb %)))
     (-bind [it cb]
       (.then it #(cb %)))
     (-catch [it cb]
       (.catch it #(cb %)))

     pt/IState
     (-extract [it]
       (if (.isRejected it)
         (.reason it)
         (.value it)))
     (-resolved? [it]
       (.isFulfilled it))
     (-rejected? [it]
       (.isRejected it))
     (-pending? [it]
       (.isPending it))))

(declare resolved)

#?(:cljs
   (extend-type default
     pt/IPromise
     (-map [it cb]
       (pt/-map (resolved it) cb))
     (-bind [it cb]
       (pt/-bind (resolved it) cb))
     (-catch [it cb]
       (pt/-catch (resolved it) cb))))

#?(:clj
   (extend-type CompletableFuture
     pt/ICancellable
     (-cancel [it]
       (.cancel it true))
     (-cancelled? [it]
       (.isCancelled it))

     pt/IPromise
     (-map [it cb]
       (let [binds (clojure.lang.Var/getThreadBindingFrame)
             func (reify Function
                    (apply [_ v]
                      (clojure.lang.Var/resetThreadBindingFrame binds)
                      (cb v)))]
         (.thenApplyAsync it ^Function func ^Executor +executor+)))

     (-bind [it cb]
       (let [binds (clojure.lang.Var/getThreadBindingFrame)
             func (reify Function
                    (apply [_ v]
                      (clojure.lang.Var/resetThreadBindingFrame binds)
                      (cb v)))]
         (.thenComposeAsync it ^Function func ^Executor +executor+)))

     (-catch [it cb]
       (let [binds (clojure.lang.Var/getThreadBindingFrame)
             func (reify Function
                    (apply [_ e]
                      (clojure.lang.Var/resetThreadBindingFrame binds)
                      (if (instance? CompletionException e)
                        (cb (.getCause ^Exception e))
                        (cb e))))]
         (.exceptionally it ^Function func)))

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

;; --- Promise Factory Impl

(defn resolved
  [v]
  #?(:cljs (.resolve Promise v)
     :clj (let [p (CompletableFuture.)]
            (.complete p v)
            p)))

(defn rejected
  [v]
  #?(:cljs (.reject Promise v)
     :clj (let [p (CompletableFuture.)]
            (.completeExceptionally p v)
            p)))

#?(:clj
   (extend-protocol pt/IPromiseFactory
     clojure.lang.Fn
     (-promise [func]
       (let [p (CompletableFuture.)
             reject #(.completeExceptionally p %)
             resolve #(.complete p %)]
         (try
           (func resolve reject)
           (catch Throwable e
             (reject e)))
         p))

     Throwable
     (-promise [e]
       (rejected e))

     CompletionStage
     (-promise [cs] cs)

     Object
     (-promise [v]
       (resolved v))

     nil
     (-promise [v]
       (resolved v)))

   :cljs
   (extend-protocol pt/IPromiseFactory
     function
     (-promise [func]
       (Promise. func))

     Promise
     (-promise [p] p)

     js/Error
     (-promise [e]
       (rejected e))

     object
     (-promise [v]
       (resolved v))

     number
     (-promise [v]
       (resolved v))

     boolean
     (-promise [v]
       (resolved v))

     string
     (-promise [v]
       (resolved v))

     nil
     (-promise [v]
       (resolved v))))

;; --- Pretty printing

(defn promise->str
  [p]
  (str "#<Promise["
       (cond
         (pt/-pending? p) "~"
         (pt/-rejected? p) (str "error=" (pt/-extract p))
         :else (str "value=" (pt/-extract p)))
       "]>"))

#?(:clj
   (defmethod print-method java.util.concurrent.CompletionStage
     [p ^java.io.Writer writer]
     (.write writer ^String (promise->str p)))
   :cljs
   (extend-type Promise
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer (promise->str p)))))
