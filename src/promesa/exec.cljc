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
      java.util.concurrent.Callable
      java.util.concurrent.CompletableFuture
      java.util.concurrent.Executor
      java.util.concurrent.ExecutorService
      java.util.concurrent.Executors
      java.util.concurrent.ForkJoinPool
      java.util.concurrent.ForkJoinPool$ForkJoinWorkerThreadFactory
      java.util.concurrent.ForkJoinWorkerThread
      java.util.concurrent.Future
      java.util.concurrent.ScheduledExecutorService
      java.util.concurrent.ThreadFactory
      java.util.concurrent.TimeUnit
      java.util.concurrent.TimeoutException
      java.util.concurrent.atomic.AtomicLong
      java.util.function.Supplier)))

(set! *warn-on-reflection* true)

;; --- Globals & Defaults (with CLJS Impl)

#?(:clj (declare scheduled-pool)
   :cljs (declare ->ScheduledExecutor))

#?(:cljs (declare ->MicrotaskExecutor))
(declare ->SameThreadExecutor)

(defonce ^:dynamic default-scheduler
  (delay #?(:clj (scheduled-pool)
            :cljs (->ScheduledExecutor))))

(defonce ^:dynamic default-executor
  (delay #?(:clj (ForkJoinPool/commonPool)
            :cljs (->MicrotaskExecutor))))

(defonce same-thread-executor
  (delay (->SameThreadExecutor)))

(defn executor?
  [o]
  #?(:clj (instance? Executor o)
     :cljs (satisfies? pt/IExecutor o)))

(defn resolve-executor
  ([] (resolve-executor default-executor))
  ([executor]
   (if (= :default executor)
     (resolve-executor default-executor)
     (cond-> executor (delay? executor) deref))))

(defn resolve-scheduler
  ([] (resolve-scheduler default-scheduler))
  ([scheduler]
   (if (= :default scheduler)
     (resolve-scheduler default-scheduler)
     (cond-> scheduler (delay? scheduler) deref))))

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

;; --- Pool & Thread Factories

#?(:clj
   (defn thread-factory?
     [o]
     (instance? ThreadFactory o)))

#?(:clj
   (defn- fn->thread-factory
     "Adapt a simple clojure function into a ThreadFactory instance."
     [func]
     (reify ThreadFactory
       (^Thread newThread [_ ^Runnable runnable]
        (func runnable)))))

#?(:clj
   (defn default-thread-factory
     [& {:keys [name daemon priority]
         :or {daemon true priority Thread/NORM_PRIORITY}}]
     (let [^AtomicLong along (AtomicLong. 0)]
       (reify ThreadFactory
         (newThread [this runnable]
           (doto (Thread. ^Runnable runnable)
             (.setPriority priority)
             (.setDaemon ^Boolean daemon)
             (.setName (format name (.getAndIncrement along)))))))))

#(:clj
  (defn default-forkjoin-thread-factory
    ^ForkJoinPool$ForkJoinWorkerThreadFactory
    [& {:keys [name daemon] :or {name "promesa/forkjoin/%s" daemon true}}]
    (let [^AtomicLong counter (AtomicLong. 0)]
      (reify ForkJoinPool$ForkJoinWorkerThreadFactory
        (newThread [_ pool]
          (let [thread (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)
                tname  (format name (.getAndIncrement counter))]
            (.setName ^ForkJoinWorkerThread thread ^String tname)
            (.setDaemon ^ForkJoinWorkerThread thread ^Boolean daemon)
            thread))))))

#?(:clj
   (defn- opts->thread-factory
     [{:keys [daemon priority]
       :or {daemon true priority Thread/NORM_PRIORITY}}]
     (fn->thread-factory
      (fn [runnable]
        (let [thread (Thread. ^Runnable runnable)]
          (.setDaemon thread daemon)
          (.setPriority thread priority)
          thread)))))

#?(:clj
   (defn- resolve-thread-factory
     ^ThreadFactory
     [opts]
     (cond
       (thread-factory? opts) opts
       (= :default opts)      (default-thread-factory)
       (nil? opts)            (default-thread-factory)
       (map? opts)            (default-thread-factory opts)
       (fn? opts)             (fn->thread-factory opts)
       :else                  (throw (ex-info "Invalid thread factory" {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; --- DEPRECATED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn cached-pool
     "A cached thread pool constructor."
     {:deprecated "9.0"}
     ([]
      (Executors/newCachedThreadPool))
     ([opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newCachedThreadPool factory)))))

#?(:clj
   (defn fixed-pool
     "A fixed thread pool constructor."
     {:deprecated "9.0"}
     ([n]
      (Executors/newFixedThreadPool (int n)))
     ([n opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newFixedThreadPool (int n) factory)))))

#?(:clj
   (defn single-pool
     "A single thread pool constructor."
     {:deprecated "9.0"}
     ([]
      (Executors/newSingleThreadExecutor))
     ([opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newSingleThreadExecutor factory)))))

#?(:clj
   (defn scheduled-pool
     "A scheduled thread pool constructor."
     {:deprecated "9.0"}
     ([] (Executors/newScheduledThreadPool (int 0)))
     ([n] (Executors/newScheduledThreadPool (int n)))
     ([n opts]
      (let [factory (resolve-thread-factory opts)]
        (Executors/newScheduledThreadPool (int n) factory)))))

#?(:clj
   (defn work-stealing-pool
     "Creates a work-stealing thread pool."
     {:deprecated "9.0"}
     ([] (Executors/newWorkStealingPool))
     ([n] (Executors/newWorkStealingPool (int n)))))

#?(:clj
   (defn forkjoin-pool
     {:deprecated "9.0"}
     [{:keys [factory async? parallelism]
       :or {async? true}
       :as opts}]
     (let [parallelism (or parallelism @processors)
           factory (cond
                     (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
                     (nil? factory) ForkJoinPool/defaultForkJoinWorkerThreadFactory
                     :else (throw (ex-info "Unexpected thread factory" {:factory factory})))]
       (ForkJoinPool. (or parallelism @processors) factory nil async?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; --- END DEPRECATED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn cached-executor
     "A cached thread executor pool constructor."
     [& {:keys [factory]}]
     (let [factory (resolve-thread-factory factory)]
       (Executors/newCachedThreadPool factory))))

#?(:clj
   (defn fixed-executor
     "A fixed thread executor pool constructor."
     [& {:keys [parallelism factory]}]
     (let [factory (resolve-thread-factory factory)]
       (Executors/newFixedThreadPool (int parallelism) factory))))

#?(:clj
   (defn single-executor
     "A single thread executor pool constructor."
     [& {:keys [factory]}]
     (let [factory (resolve-thread-factory factory)]
       (Executors/newSingleThreadExecutor factory))))

#?(:clj
   (defn scheduled-executor
     "A scheduled thread pool constructor."
     [& {:keys [parallelism factory] :or {parallelism 1}}]
     (let [factory (resolve-thread-factory factory)]
       (Executors/newScheduledThreadPool (int parallelism) factory))))

#?(:clj
   (defn work-stealing-executor
     "Creates a work-stealing thread pool."
     [& {:keys [parallelism]}]
     (Executors/newWorkStealingPool (int parallelism))))

#?(:clj
   (defn forkjoin-executor
     [& {:keys [factory async? parallelism] :or {async? true}}]
     (let [parallelism (or parallelism @processors)
           factory     (cond
                         (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
                         (nil? factory) (default-forkjoin-thread-factory)
                         :else (throw (ex-info "Unexpected thread factory" {:factory factory})))]
       (ForkJoinPool. (int (or parallelism @processors)) factory nil async?))))

#?(:clj
   (extend-protocol pt/IExecutor
     Executor
     (-run! [this f]
       (CompletableFuture/runAsync ^Runnable f
                                   ^Executor this))
     (-submit! [this f]
       (CompletableFuture/supplyAsync ^Supplier (pu/->SupplierWrapper f)
                                      ^Executor this)
       )))

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
   (deftype SameThreadExecutor []
     Executor
     (^void execute [_ ^Runnable f]
       (.run f)))

   :cljs
   (deftype SameThreadExecutor []
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

(defmacro with-dispatch
  "Helper macro for dispatch execution of the body to an executor
  service. The returned promise is not cancellable (the body will be
  executed independently of the cancellation)."
  [executor & body]
  `(-> (submit! ~executor (^:once fn* [] (pt/-promise (do ~@body))))
       (pt/-bind identity)))
