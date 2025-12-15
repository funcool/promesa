;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec.bulkhead
  "Bulkhead pattern: limiter of concurrent executions."
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.core.protocols :as cp]
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
   java.util.concurrent.TimeUnit
   java.util.concurrent.atomic.AtomicLong
   java.util.concurrent.atomic.AtomicInteger
   java.util.function.Supplier))

(set! *warn-on-reflection* true)

(defmacro log
  [& params]
  ;; `(locking prn
  ;;    (prn ~@params))
  )

(defprotocol IQueue
  (-poll [_])
  (-offer [_ _] [_ _ _]))

(extend-type BlockingQueue
  IQueue
  (-poll [this] (.poll ^BlockingQueue this))
  (-offer
    ([this o] (.offer ^BlockingQueue this o))
    ([this o timeout] (.offer ^BlockingQueue this o (long timeout) TimeUnit/MILLISECONDS))))

(defn- instant
  []
  (System/currentTimeMillis))

(deftype Task [bulkhead f inst]
  clojure.core/Inst
  (inst-ms* [_] inst)

  Runnable
  (run [this]
    (let [semaphore (:semaphore bulkhead)
          executor  (:executor bulkhead)]

      (log "cmd:" "Task/run" "f:" (hash f) "task:" (hash this) "done" done? "START")
      (try
        (.run ^Runnable f)
        (finally
          (psm/release semaphore :permits 1)
          (log "cmd:" "Task/run" "f:" (hash f)
               "task:" (hash this)
               "permits:" (.availablePermits ^Semaphore semaphore)
               "END")
          (.execute ^Executor executor ^Runnable bulkhead))))))

(defrecord Bulkhead [^Executor executor
                     ^Semaphore semaphore
                     ^BlockingQueue queue
                     max-permits
                     max-queue
                     timeout]

  cp/Datafiable
  (datafy [_]
    {:permits (.availablePermits semaphore)
     :queue (.size queue)
     :max-permits max-permits
     :max-queue max-queue
     :timeout timeout})

  pt/IInvoke
  (-invoke [this f]
    (->> (px/submit this f)
         (p/join)))

  (-invoke [this f ms-or-duration]
    (let [result (px/submit this f)]
      (try
        (p/join result ms-or-duration)
        (catch java.util.concurrent.TimeoutException cause
          (p/cancel result)
          (throw cause)))))

  Executor
  (execute [this f]
    (log "cmd:" "Bulkhead/execute" "f:" (hash f) timeout)
    (let [task (Task. this f (instant))
          res  (if timeout
                 (-offer queue task timeout)
                 (-offer queue task))]

      (when-not res
        (let [size  (.size queue)
              hint  (str "bulkhead: queue max capacity reached (" max-queue ")")
              props {:type :bulkhead-error
                     :code :capacity-limit-reached
                     :size size}]
          (throw (ex-info hint props))))

      (log "cmd:" "Bulkhead/-offer" "queue" (.size queue))
      (.execute ^Executor executor ^Runnable this)))

  Runnable
  (run [this]
    (log "cmd:" "Bulkhead/run" "queue:" (.size queue) "permits:" (.availablePermits semaphore))
    (loop []
      (log "cmd:" "Bulkhead/run$loop1" "queue:" (.size queue) "permits:" (.availablePermits semaphore))
      (when-let [task (when (pt/-try-acquire semaphore)
                        (if-let [task (-poll queue)]
                          task
                          (pt/-release semaphore)))]
        (log "cmd:" "Bulkhead/run$loop2" "task:" (hash task) "available-permits:" (.availablePermits semaphore))
        (.execute ^Executor executor ^Runnable task)
        (recur)))))

(ns-unmap *ns* '->Bulkhead)
(ns-unmap *ns* 'map->Bulkhead)
(ns-unmap *ns* '->Task)

(defrecord SemaphoreBulkhead [^Semaphore semaphore
                              ^AtomicInteger counter
                              max-permits
                              max-queue
                              timeout]
  cp/Datafiable
  (datafy [_]
    {:permits (.availablePermits semaphore)
     :queue (+ (long counter) (long max-permits))
     :max-permits max-permits
     :max-queue max-queue})

  pt/IInvoke
  (-invoke [this f]
    (let [result (volatile! nil)
          f'     (fn [] (vreset! result (f)))]
      (.execute ^Executor this
                ^Runnable f')
      (deref result)))

  (-invoke [this f ms-or-duration]
    (-> this
        (assoc :timeout ms-or-duration)
        (pt/-invoke f)))

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
        (if (psm/acquire semaphore :permits 1 :timeout timeout)
          (try
            (.run ^Runnable f)
            (finally
              (psm/release semaphore)))
          (let [props {:type :bulkhead-error
                       :code :capacity-limit-reached
                       :timeout timeout}]
            (throw (ex-info "bulkhead: timeout" props))))
        (finally
          (.decrementAndGet counter))))))

(ns-unmap *ns* '->SemaphoreBulkhead)
(ns-unmap *ns* 'map->SemaphoreBulkhead)

;; --- PUBLIC API

(defn create
  [& {:keys [type permits queue timeout] :as params}]
  (case type
    (:async :executor)
    (let [executor    (px/resolve-executor (:executor params))
          max-queue   (or queue Integer/MAX_VALUE)
          max-permits (or permits 1)
          queue       (LinkedBlockingQueue. (int max-queue))
          semaphore   (Semaphore. (int max-permits))]
      (Bulkhead. executor
                 semaphore
                 queue
                 max-permits
                 max-queue
                 timeout))

    (:sync :semaphore)
    (let [max-queue   (or queue Integer/MAX_VALUE)
          max-permits (or permits 1)
          counter     (AtomicInteger. (int (- max-permits)))
          semaphore   (Semaphore. (int max-permits))]
      (SemaphoreBulkhead. semaphore
                          counter
                          max-permits
                          max-queue
                          timeout))))

(defn get-stats
  [instance]
  (cp/datafy instance))

(defn bulkhead?
  "Check if the provided object is instance of Bulkhead type."
  [o]
  (satisfies? Bulkhead o))

(defn invoke!
  {:deprecated "12.0.0"
   :no-doc true}
  ([context f]
   (pt/-invoke context f))
  ([context f ms-or-duration]
   (pt/-invoke context f ms-or-duration)))
