;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>
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

(ns promesa.exec
  "Executors & Schedulers facilities."
  (:require [promesa.protocols :as pt]
            #?(:cljs [goog.object :as gobj]))
  #?(:clj
     (:import
      java.util.function.Supplier
      java.util.concurrent.ForkJoinPool
      java.util.concurrent.Future
      java.util.concurrent.CompletableFuture
      java.util.concurrent.ExecutorService
      java.util.concurrent.TimeoutException
      java.util.concurrent.ThreadFactory
      java.util.concurrent.TimeUnit
      java.util.concurrent.ScheduledExecutorService
      java.util.concurrent.Executors)))

;; --- Globals & Defaults

#?(:clj (declare scheduled-pool)
   :cljs (declare ->ScheduledExecutor))

#?(:cljs (declare ->Executor))

(defonce ^:dynamic *scheduler*
  (delay #?(:clj (scheduled-pool)
            :cljs (->ScheduledExecutor))))

(defonce ^:dynamic *executor*
  (delay #?(:clj (ForkJoinPool/commonPool)
            :cljs (->Executor))))

(defn get-default-executor
  []
  (if (delay? *executor*)
    (deref *executor*)
    *executor*))

(defn get-default-scheduler
  []
  (if (delay? *scheduler*)
    (deref *scheduler*)
    *scheduler*))

;; --- Public Api

(defn submit
  "Submit a task to be executed in a provided executor
  and return a promise that will be completed with
  the return value of a task.

  A task is a plain clojure function."
  ([task]
   (pt/-submit (get-default-executor) task))
  ([executor task]
   (pt/-submit executor task)))

(defn schedule
  "Schedule a callable to be executed after the `ms` delay
  is reached.

  In JVM it uses a scheduled executor service and in JS
  it uses the `setTimeout` function."
  ([ms task]
   (pt/-schedule (get-default-scheduler) ms task))
  ([scheduler ms task]
   (pt/-schedule scheduler ms task)))

;; --- Pool constructorls

(declare resolve-thread-factory)

#?(:clj
   (defn cached-pool
     "A cached thread pool constructor."
     ([]
      (Executors/newCachedThreadPool))
     ([opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newCachedThreadPool factory)))))

#?(:clj
   (defn fixed-pool
     "A fixed thread pool constructor."
     ([n]
      (Executors/newFixedThreadPool (int n)))
     ([n opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newFixedThreadPool (int n) factory)))))

#?(:clj
   (defn single-pool
     "A single thread pool constructor."
     ([]
      (Executors/newSingleThreadExecutor))
     ([opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newSingleThreadExecutor factory)))))


#?(:clj
   (defn scheduled-pool
     "A scheduled thread pool constructo."
     ([] (Executors/newScheduledThreadPool (int 0)))
     ([n] (Executors/newScheduledThreadPool (int 0)))
     ([n opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newScheduledThreadPool (int n) factory)))))

;; --- Impl

#?(:clj
   (deftype RunnableWrapper [f bindings]
     Runnable
     (run [_]
       (clojure.lang.Var/resetThreadBindingFrame bindings)
       (f))))

#?(:clj
   (deftype SupplierWrapper [f bindings]
     Supplier
     (get [_]
       (clojure.lang.Var/resetThreadBindingFrame bindings)
       (f))))

;; #?(:clj
;;    (deftype FunctionWrapper [f bindings]
;;      Function
;;      (apply [_ v]
;;        (clojure.lang.Var/resetThreadBindingFrame bindings)
;;        (f v))))

#?(:clj
   (defn- thread-factory-adapter
     "Adapt a simple clojure function into a
     ThreadFactory instance."
     [func]
     (reify ThreadFactory
       (^Thread newThread [_ ^Runnable runnable]
        (func runnable)))))

#?(:clj
   (defn- thread-factory
     [{:keys [daemon priority]
       :or {daemon true
            priority Thread/NORM_PRIORITY}}]
     (thread-factory-adapter
      (fn [runnable]
        (let [thread (Thread. ^Runnable runnable)]
          (.setDaemon thread daemon)
          (.setPriority thread priority)
          thread)))))

#?(:clj
   (defn- resolve-thread-factory
     [opts]
     (cond
       (map? opts) (thread-factory opts)
       (fn? opts) (thread-factory-adapter opts)
       (instance? ThreadFactory opts) opts
       :else (throw (ex-info "Invalid thread factory" {})))))

#?(:clj
   (extend-protocol pt/IExecutor
     ExecutorService
     (-submit [this f]
       (let [binds (clojure.lang.Var/getThreadBindingFrame)
             supplier (->SupplierWrapper f binds)]
         (CompletableFuture/supplyAsync supplier this))))

   :cljs
   (deftype Executor []
     pt/IExecutor
     (-submit [this f]
       (pt/-map (pt/-promise nil)
                (fn [_] (f))))))

;; --- Scheduler & ScheduledTask

#?(:clj
   (deftype ScheduledTask [^Future fut]
     clojure.lang.IDeref
     (deref [_] (.get fut))

     clojure.lang.IBlockingDeref
     (deref [_ ms default]
       (try
         (.get fut ms TimeUnit/MILLISECONDS)
         (catch TimeoutException e
           default)))

     clojure.lang.IPending
     (isRealized [_] (and (.isDone fut)
                          (not (.isCancelled fut))))

     pt/ICancellable
     (-cancelled? [_]
       (.isCancelled fut))
     (-cancel [_]
       (when-not (.isCancelled fut)
         (.cancel fut true)))

     Future
     (get [_] (.get fut))
     (get [_ timeout unit] (.get fut timeout unit))
     (isCancelled [_] (.isCancelled fut))
     (isDone [_] (.isDone fut))
     (cancel [_ interrupt?] (.cancel fut interrupt?)))

   :cljs
   (deftype ScheduledTask [state]
     cljs.core/IPending
     (-realized? [_]
       (let [done-iref (gobj/get state "done")]
         (deref done-iref)))

     pt/ICancellable
     (-cancelled? [_]
       (gobj/get state "cancelled"))
     (-cancel [self]
       (when-not (pt/-cancelled? self)
         (let [cancel-fn (gobj/get state "cancel-fn")]
           (gobj/set state "cancelled" true)
           (cancel-fn))))))

#?(:clj
   (extend-type ScheduledExecutorService
     pt/IScheduler
     (-schedule [this ms f]
       (let [binds (clojure.lang.Var/getThreadBindingFrame)
             runnable (->RunnableWrapper f binds)
             fut (.schedule this runnable ms TimeUnit/MILLISECONDS)]
         (ScheduledTask. fut))))

   :cljs
   (deftype ScheduledExecutor []
     pt/IScheduler
     (-schedule [_ ms f]
       (let [done (volatile! false)
             task #(try
                     (f)
                     (finally
                       (vreset! done true)))
             tid (js/setTimeout task ms)
             cancel #(js/clearTimeout tid)]
         (->ScheduledTask #js {:done done
                               :cancelled false
                               :cancel-fn cancel})))))
