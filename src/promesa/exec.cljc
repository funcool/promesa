;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>
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
  (:refer-clojure :exclude [run!])
  (:require [promesa.protocols :as pt]
            [promesa.util :as pu]
            #?(:cljs [goog.object :as gobj]))
  #?(:clj
     (:import
      java.util.concurrent.atomic.AtomicLong
      java.util.concurrent.Callable
      java.util.concurrent.CompletableFuture
      java.util.concurrent.Executor
      java.util.concurrent.ExecutorService
      java.util.concurrent.Executors
      java.util.concurrent.ForkJoinPool
      java.util.concurrent.ForkJoinPool$ForkJoinWorkerThreadFactory
      java.util.concurrent.Future
      java.util.concurrent.ScheduledExecutorService
      java.util.concurrent.ThreadFactory
      java.util.concurrent.TimeUnit
      java.util.concurrent.TimeoutException
      java.util.function.Supplier)))

;; --- Globals & Defaults (with CLJS Impl)

#?(:clj (declare scheduled-pool)
   :cljs (declare ->ScheduledExecutor))

#?(:cljs (declare ->MicrotaskExecutor))

(declare ->CurrentThreadExecutor)

(defonce default-scheduler
  (delay #?(:clj (scheduled-pool)
            :cljs (->ScheduledExecutor))))

(defonce default-executor
  (delay #?(:clj (ForkJoinPool/commonPool)
            :cljs (->MicrotaskExecutor))))

(defonce current-thread-executor
  (delay (->CurrentThreadExecutor)))

(defn resolve-executor
  ([] (if (delay? default-executor) @default-executor default-executor))
  ([executor] (if (delay? executor) @executor executor)))

(defn resolve-scheduler
  ([] (if (delay? default-scheduler) @default-scheduler default-scheduler))
  ([scheduler] (if (delay? scheduler) @scheduler scheduler)))

#?(:clj
   (defonce processors
     (delay (.availableProcessors (Runtime/getRuntime)))))

;; --- Public Api

(defn run!
  "Run the task in the provided executor."
  ([task] (pt/-run! (resolve-executor) task))
  ([executor task] (pt/-run! (resolve-executor executor) task)))

(defn submit!
  "Submit a task to be executed in a provided executor
  and return a promise that will be completed with
  the return value of a task.

  A task is a plain clojure function."
  ([task]
   (pt/-submit! (resolve-executor) task))
  ([executor task]
   (pt/-submit! (resolve-executor executor) task)))

(defn schedule!
  "Schedule a callable to be executed after the `ms` delay
  is reached.

  In JVM it uses a scheduled executor service and in JS
  it uses the `setTimeout` function."
  ([ms task]
   (pt/-schedule! (resolve-scheduler) ms task))
  ([scheduler ms task]
   (pt/-schedule! (resolve-scheduler scheduler) ms task)))

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
     "A scheduled thread pool constructor."
     ([] (Executors/newScheduledThreadPool (int 0)))
     ([n] (Executors/newScheduledThreadPool (int n)))
     ([n opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newScheduledThreadPool (int n) factory)))))

#?(:clj
   (defn work-stealing-pool
     "Creates a work-stealing thread pool."
     ([] (Executors/newWorkStealingPool))
     ([n] (Executors/newWorkStealingPool (int n)))))

#?(:clj
   (defn forkjoin-pool
     [{:keys [factory async? parallelism]
       :or {async? true}
       :as opts}]
     (let [parallelism (or parallelism @processors)
           factory (cond
                     (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
                     (nil? factory) ForkJoinPool/defaultForkJoinWorkerThreadFactory
                     :else (throw (ex-info "Unexpected thread factory" {:factory factory})))]
       (ForkJoinPool. (or parallelism @processors) factory nil async?))))


;; --- Impl

#?(:clj
   (defn- thread-factory-adapter
     "Adapt a simple clojure function into a
     ThreadFactory instance."
     [func]
     (reify ThreadFactory
       (^Thread newThread [_ ^Runnable runnable]
        (func runnable)))))

#?(:clj
   (defn counted-thread-factory
     [name daemon]
     (let [along (AtomicLong. 0)]
       (reify ThreadFactory
         (newThread [this runnable]
           (doto (Thread. ^Runnable runnable)
             (.setDaemon ^Boolean daemon)
             (.setName (format name (.getAndIncrement along)))))))))

#?(:clj
   (defn forkjoin-named-thread-factory
     [name]
     (reify ForkJoinPool$ForkJoinWorkerThreadFactory
       (newThread [this pool]
         (let [wth (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)]
           (.setName wth (str name ":" (.getPoolIndex wth)))
           wth)))))

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
     Executor
     (-run! [this f]
       (CompletableFuture/runAsync ^Runnable f
                                   ^Executor this))
     (-submit! [this f]
       (CompletableFuture/supplyAsync ^Supplier (pu/->SupplierWrapper f)
                                      ^Executor this))))



;; Default executor that executes cljs/js tasks in the microtask
;; queue.
#?(:cljs
   (deftype MicrotaskExecutor []
     pt/IExecutor
     (-run! [this f]
       (-> (pt/-promise nil)
           (pt/-map (fn [_] (f) nil))
           (pt/-mapErr (fn [e] (js/setTimeout #(throw e)) nil))))

     (-submit! [this f]
       (-> (pt/-promise nil)
           (pt/-map (fn [_] (f)))
           (pt/-mapErr (fn [e] (js/setTimeout #(throw e)) nil))))))

;; Executor that executes the task in the calling thread
#?(:clj
   (deftype CurrentThreadExecutor []
     Executor
     (^void execute [_ ^Runnable f]
       (.run f)))

   :cljs
   (deftype CurrentThreadExecutor []
     pt/IExecutor
     (-run! [this f]
       (f)
       (pt/-promise nil))

     (-submit! [this f]
       (pt/-promise (f)))))

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
     (-cancel! [_]
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
     (-cancel! [self]
       (when-not (pt/-cancelled? self)
         (let [cancel-fn (gobj/get state "cancel-fn")]
           (gobj/set state "cancelled" true)
           (cancel-fn))))))

#?(:clj
   (extend-type ScheduledExecutorService
     pt/IScheduler
     (-schedule! [this ms f]
       (let [fut (.schedule this ^Callable f ^long ms TimeUnit/MILLISECONDS)]
         (ScheduledTask. fut)))))

#?(:cljs
   (deftype ScheduledExecutor []
     pt/IScheduler
     (-schedule! [_ ms f]
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
