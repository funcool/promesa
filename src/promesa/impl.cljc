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

(ns promesa.impl
  "Implementation of promise protocols."
  (:require [promesa.protocols :as pt])
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

#?(:clj
   (def ^:dynamic *executor* (ForkJoinPool/commonPool)))

#?(:cljs
   (def ^:dynamic *default-promise* js/Promise))

;; --- Promise Impl

(declare resolved)

#?(:cljs
   (defn extend-promise!
     [t]
     (extend-type t
       pt/IPromiseFactory
       (-promise [p] p)

       pt/IPromise
       (-map [it cb]
         (.then it #(cb %)))
       (-bind [it cb]
         (.then it #(cb %)))
       (-catch [it cb]
         (.catch it #(cb %))))))

#?(:cljs (extend-promise! js/Promise))

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
         (.thenApplyAsync it ^Function func ^Executor *executor*)))

     (-bind [it cb]
       (let [binds (clojure.lang.Var/getThreadBindingFrame)
             func (reify Function
                    (apply [_ v]
                      (clojure.lang.Var/resetThreadBindingFrame binds)
                      (cb v)))]
         (.thenComposeAsync it ^Function func ^Executor *executor*)))

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
  #?(:cljs (.resolve *default-promise* v)
     :clj (let [p (CompletableFuture.)]
            (.complete p v)
            p)))

(defn rejected
  [v]
  #?(:cljs (.reject *default-promise* v)
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
       (new *default-promise* func))

     js/Error
     (-promise [e]
       (rejected e))

     default
     (-promise [v]
       (resolved v))))

(defn empty-promise
  []
  #?(:clj (CompletableFuture.)
     :cljs
     (let [state (volatile! {})
           obj (new *default-promise*
                  (fn [resolve reject]
                    (vreset! state
                             {:resolve resolve
                              :reject reject})))]
       (specify! obj
         pt/ICompletable
         (-resolve [_ v]
           ((:resolve @state) v))
         (-reject [_ v]
           ((:reject @state) v))))))

#?(:clj
   (extend-protocol pt/ICompletable
     CompletableFuture
     (-resolve [f v] (.complete f v))
     (-reject [f v] (.completeExceptionally f v))))

;; --- Pretty printing

(defn promise->str
  [p]
  "#<Promise[~]>")

#?(:clj
   (defmethod print-method java.util.concurrent.CompletionStage
     [p ^java.io.Writer writer]
     (.write writer ^String (promise->str p))))

#?(:cljs
   (extend-type js/Promise
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer (promise->str p)))))
