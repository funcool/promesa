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
(declare ^:privare run-hook!)

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

(defn- run-hook!
  ([instance key-fn]
   (when-let [hook-fn (-> instance meta key-fn)]
     (let [executor (:executor instance)]
       (pt/-run! executor (partial hook-fn instance)))))
  ([instance key-fn param1]
   (when-let [hook-fn (-> instance meta key-fn)]
     (let [executor (:executor instance)]
       (pt/-run! executor (partial hook-fn instance param1))))))

(deftype ExecutorBulkheadTask [bulkhead f inst]
  clojure.core/Inst
  (inst-ms* [_] inst)

  Runnable
  (run [this]
    (let [^Semaphore semaphore (:semaphore bulkhead)
          ^Executor executor  (:executor bulkhead)]
      (run-hook! bulkhead ::on-run this)
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
      {:permits (- (long max-permits) permits)
       :queue (.size queue)
       :max-permits max-permits
       :max-queue max-queue}))

  (-invoke! [this f]
    (p/await! (pt/-submit! this f)))

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
      (run-hook! this ::on-queue)
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
        (pt/-run! executor task)
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
    (let [queue   (+ (long counter) (long max-permits))
          permits (.availablePermits semaphore)]
      {:permits (- (long max-permits) permits)
       :queue queue
       :max-permits max-permits
       :max-queue max-queue}))

  (-invoke! [this f]
    (let [nqueued (.incrementAndGet counter)]
      (try
        (when (> (long nqueued) (long max-queue))
          (let [hint  (str "queue max capacity reached: " max-queue)
                props {:type :bulkhead-error
                       :code :capacity-limit-reached
                       :size max-queue}]
            (throw (ex-info hint props))))
        (psm/acquire! semaphore :permits 1 :timeout timeout)
        (f)
        (finally
          (.decrementAndGet counter)
          (psm/release! semaphore)))))

  Executor
  (execute [this f]
    (-invoke! this f)))

(ns-unmap *ns* '->SemaphoreBulkhead)
(ns-unmap *ns* 'map->SemaphoreBulkhead)

(defmulti create* :type)

(defmethod create* :executor
  [{:keys [executor permits queue on-run on-queue]}]
  (let [executor    (px/resolve-executor executor)
        max-queue   (or queue Integer/MAX_VALUE)
        max-permits (or permits 1)
        queue       (LinkedBlockingQueue. (int max-queue))
        semaphore   (Semaphore. (int permits))]
    (ExecutorBulkhead. executor semaphore queue max-permits max-queue)))

(defmethod create* :semaphore
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

(defmethod create* :default
  [_]
  (throw (UnsupportedOperationException. "invalid bulkhead type provided")))

;; --- PUBLIC API

(defn create
  [& {:as params}]
  (create* params))

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
