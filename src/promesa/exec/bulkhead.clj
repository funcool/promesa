;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec.bulkhead
  "Bulkhead pattern: limiter of concurrent executions."
  (:refer-clojure :exclude [run! prn])
  (:require
   [promesa.exec :as px]
   [promesa.impl :as pi]
   [promesa.protocols :as pt]
   [promesa.util :as pu])
  (:import
   clojure.lang.IObj
   clojure.lang.IFn
   clojure.lang.ILookup
   java.util.concurrent.LinkedBlockingQueue
   java.util.concurrent.BlockingQueue
   java.util.concurrent.CompletableFuture
   java.util.concurrent.ExecutorService
   java.util.concurrent.Semaphore))

(set! *warn-on-reflection* true)

(declare ^:private instant)
(declare ^:privare run-hook!)

(defn- prn
  [& args]
  (locking prn
    (apply clojure.core/prn args)))

(defn- current-thread
  []
  (.getName (Thread/currentThread)))

(defprotocol IQueue
  (-poll! [_])
  (-offer! [_ _]))

(deftype Task [bulkhead task-fn result inst]
  pt/ICancellable
  (-cancelled? [_]
    (pt/-cancelled? result))
  (-cancel! [_]
    (pt/-cancel! result))

  clojure.core/Inst
  (inst-ms* [_] inst)

  IFn
  (invoke [this]
    ;; (prn "Task.invoke0" (current-thread))
    (let [semaphore (::semaphore bulkhead)
          executor  (::executor bulkhead)]
      (run-hook! bulkhead ::on-run this)
      (try
        (if-let [rval (task-fn)]
          (do
            ;; (prn "Task.invoke0" "rval=" rval)
            (-> (pt/-promise rval)
                (pt/-fnly (fn [v e]
                            (pt/-release! semaphore)
                            (pt/-submit! executor bulkhead)
                            (if e
                              (pt/-reject! result e)
                              (pt/-resolve! result v))))))

          (pt/-resolve! result nil))
        (catch Throwable cause
          (pt/-release! semaphore)
          (pt/-submit! executor bulkhead)
          (pt/-reject! result cause))))))

(deftype Bulkhead [queue semaphore executor metadata]
  IObj
  (meta [_] metadata)
  (withMeta [this mdata]
    (Bulkhead. queue semaphore executor mdata))

  ILookup
  (valAt [this key]
    (case key
      ::executor           executor
      ::semaphore          semaphore
      ::queue-size          (-> this meta ::queue-size)
      ::concurrency         (-> this meta ::concurrency)
      ::current-queue-size  (.size ^BlockingQueue queue)
      ::current-concurrency (- (-> this meta ::concurrency)
                               (.availablePermits ^Semaphore semaphore))
      nil))

  (valAt [this key default]
    (or (.valAt ^ILookup this key) nil))

  pt/IExecutor
  (-run! [this task-fn]
    (-> (-offer! this task-fn)
        (pt/-fmap px/noop)))

  (-submit! [this task-fn]
    (-offer! this task-fn))

  IFn
  (invoke [this]
    ;; (prn "Bulkhead.invoke0" (current-thread) "start")
    (loop []
      (when-let [task (-poll! this)]
        (if (pt/-cancelled? task)
          (pt/-release! semaphore)
          (pt/-submit! executor task))
        (recur)))
    ;; (prn "Bulkhead.invoke0" (current-thread) "end")
    )

  IQueue
  (-offer! [this task-fn]
    (let [result (pi/deferred)
          task   (Task. this task-fn result (instant))]
      (if (-offer! queue task)
        (do
          ;; (prn "Bulkhead.offer!" (current-thread) "success")
          (run-hook! this ::on-queue)
          (this))
        (let [size  (-> this meta ::queue-size)
              msg   (str "Queue max capacity reached: " size)
              props {:type :bulkhead-error
                     :code :capacity-limit-reached
                     ::instance this}]
          ;; (prn "Bulkhead.offer!" (current-thread) "fail")
          (pt/-reject! result (ex-info msg props))))
      result))

  (-poll! [this]
    (when (pt/-try-acquire! semaphore)
      (if-let [task (-poll! queue)]
        (do
          ;; (prn "Bulkhead.poll!" (current-thread))
          task)
        (.release ^Semaphore semaphore)))))

(defn bulkhead?
  "Check if the provided object is instance of Bulkhead type."
  [o]
  (instance? Bulkhead o))

(extend-type BlockingQueue
  IQueue
  (-poll! [this] (.poll ^BlockingQueue this))
  (-offer! [this o] (.offer ^BlockingQueue this o)))

(extend-type Semaphore
  pt/ISemaphore
  (-try-acquire!
    ([this] (.tryAcquire ^Semaphore this))
    ([this permits] (.tryAcquire ^Semaphore this (int permits))))
  (-acquire!
    ([this] (.acquire ^Semaphore this))
    ([this permits] (.acquire ^Semaphore this (int permits))))
  (-release!
    ([this] (.release ^Semaphore this))
    ([this permits] (.release ^Semaphore this (int permits)))))

(defn- instant
  []
  (System/currentTimeMillis))

(defn- run-hook!
  ([instance key-fn]
   (when-let [hook-fn (-> instance meta key-fn)]
     (let [executor (::executor instance)]
       (pt/-submit! executor (partial hook-fn instance)))))
  ([instance key-fn param1]
   (when-let [hook-fn (-> instance meta key-fn)]
     (let [executor (::executor instance)]
       (pt/-submit! executor (partial hook-fn instance param1))))))

(ns-unmap *ns* '->Bulkhead)

(defn create
  [& {:keys [executor concurrency queue-size on-run on-queue]
      :or {concurrency 1 queue-size Integer/MAX_VALUE}
      :as params}]
  (let [executor  (px/resolve-executor (or executor px/*default-executor*))
        queue     (LinkedBlockingQueue. (int queue-size))
        semaphore (Semaphore. (int concurrency))
        metadata  {::concurrency concurrency
                   ::queue-size queue-size
                   ::on-run on-run
                   ::on-queue on-queue}]

    (Bulkhead. queue semaphore executor metadata)))

