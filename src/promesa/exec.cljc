;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec
  "Executors & Schedulers facilities."
  (:refer-clojure :exclude [run! pmap await])
  (:require
   [promesa.protocols :as pt]
   [promesa.util :as pu]
   #?(:cljs [goog.object :as gobj])
   #?(:cljs [promesa.impl.promise :as impl]))
  #?(:clj
     (:import
      clojure.lang.Var
      java.lang.AutoCloseable
      java.lang.Thread$UncaughtExceptionHandler
      java.time.Duration
      java.time.Instant
      java.time.temporal.TemporalAmount
      java.util.concurrent.BlockingQueue
      java.util.concurrent.Callable
      java.util.concurrent.CancellationException
      java.util.concurrent.CompletableFuture
      java.util.concurrent.CompletionException
      java.util.concurrent.CompletionStage
      java.util.concurrent.CountDownLatch
      java.util.concurrent.ExecutionException
      java.util.concurrent.Executor
      java.util.concurrent.ExecutorService
      java.util.concurrent.Executors
      java.util.concurrent.ForkJoinPool
      java.util.concurrent.ForkJoinPool$ForkJoinWorkerThreadFactory
      java.util.concurrent.ForkJoinPool$ManagedBlocker
      java.util.concurrent.ForkJoinWorkerThread
      java.util.concurrent.Future
      java.util.concurrent.ScheduledExecutorService
      java.util.concurrent.ScheduledThreadPoolExecutor
      java.util.concurrent.SynchronousQueue
      java.util.concurrent.ThreadFactory
      java.util.concurrent.ThreadPoolExecutor
      java.util.concurrent.TimeUnit
      java.util.concurrent.TimeoutException
      java.util.concurrent.atomic.AtomicLong
      java.util.function.Supplier)))

#?(:clj (set! *warn-on-reflection* true))

;; --- Globals & Defaults (with CLJS Impl)

(declare scheduled-executor)
(declare current-thread-executor)

#?(:clj  (declare thread-factory))
#?(:clj  (declare thread-per-task-executor))
#?(:clj  (declare vthread-per-task-executor))
#?(:clj  (declare cached-executor))
#?(:cljs (declare microtask-executor))

(def ^:dynamic *default-scheduler* nil)
(def ^:dynamic *default-executor* nil)

(def virtual-threads-available?
  "Var that indicates the availability of virtual threads."
  #?(:clj (and (pu/has-method? Thread "ofVirtual")
               ;; the following should succeed with the `--enable-preview` java argument:
               ;; eval happens on top level = compile time, which is ok for GraalVM
               (pu/can-eval? '(Thread/ofVirtual)))
     :cljs false))

(def structured-task-scope-available?
  #?(:clj (and (pu/class-exists? "java.util.concurrent.StructuredTaskScope")
               (pu/can-eval? '(java.util.concurrent.StructuredTaskScope.)))
     :cljs false))

(def ^{:no-doc true} noop (constantly nil))

#?(:clj
   (defn get-available-processors
     []
     (.availableProcessors (Runtime/getRuntime))))

(defonce
  ^{:doc "Default scheduled executor instance."}
  default-scheduler
  (delay
    #?(:clj  (scheduled-executor :parallelism (min (int (* (get-available-processors) 0.2)) 2)
                                 :thread-factory {:prefix "promesa/default-scheduler/"})
       :cljs (scheduled-executor))))

(defonce
  ^{:doc "Default executor instance, ForkJoinPool/commonPool in JVM, MicrotaskExecutor on JS."}
  default-executor
  (delay
    #?(:clj  (ForkJoinPool/commonPool)
       :cljs (microtask-executor))))

;; Executor that executes the task in the calling thread
(def ^{:doc "Default Executor instance that runs the task in the same thread."}
  default-current-thread-executor
  (delay (current-thread-executor)))

(defonce
  ^{:doc "A global, cached thread executor service."
    :no-doc true}
  default-cached-executor
  (delay
    #?(:clj  (cached-executor)
       :cljs default-executor)))

(defonce
  ^{:doc "A global, thread per task executor service."
    :no-doc true}
  default-thread-executor
  #?(:clj (pu/with-compile-cond virtual-threads-available?
            (delay (thread-per-task-executor))
            default-cached-executor)
     :cljs default-executor))

(defonce
  ^{:doc "A global, virtual thread per task executor service."
    :no-doc true}
  default-vthread-executor
  #?(:clj  (pu/with-compile-cond virtual-threads-available?
             (delay (vthread-per-task-executor))
             default-cached-executor)
     :cljs default-executor))

(defn executor?
  "Returns true if `o` is an instane of Executor or satisfies IExecutor protocol."
  [o]
  #?(:clj  (or (instance? Executor o)
               (satisfies? pt/IExecutor o))
     :cljs (satisfies? pt/IExecutor o)))

#?(:clj
(defn shutdown!
  "Shutdowns the executor service."
  [^ExecutorService executor]
  (.shutdown executor)))

#?(:clj
(defn shutdown-now!
  "Shutdowns and interrupts the executor service."
  [^ExecutorService executor]
  (.shutdownNow executor)))

#?(:clj
(defn shutdown?
  "Check if execitor is in shutdown state."
  [^ExecutorService executor]
  (.isShutdown executor)))

(defn resolve-executor
  {:no-doc true}
  ([] (resolve-executor nil))
  ([executor]
   (case executor
     :default        @default-executor
     :cached         @default-cached-executor
     :virtual        @default-vthread-executor
     :platform       @default-cached-executor
     :thread         @default-thread-executor
     :vthread        @default-vthread-executor
     :same-thread    @default-current-thread-executor
     :current-thread @default-current-thread-executor
     (cond
       (nil? executor)      @default-executor
       (executor? executor) executor
       (delay? executor)    (resolve-executor @executor)
       :else
       (throw #?(:clj (IllegalArgumentException. "invalid executor")
                 :cljs (js/TypeError. "invalid executor")))))))

(defn resolve-scheduler
  {:no-doc true}
  ([] (resolve-scheduler nil))
  ([scheduler]
   (if (or (nil? scheduler) (= :default scheduler))
     @default-scheduler
     (pu/maybe-deref scheduler))))

(defn wrap-bindings
  {:no-doc true}
  [f]
  #?(:cljs f
     :clj
     (let [bindings (get-thread-bindings)]
       (fn
         ([]
          (push-thread-bindings bindings)
          (try
            (f)
            (finally
              (pop-thread-bindings))))
         ([a]
          (push-thread-bindings bindings)
          (try
            (f a)
            (finally
              (pop-thread-bindings))))
         ([a b]
          (push-thread-bindings bindings)
          (try
            (f a b)
            (finally
              (pop-thread-bindings))))
         ([a b c]
          (push-thread-bindings bindings)
          (try
            (f a b c)
            (finally
              (pop-thread-bindings))))
         ([a b c d]
          (push-thread-bindings bindings)
          (try
            (f a b c d)
            (finally
              (pop-thread-bindings))))
         ([a b c d e]
          (push-thread-bindings bindings)
          (try
            (f a b c d e)
            (finally
              (pop-thread-bindings))))
         ([a b c d e & args]
          (push-thread-bindings bindings)
          (try
            (apply f a b c d e args)
            (finally
              (pop-thread-bindings))))))))

;; --- Public API

(defn exec!
  "Run the task in the provided executor, returns `nil`. Analogous to
  the `(.execute executor f)`. Fire and forget."
  ([f]
   (pt/-exec! (resolve-executor *default-executor*) f))
  ([executor f]
   (pt/-exec! (resolve-executor executor) f)))

(defn run!
  "Run the task in the provided executor."
  ([f]
   (pt/-run! (resolve-executor *default-executor*) f))
  ([executor f]
   (pt/-run! (resolve-executor executor) f)))

(defn submit!
  "Submit a task to be executed in a provided executor
  and return a promise that will be completed with
  the return value of a task.

  A task is a plain clojure function."
  ([f]
   (pt/-submit! (resolve-executor *default-executor*) f))
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
     "Checks if `o` is an instance of ThreadFactory"
     [o]
     (instance? ThreadFactory o)))

#?(:clj
(def ^{:no-doc true :dynamic true}
  *default-counter*
  (AtomicLong. 0)))

#?(:clj
(defn get-next
  "Get next value from atomic long counter"
  {:no-doc true}
  ([] (.getAndIncrement ^AtomicLong *default-counter*))
  ([counter] (.getAndIncrement ^AtomicLong counter))))

#?(:clj
   (defn thread-factory
     "Create a new thread factory instance"
     [& {:keys [name daemon priority prefix virtual]}]
     (pu/with-compile-cond virtual-threads-available?
       (if virtual
         (let [thb (Thread/ofVirtual)
               thb (cond
                     (string? name)   (.name thb ^String name)
                     (string? prefix) (.name thb ^String prefix 0)
                     :else            thb)]
           (.factory thb))

         (let [thb (Thread/ofPlatform)
               thb (if (some? priority)
                     (.priority thb (int priority))
                     thb)
               thb (if (some? daemon)
                     (.daemon thb ^Boolean daemon)
                     (.daemon thb true))
               thb (cond
                     (string? name)   (.name thb ^String name)
                     (string? prefix) (.name thb ^String prefix 0)
                     :else            thb)]
           (.factory thb)))

       (let [counter (AtomicLong. 0)]
         (reify ThreadFactory
           (newThread [this runnable]
             (let [thr (Thread. ^Runnable runnable)]
               (when (some? priority)
                 (.setPriority thr (int priority)))
               (when (some? daemon)
                 (.setDaemon thr ^Boolean daemon))
               (when (string? name)
                 (.setName thr ^String name))
               (when (string? prefix)
                 (.setName thr (str prefix (get-next counter))))
               thr)))))))

#?(:clj
   (defn forkjoin-thread-factory
     ^ForkJoinPool$ForkJoinWorkerThreadFactory
     [& {:keys [name daemon] :or {name "promesa/forkjoin/%s" daemon true}}]
     (let [counter (AtomicLong. 0)]
       (reify ForkJoinPool$ForkJoinWorkerThreadFactory
         (newThread [_ pool]
           (let [thread (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)
                 tname  (format name (get-next counter))]
             (.setName ^ForkJoinWorkerThread thread ^String tname)
             (.setDaemon ^ForkJoinWorkerThread thread ^Boolean daemon)
             thread))))))

#?(:clj
   (defonce default-thread-factory
     (delay (thread-factory :prefix "promesa/platform/"))))

#?(:clj
   (defonce default-vthread-factory
     (delay (thread-factory :prefix "promesa/virtual/" :virtual true))))

#?(:clj
   (defn- resolve-thread-factory
     {:no-doc true}
     ^ThreadFactory
     [tf]
     (cond
       (nil? tf)            nil
       (thread-factory? tf) tf
       (= :default tf)      @default-thread-factory
       (= :platform tf)     @default-thread-factory
       (= :virtual tf)      @default-vthread-factory
       (map? tf)            (thread-factory tf)
       (fn? tf)             (reify ThreadFactory
                              (^Thread newThread [_ ^Runnable runnable]
                               (tf runnable)))
       :else                (throw (ex-info "Invalid thread factory" {})))))

#?(:clj
   (defn- options->thread-factory
     {:no-doc true}
     (^ThreadFactory [options]
      (resolve-thread-factory
       (or (:thread-factory options)
           (:factory options))))

     (^ThreadFactory [options default]
      (resolve-thread-factory
       (or (:thread-factory options)
           (:factory options)
           default)))))

#?(:clj
   (defn cached-executor
     "A cached thread executor pool constructor."
     [& {:keys [max-size keepalive]
         :or {keepalive 60000 max-size Integer/MAX_VALUE}
         :as options}]
     (let [factory (or (options->thread-factory options)
                       (deref default-thread-factory))
           queue  (SynchronousQueue.)]
       (ThreadPoolExecutor. 0
                            (long max-size)
                            (long keepalive)
                            TimeUnit/MILLISECONDS
                            ^BlockingQueue queue
                            ^ThreadFactory factory))))

#?(:clj
   (defn fixed-executor
     "A fixed thread executor pool constructor."
     [& {:keys [parallelism]
         :as options}]
     (if-let [factory (options->thread-factory options)]
       (Executors/newFixedThreadPool (int parallelism) ^ThreadFactory factory)
       (Executors/newFixedThreadPool (int parallelism)))))

#?(:clj
   (defn single-executor
     "A single thread executor pool constructor."
     [& {:as options}]
     (if-let [factory (options->thread-factory options)]
       (Executors/newSingleThreadExecutor factory)
       (Executors/newSingleThreadExecutor))))

#?(:cljs
   (deftype Scheduler []
     pt/IScheduler
     (-schedule! [_ ms f]
       (let [df  (impl/deferred)
             tid (js/setTimeout
                  (fn []
                    (try
                      (pt/-resolve! df (f))
                      (catch :default cause
                        (pt/-reject! df cause))))
                  ms)]
         (pt/-fnly df
                   (fn [_ c]
                     (when (impl/isCancellationError c)
                       (js/clearTimeout tid))))
         df))))

(defn scheduled-executor
  "A scheduled thread pool constructor. A ScheduledExecutor (IScheduler
  in CLJS) instance allows execute asynchronous tasks some time later."
  [& {:keys [parallelism] :or {parallelism 1} :as options}]
  #?(:clj
     (let [parallelism (or parallelism (get-available-processors))
           factory     (options->thread-factory options)
           executor    (if factory
                         (ScheduledThreadPoolExecutor. (int parallelism) ^ThreadFactory factory)
                         (ScheduledThreadPoolExecutor. (int parallelism)))]
       (.setRemoveOnCancelPolicy executor true)
       executor)

     :cljs
     (->Scheduler)))

(defn current-thread-executor
  "Creates an executor instance that run tasks in the same thread."
  []
  #?(:clj
     (reify
       Executor
       (^void execute [_ ^Runnable f] (.run f)))

     :cljs
     (reify
       pt/IExecutor
       (-exec! [this f]
         (try
           (f)
           nil
           (catch :default _
             nil)))

       (-run! [this f]
         (try
           (pt/-promise (comp noop f))
           (catch :default cause
             (pt/-promise cause))))

       (-submit! [this f]
         (try
           (pt/-promise (f))
           (catch :default cause
             (pt/-promise cause)))))))

#?(:cljs
   (defn microtask-executor
     "An IExecutor that schedules tasks to be executed in the MicrotasksQueue."
     []
     (reify
       pt/IExecutor
       (-exec! [this f]
         (impl/nextTick f))

       (-run! [this f]
         (-> (pt/-promise nil)
             (pt/-fmap (fn [_]
                        (try (f) (catch :default _ nil))))
             (pt/-fmap noop)))

       (-submit! [this f]
         (-> (pt/-promise nil)
             (pt/-fmap (fn [_] (f))))))))

#?(:clj
   (pu/with-compile-cond virtual-threads-available?
     (defn thread-per-task-executor
       [& {:as options}]
       (let [factory (or (options->thread-factory options)
                         (deref default-thread-factory))]
         (Executors/newThreadPerTaskExecutor ^ThreadFactory factory)))))

#?(:clj
   (pu/with-compile-cond virtual-threads-available?
     (defn vthread-per-task-executor
       []
       (Executors/newVirtualThreadPerTaskExecutor))))

#?(:clj
   (defn forkjoin-executor
     [& {:keys [factory async parallelism keepalive core-size max-size]
         :or {max-size 0x7fff async true keepalive 60000}}]
     (let [parallelism (or parallelism (get-available-processors))
           core-size   (or core-size parallelism)
           factory     (cond
                         (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
                         (nil? factory) (forkjoin-thread-factory)
                         :else (throw (UnsupportedOperationException. "Unexpected thread factory")))]
       (ForkJoinPool. (int parallelism)
                      ^ForkJoinPool$ForkJoinWorkerThreadFactory factory
                      nil
                      async
                      (int core-size)
                      (int max-size)
                      1,
                      nil
                      (long keepalive)
                      TimeUnit/MILLISECONDS))))

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
   (extend-type Executor
     pt/IExecutor
     (-exec! [this f]
       (.execute ^Executor this ^Runnable f))

     (-run! [this f]
       (CompletableFuture/runAsync ^Runnable f ^Executor this))

     (-submit! [this f]
       (CompletableFuture/supplyAsync ^Supplier (pu/->Supplier f) ^Executor this))))


;; --- Scheduler

#?(:clj
   (extend-type ScheduledExecutorService
     pt/IScheduler
     (-schedule! [this ms f]
       (let [ms  (if (instance? Duration ms)
                   (.toMillis ^Duration ms)
                   ms)
             df  (CompletableFuture.)
             fut (.schedule this
                            ^Runnable (fn []
                                        (try
                                          (pt/-resolve! df (f))
                                          (catch Throwable cause
                                            (pt/-reject! df cause))))
                            (long ms)
                            TimeUnit/MILLISECONDS)]

         (pt/-fnly df
                   (fn [_ c]
                     (when (instance? CancellationException c)
                       (pt/-cancel! fut))))
         df))))

(defmacro with-dispatch
  "Helper macro for dispatch execution of the body to an executor
  service. The returned promise is not cancellable (the body will be
  executed independently of the cancellation)."
  [executor & body]
  `(-> (submit! ~executor (wrap-bindings (^:once fn* [] ~@body)))
       (pt/-mcat pt/-promise)))

(defmacro with-dispatch!
  "Blocking version of `with-dispatch`. Useful when you want to
  dispatch a blocking operation to a separated thread and join current
  thread waiting for result; effective when current thread is virtual
  thread."
  [executor & body]
  (when (:ns &env)
    (throw (ex-info "cljs not supported on with-dispatch! macro" {})))
  `(-> (submit! ~executor (wrap-bindings (^:once fn* [] ~@body)))
       (pt/-mcat pt/-promise)
       (pt/-await!)))

(defmacro with-executor
  "Binds the *default-executor* var with the provided executor,
  executes the macro body. It also can optionally shutdown or shutdown
  and interrupt on termination if you provide `^:shutdown` and
  `^:interrupt` metadata.

  **EXPERIMENTAL API:** This function should be considered
  EXPERIMENTAL and may be changed or removed in future versions until
  this notification is removed."
  [executor & body]
  (let [interrupt?   (-> executor meta :interrupt)
        shutdown?    (-> executor meta :shutdown)
        executor-sym (gensym "executor")]
    `(let [~executor-sym ~executor
           ~executor-sym (if (fn? ~executor-sym) (~executor-sym) ~executor-sym)]
       (binding [*default-executor* ~executor-sym]
         (try
           ~@body
           (finally
             ~(when (or shutdown? interrupt?)
                (list (if interrupt? 'promesa.exec/shutdown-now! 'promesa.exec/shutdown!) executor-sym))))))))

#?(:clj
(defn pmap
  "Analogous to the `clojure.core/pmap` with the excetion that it allows
  use a custom executor (binded to *default-executor* var) The default
  clojure chunk size (32) is used for evaluation and the real
  parallelism is determined by the provided executor.


  **EXPERIMENTAL API:** This function should be considered
  EXPERIMENTAL and may be changed or removed in future versions until
  this notification is removed."
  {:experimental true}
  ([f coll]
   (let [executor (resolve-executor *default-executor*)
         frame    (Var/cloneThreadBindingFrame)]
     (->> coll
          (map (fn [o] (pt/-submit! executor #(do
                                                (Var/resetThreadBindingFrame frame)
                                                (f o)))))
          (clojure.lang.RT/iter)
          (clojure.lang.RT/chunkIteratorSeq)
          (map (fn [o] (.get ^CompletableFuture o))))))
  ([f coll & colls]
   (let [step-fn (fn step-fn [cs]
                   (lazy-seq
                    (let [ss (map seq cs)]
                      (when (every? identity ss)
                        (cons (map first ss) (step-fn (map rest ss)))))))]
     (pmap #(apply f %) (step-fn (cons coll colls)))))))

#?(:clj
   (defn fn->thread
     [f & {:keys [start] :or {start true} :as options}]
     (let [factory (thread-factory options)
           thread  (.newThread ^ThreadFactory factory ^Runnable f)]
       (if start
         (.start ^Thread thread))
       thread)))

#?(:clj
(defmacro thread
  "A low-level, not-pooled thread constructor, it accepts an optional
  map as first argument and the body. The options map is interepreted
  as options if a literal map is provided. The available options are:
  `:name`, `:priority`, `:daemon` and `:virtual`. The `:virtual`
  option is ignored if you are using a JVM that has no support for
  Virtual Threads."
  [opts & body]
  (let [[opts body] (if (map? opts) [opts body] [{} (cons opts body)])]
    `(fn->thread (^:once fn* [] ~@body) ~@(mapcat identity opts)))))

#?(:clj
(defn thread-call
  "Advanced version of `p/thread-call` that creates and starts a thread
  configured with `opts`. No executor service is used, this will start
  a plain unpooled thread; returns a non-cancellable promise instance"
  [f & {:as opts}]
  (let [p (CompletableFuture.)]
    (fn->thread #(try
                   (pt/-resolve! p (f))
                   (catch Throwable cause
                     (pt/-reject! p cause)))
                (assoc opts :start true))
    p)))

#?(:clj
(defn current-thread
  "Return the current thread."
  []
  (Thread/currentThread)))

#?(:clj
(defn set-name!
  "Rename thread."
  ([name] (set-name! (current-thread) name))
  ([thread name] (.setName ^Thread thread ^String name))))

#?(:clj
(defn get-name
  "Retrieve thread name"
  ([] (get-name (current-thread)))
  ([thread]
   (.getName ^Thread thread))))

#?(:clj
(defn interrupted?
  "Check if the thread has the interrupted flag set.

  There are two special cases:

  Using the `:current` keyword as argument will check the interrupted
  flag on the current thread.

  Using the arity 0 (passing no arguments), then the current thread
  will be checked and **WARNING** the interrupted flag reset to
  `false`."
  ([]
   (Thread/interrupted))
  ([thread]
   (if (= :current thread)
     (.isInterrupted (Thread/currentThread))
     (.isInterrupted ^Thread thread)))))

#?(:clj
(defn get-thread-id
  "Retrieves the thread ID."
  ([]
   (.getId ^Thread (Thread/currentThread)))
  ([^Thread thread]
   (.getId thread))))

#?(:clj
(defn thread-id
  "Retrieves the thread ID."
  {:deprecated "11.0"}
  ([]
   (.getId ^Thread (Thread/currentThread)))
  ([^Thread thread]
   (.getId thread))))

#?(:clj
(defn interrupt!
  "Interrupt a thread."
  ([]
   (.interrupt (Thread/currentThread)))
  ([^Thread thread]
   (.interrupt thread))))

#?(:clj
(defn thread?
  "Check if provided object is a thread instance."
  [t]
  (instance? Thread t)))

#?(:clj
(defn sleep
  "Turn the current thread to sleep accept a number of milliseconds or
  Duration instance."
  [ms]
  (if (instance? Duration ms)
    (Thread/sleep (int (.toMillis ^Duration ms)))
    (Thread/sleep (int ms)))))

#?(:clj
(defn throw-uncaught!
  "Throw an exception to the current uncaught exception handler."
  [cause]
  (let [thr (current-thread)
        hdl (.getUncaughtExceptionHandler ^Thread thr)]
    (.uncaughtException ^Thread$UncaughtExceptionHandler hdl
                        ^Thread thr
                        ^Throwable cause))))

;; FIXME: set correct default virtual thread factory
#?(:clj
   (defn structured-task-scope
     ([]
      (pu/with-compile-cond structured-task-scope-available?
        (java.util.concurrent.StructuredTaskScope.)
        (throw (IllegalArgumentException. "implementation not available"))))
     ([& {:keys [name preset] :as options}]
      (pu/with-compile-cond structured-task-scope-available?
        (let [tf (options->thread-factory options :virtual)]
          (case preset
            :shutdown-on-success
            (java.util.concurrent.StructuredTaskScope$ShutdownOnSuccess.
             ^String name ^ThreadFactory tf)

            :shutdown-on-failure
            (java.util.concurrent.StructuredTaskScope$ShutdownOnFailure.
             ^String name ^ThreadFactory tf)

            (java.util.concurrent.StructuredTaskScope.
             ^String name ^ThreadFactory tf)))

        (throw (IllegalArgumentException. "implementation not available"))))))

#?(:clj
   (pu/with-compile-cond structured-task-scope-available?
     (extend-type java.util.concurrent.StructuredTaskScope$Subtask
       pt/IState
       (-extract
         ([it]
          (let [state (.state ^java.util.concurrent.StructuredTaskScope$Subtask it)]
            (case (str state)
              "UNAVAILABLE" nil
              "FAILED"      (.exception ^java.util.concurrent.StructuredTaskScope$Subtask it)
              "SUCCESS"     (.get ^java.util.concurrent.StructuredTaskScope$Subtask it))))
         ([it default]
          (or (pt/-extract it) default)))

       (-pending? [it]
         (let [state (.state ^java.util.concurrent.StructuredTaskScope$Subtask it)]
           (not= state java.util.concurrent.StructuredTaskScope$Subtask$State/UNAVAILABLE)))
       (-rejected? [it]
         (let [state (.state ^java.util.concurrent.StructuredTaskScope$Subtask it)]
           (= state java.util.concurrent.StructuredTaskScope$Subtask$State/FAILED)))
       (-resolved? [it]
         (let [state (.state ^java.util.concurrent.StructuredTaskScope$Subtask it)]
           (= state java.util.concurrent.StructuredTaskScope$Subtask$State/SUCCESS))))))

#?(:clj
   (pu/with-compile-cond structured-task-scope-available?
     (extend-type java.util.concurrent.StructuredTaskScope
       pt/IAwaitable
       (-await!
         ([it] (.join ^java.util.concurrent.StructuredTaskScope it))
         ([it duration]
          (let [duration (if (instance? Duration duration)
                           duration
                           (Duration/ofMillis duration))
                deadline (Instant/now)
                deadline (.plus ^Instant deadline
                                ^TemporalAmount duration)]
            (.joinUntil ^java.util.concurrent.StructuredTaskScope it
                        ^Instant deadline))))

       pt/ICloseable
       (-closed? [it]
         (.isShutdown ^java.util.concurrent.StructuredTaskScope it))

       (-close!
         ([it]
          (.close ^java.util.concurrent.StructuredTaskScope it))
         ([it reason]
          (.close ^java.util.concurrent.StructuredTaskScope it)))

       pt/IExecutor
       (-exec! [it task]
         (let [task (wrap-bindings task)]
           (.fork ^java.util.concurrent.StructuredTaskScope it ^Callable task)
           nil))
       (-run! [it task]
         (let [task (wrap-bindings task)]
           (.fork ^java.util.concurrent.StructuredTaskScope it ^Callable task)))
       (-submit! [it task]
         (let [task (wrap-bindings task)]
           (.fork ^java.util.concurrent.StructuredTaskScope it ^Callable task))))))

#?(:clj
   (pu/with-compile-cond structured-task-scope-available?
     (extend-type java.util.concurrent.StructuredTaskScope$ShutdownOnFailure
       pt/IAwaitable
       (-await!
         ([it]
          (.join ^java.util.concurrent.StructuredTaskScope$ShutdownOnFailure it)
          (.throwIfFailed ^java.util.concurrent.StructuredTaskScope$ShutdownOnFailure it))
         ([it duration]
          (let [duration (if (instance? Duration duration)
                           duration
                           (Duration/ofMillis duration))
                deadline (Instant/now)
                deadline (.plus ^Instant deadline
                                ^TemporalAmount duration)]
            (.joinUntil ^java.util.concurrent.StructuredTaskScope$ShutdownOnFailure it ^Instant deadline)
            (.throwIfFailed ^java.util.concurrent.StructuredTaskScope$ShutdownOnFailure it)))))))


;; #?(:clj
;;    (defn managed-blocker
;;      {:no-doc true}
;;      [f]
;;      (let [state (volatile! nil)]
;;        (reify
;;          ForkJoinPool$ManagedBlocker
;;          (block [_]
;;            (try
;;              (vreset! state (.call ^Callable f))
;;              (catch Throwable cause#
;;                (vreset! state cause#)))
;;            true)
;;          (isReleasable [_]
;;            false)

;;          clojure.lang.IDeref
;;          (deref [_]
;;            (let [v @state]
;;              (if (instance? Throwable v)
;;                (throw v)
;;                v)))))))

;; (defmacro blocking
;;   {:no-doc true}
;;   [& body]
;;   `(let [f# (^:once fn* [] ~@body)
;;          m# (managed-blocker f#)]
;;      (ForkJoinPool/managedBlock m#)
;;      (deref m#)))

#?(:clj
(defn await!
  "Generic await operation. Block current thread until some operation
  terminates.

  The return value is implementation specific."
  ([resource]
   (pt/-await! resource))
  ([resource duration]
   (pt/-await! resource duration))))

(defn close!
  ([o]
   (pt/-close! o))
  ([o reason]
   (pt/-close! o reason)))
