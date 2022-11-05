;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec
  "Executors & Schedulers facilities."
  (:refer-clojure :exclude [run!])
  (:require [promesa.protocols :as pt]
            [promesa.util :as pu]
            #?(:cljs [goog.object :as gobj]))
  #?(:clj
     (:import
      java.lang.AutoCloseable
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

#?(:clj (set! *warn-on-reflection* true))

;; --- Globals & Defaults (with CLJS Impl)

#?(:clj (declare scheduled-pool)
   :cljs (declare ->ScheduledExecutor))

#?(:clj (declare cached-executor)
   :cljs (declare ->MicrotaskExecutor))

(declare ->SameThreadExecutor)

(defonce ^:dynamic *default-scheduler*
  (delay #?(:clj (scheduled-pool)
            :cljs (->ScheduledExecutor))))

(defonce ^:dynamic *default-executor*
  (delay #?(:clj (ForkJoinPool/commonPool)
            :cljs (->MicrotaskExecutor))))

(defonce same-thread-executor
  (delay (->SameThreadExecutor)))

(def vthreads-supported?
  #?(:clj (and (pu/has-method? Thread "startVirtualThread")
               (try
                 (eval '(Thread/startVirtualThread (constantly nil)))
                 true
                 (catch Throwable cause
                   false)))
     :cljs false))

(defonce ^:dynamic *vthread-executor*
  #?(:clj (when vthreads-supported?
            (delay (eval '(java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))))
     :cljs (delay (->MicrotaskExecutor))))

(defonce ^:dynamic *thread-executor*
  #?(:clj  (delay (cached-executor))
     :cljs (delay (->MicrotaskExecutor))))

(defn executor?
  [o]
  #?(:clj (instance? Executor o)
     :cljs (satisfies? pt/IExecutor o)))

(defn resolve-executor
  ([] (resolve-executor :default))
  ([executor]
   (case executor
     :default (pu/maybe-deref *default-executor*)
     :thread  (pu/maybe-deref *thread-executor*)
     :vthread (do
                #?(:clj
                   (when (nil? *vthread-executor*)
                     (throw (UnsupportedOperationException. "vthreads not available"))))
                (pu/maybe-deref *vthread-executor*))
     (pu/maybe-deref executor))))

(defn resolve-scheduler
  ([] (resolve-scheduler :default))
  ([scheduler]
   (if (= :default scheduler)
     (pu/maybe-deref *default-scheduler*)
     (pu/maybe-deref scheduler))))

#?(:clj
   (defn- get-available-processors
     []
     (.availableProcessors (Runtime/getRuntime))))

(defn wrap-bindings
  [f]
  #?(:cljs f
     :clj
     (let [frame (clojure.lang.Var/cloneThreadBindingFrame)]
       (fn
         ([]
          (clojure.lang.Var/resetThreadBindingFrame frame)
          (f))
         ([x]
          (clojure.lang.Var/resetThreadBindingFrame frame)
          (f x))
         ([x y]
          (clojure.lang.Var/resetThreadBindingFrame frame)
          (f x y))
         ([x y z]
          (clojure.lang.Var/resetThreadBindingFrame frame)
          (f x y z))
         ([x y z & args]
          (clojure.lang.Var/resetThreadBindingFrame frame)
          (apply f x y z args))))))

;; --- Public API

(def noop (constantly nil))

(defn run!
  "Run the task in the provided executor."
  ([f]
   (pt/-submit! (resolve-executor) (comp noop f)))
  ([executor f]
   (pt/-submit! (resolve-executor executor) (comp noop f))))

(defn submit!
  "Submit a task to be executed in a provided executor
  and return a promise that will be completed with
  the return value of a task.

  A task is a plain clojure function."
  ([f]
   (pt/-submit! (resolve-executor) f))
  ([executor f]
   (pt/-submit! (resolve-executor executor) f)))

(defn schedule!
  "Schedule a callable to be executed after the `ms` delay
  is reached.

  In JVM it uses a scheduled executor service and in JS
  it uses the `setTimeout` function."
  ([ms f]
   (pt/-schedule! (resolve-scheduler) ms f))
  ([scheduler ms f]
   (pt/-schedule! (resolve-scheduler scheduler) ms f)))

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

#?(:clj (def counter (AtomicLong. 0)))

#?(:clj
   (defn default-thread-factory
     [& {:keys [name daemon priority]
         :or {daemon true
              priority Thread/NORM_PRIORITY
              name "promesa/thread/%s"}}]
     (reify ThreadFactory
       (newThread [this runnable]
         (doto (Thread. ^Runnable runnable)
           (.setPriority priority)
           (.setDaemon ^Boolean daemon)
           (.setName (format name (.getAndIncrement ^AtomicLong counter))))))))

#?(:clj
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
     (let [parallelism (or parallelism (get-available-processors))
           factory     (cond
                         (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
                         (nil? factory) ForkJoinPool/defaultForkJoinWorkerThreadFactory
                         :else (throw (ex-info "Unexpected thread factory" {:factory factory})))]
       (ForkJoinPool. parallelism factory nil async?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; --- END DEPRECATED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn cached-executor
     "A cached thread executor pool constructor."
     [& {:keys [factory]}]
     (let [factory (or (some-> factory resolve-thread-factory)
                       (default-thread-factory :name "promesa/cached/%s"))]
       (Executors/newCachedThreadPool factory))))

#?(:clj
   (defn fixed-executor
     "A fixed thread executor pool constructor."
     [& {:keys [parallelism factory]}]
     (let [factory (or (some-> factory resolve-thread-factory)
                       (default-thread-factory :name "promesa/fixed/%s"))]
       (Executors/newFixedThreadPool (int parallelism) factory))))

#?(:clj
   (defn single-executor
     "A single thread executor pool constructor."
     [& {:keys [factory]}]
     (let [factory (or (some-> factory resolve-thread-factory)
                       (default-thread-factory :name "promesa/single/%s"))]
       (Executors/newSingleThreadExecutor factory))))

#?(:clj
   (defn scheduled-executor
     "A scheduled thread pool constructor."
     [& {:keys [parallelism factory] :or {parallelism 1}}]
     (let [factory (or (some-> factory resolve-thread-factory)
                       (default-thread-factory :name "promesa/scheduled/%s"))]
       (Executors/newScheduledThreadPool (int parallelism) factory))))

#?(:clj
   (when vthreads-supported?
     (eval
      '(defn thread-per-task-executor
         [& {:keys [factory]}]
         (let [factory (or (some-> factory resolve-thread-factory)
                           (default-thread-factory :name "promesa/thread-per-task/%s"))]
           (Executors/newThreadPerTaskExecutor ^ThreadFactory factory))))))

#?(:clj
   (when vthreads-supported?
     (eval
      '(defn vthread-per-task-executor
         []
         (Executors/newVirtualThreadPerTaskExecutor)))))

#?(:clj
   (defn forkjoin-executor
     [& {:keys [factory async? parallelism] :or {async? true}}]
     (let [parallelism (or parallelism (get-available-processors))
           factory     (cond
                         (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
                         (nil? factory) (default-forkjoin-thread-factory)
                         :else (throw (ex-info "Unexpected thread factory" {:factory factory})))]
       (ForkJoinPool. (int parallelism) factory nil async?))))

#?(:clj
   (defn work-stealing-executor
     "An alias for the `forkjoin-executor`."
     [& params]
     (apply forkjoin-executor params)))

#?(:clj
   (defn configure-default-executor!
     [& params]
     (alter-var-root #'*default-executor*
                     (fn [executor]
                       (when (and (delay? executor) (realized? executor))
                         (.close ^AutoCloseable @executor))
                       (when (instance? AutoCloseable executor)
                         (.close ^AutoCloseable executor))
                       (apply forkjoin-executor params)))))

#?(:clj
   (extend-protocol pt/IExecutor
     Executor
     (-submit! [this f]
       (CompletableFuture/supplyAsync ^Supplier (pu/->SupplierWrapper f) ^Executor this))))

;; Default executor that executes cljs/js tasks in the microtask
;; queue.
#?(:cljs
   (deftype MicrotaskExecutor []
     pt/IExecutor
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
