;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec.bulkhead
  "Bulkhead pattern: limiter of concurrent executions."
  (:refer-clojure :exclude [run!])
  (:require
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.semaphore :as psm]
   [promesa.impl :as pi]
   [promesa.protocols :as pt]
   [promesa.util :as pu])
  (:import
   java.util.concurrent.BlockingQueue
   java.util.concurrent.CompletableFuture
   java.util.concurrent.Executor
   java.util.concurrent.LinkedBlockingQueue
   java.util.concurrent.Semaphore
   java.util.concurrent.atomic.AtomicLong))

(set! *warn-on-reflection* true)
;; (set! *unchecked-math* :warn-on-boxed)

(defmacro log!
  [& params]
  ;; `(locking prn
  ;;    (prn ~@params))
  )

(declare ^:private instant)

(defprotocol IQueue
  (-poll! [_])
  (-offer! [_ _]))

(defprotocol IBulkhead
  "Bulkhead main API"
  (-get-stats [_] "Get internal statistics of the bulkhead instance")
  (-invoke! [_ f] "Call synchronously a function under bulkhead context"))

(extend-type BlockingQueue
  IQueue
  (-poll! [this] (.poll ^BlockingQueue this))
  (-offer! [this o] (.offer ^BlockingQueue this o)))

(defn- instant
  []
  (System/currentTimeMillis))

(deftype ExecutorBulkheadTask [bulkhead f inst]
  clojure.core/Inst
  (inst-ms* [_] inst)

  Runnable
  (run [this]
    (let [^Semaphore semaphore (:semaphore bulkhead)
          ^Executor executor  (:executor bulkhead)]
      (log! "cmd:" "Task/run" "f:" (hash f) "task:" (hash this) "START")
      (try
        (.run ^Runnable f)
        (finally
          (psm/release! semaphore :permits 1)
          (log! "cmd:" "Task/run" "f:" (hash f) "task:" (hash this) "END" "permits:" (.availablePermits semaphore))
          (pt/-run! executor bulkhead))))))

(ns-unmap *ns* '->ExecutorBulkheadTask)

(defrecord ExecutorBulkhead [^Executor executor
                             ^Semaphore semaphore
                             ^BlockingQueue queue
                             max-permits
                             max-queue]
  IBulkhead
  (-get-stats [_]
    (let [permits (.availablePermits semaphore)]
      {:permits (.availablePermits semaphore)
       :queue (.size queue)
       :max-permits max-permits
       :max-queue max-queue}))

  (-invoke! [this f]
    (p/await! (pt/-submit! this (px/wrap-bindings f))))

  Executor
  (execute [this f]
    (log! "cmd:" "Bulkhead/execute" "f:" (hash f))
    (-offer! this f))

  IQueue
  (-offer! [this f]
    (let [task (ExecutorBulkheadTask. this f (instant))]
      (when-not (-offer! queue task)
        (let [size  (.size queue)
              hint  (str "queue max capacity reached: " size)
              props {:type :bulkhead-error
                     :code :capacity-limit-reached
                     :size size}]
          (throw (ex-info hint props))))

      (log! "cmd:" "Bulkhead/-offer!" "queue" (.size queue))
      (.run ^Runnable this)))

  (-poll! [this]
    (when (pt/-try-acquire! semaphore)
      (if-let [task (-poll! queue)]
        task
        (pt/-release! semaphore))))

  Runnable
  (run [this]
    (log! "cmd:" "Bulkhead/run" "queue:" (.size queue) "permits:" (.availablePermits semaphore))
    (loop []
      (log! "cmd:" "Bulkhead/run$loop1" "queue:" (.size queue) "permits:" (.availablePermits semaphore))
      (when-let [task (-poll! this)]
        (log! "cmd:" "Bulkhead/run$loop2" "task:" (hash task) "available-permits:" (.availablePermits semaphore))
        (pt/-exec! executor task)
        (recur)))))

(ns-unmap *ns* '->ExecutorBulkhead)
(ns-unmap *ns* 'map->ExecutorBulkhead)

(defrecord SemaphoreBulkhead [^Semaphore semaphore
                              ^AtomicLong counter
                              max-permits
                              max-queue
                              timeout]
  IBulkhead
  (-get-stats [_]
    {:permits (.availablePermits semaphore)
     :queue (+ (long counter) (long max-permits))
     :max-permits max-permits
     :max-queue max-queue})

  pt/IExecutor
  (-exec! [this f]
    (.execute ^Executor this ^Runnable f))

  (-run! [this f]
    (CompletableFuture/runAsync ^Runnable f ^Executor this))

  (-submit! [this f]
    (CompletableFuture/supplyAsync ^Supplier (pu/->Supplier f) ^Executor this))

  Executor
  (execute [this f]
    (let [nqueued (.incrementAndGet counter)]
      (when (> (long nqueued) (long max-queue))
        (let [hint  (str "bulkhead: queue max capacity reached (" max-queue ")")
              props {:type :bulkhead-error
                     :code :capacity-limit-reached
                     :size max-queue}]
          (.decrementAndGet counter)
          (throw (ex-info hint props))))

      (try
        (if (psm/acquire! semaphore :permits 1 :timeout timeout)
          (try
            (.run ^Runnable f)
            (finally (psm/release! semaphore)))
          (let [props {:type :bulkhead-error
                       :code :timeout
                       :timeout timeout}]
            (throw (ex-info "bulkhead: timeout" props))))
        (finally
          (.decrementAndGet counter)))))

  (-invoke! [this f]
    (p/await! (pt/-submit! this f))))

(ns-unmap *ns* '->SemaphoreBulkhead)
(ns-unmap *ns* 'map->SemaphoreBulkhead)

(defn- create-with-executor
  [{:keys [executor permits queue on-run on-queue]}]
  (let [executor    (px/resolve-executor executor)
        max-queue   (or queue Integer/MAX_VALUE)
        max-permits (or permits 1)
        queue       (LinkedBlockingQueue. (int max-queue))
        semaphore   (Semaphore. (int permits))]
    (ExecutorBulkhead. executor semaphore queue max-permits max-queue)))

(defn- create-with-semaphore
  [{:keys [permits queue timeout]}]
  (let [max-queue   (or queue Integer/MAX_VALUE)
        max-permits (or permits 1)
        counter     (AtomicLong. (- (long permits)))
        semaphore   (Semaphore. (int max-permits))]
    (SemaphoreBulkhead. semaphore
                        counter
                        max-permits
                        max-queue
                        timeout)))

;; --- PUBLIC API

(defn create
  [& {:keys [type] :as params}]
  (case type
    :executor (create-with-executor params)
    :semaphore (create-with-semaphore params)
    (throw (UnsupportedOperationException. "invalid bulkhead type provided"))))

(defn get-stats
  [instance]
  (-get-stats instance))

(defn invoke!
  [instance f]
  (-invoke! instance f))

(defn bulkhead?
  "Check if the provided object is instance of Bulkhead type."
  [o]
  (satisfies? IBulkhead o))
