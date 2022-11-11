;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec.csp.channel
  (:require
   [promesa.core :as p]
   [promesa.protocols :as pt]
   [promesa.exec.csp.mutable-list :as mlist]
   [promesa.exec :as px])
  #?(:clj
     (:import java.util.concurrent.locks.ReentrantLock)))

(def ^:dynamic *executor*
  (if px/vthreads-supported? :vthread :default))

(defn mutex
  []
  #?(:clj
     (let [m (ReentrantLock.)]
       (reify
         pt/ILock
         (-lock! [_] (.lock m))
         (-unlock! [_] (.unlock m))))
     :cljs
     (reify
       pt/ILock
       (-lock! [_])
       (-unlock! [_]))))

(defn fn->handler
  [f]
  (let [lock    (mutex)
        active? (atom true)]
    (reify
      pt/ILock
      (-lock! [_] (pt/-lock! lock))
      (-unlock! [_] (pt/-unlock! lock))

      pt/IHandler
      (-active? [_] @active?)
      (-blockable? [_] true)
      (-commit! [_]
        (and (compare-and-set! active? true false) f)))))

(defn volatile->handler
  [vo]
  (let [lock    (mutex)
        active? (atom true)]
    (reify
      pt/ILock
      (-lock! [_] (pt/-lock! lock))
      (-unlock! [_] (pt/-unlock! lock))

      pt/IHandler
      (-active? [_] @active?)
      (-blockable? [_] false)
      (-commit! [_]
        (and (compare-and-set! active? true false)
             (fn [v]
               (vreset! vo v)))))))

(defn promise->handler
  [p]
  (let [lock    (mutex)
        active? (atom true)]
    (reify
      pt/ILock
      (-lock! [_] (pt/-lock! lock))
      (-unlock! [_] (pt/-unlock! lock))

      pt/IHandler
      (-active? [_]
        (and (p/pending? p)
             (deref active?)))
      (-blockable? [_] true)
      (-commit! [_]
        (and (compare-and-set! active? true false)
             (fn [val]
               (when (p/pending? p)
                 (p/resolve! p val))))))))

(defn commit!
  "A convenience helper that locks, checks and return commited handler
  callbale given an instance of handler."
  [handler]
  (try
    (pt/-lock! handler)
    (when (pt/-active? handler)
      (pt/-commit! handler))
    (finally
      (pt/-unlock! handler))))

(defn- commit-and-run!
  [handler rval]
  "A convenience helper that commits handler and if success, executes
  the handler immediatelly with the provided value as first argument."
  (when-let [handler-fn (commit! handler)]
    (handler-fn rval)
    true))

(defn- lookup-take-transfer
  "On unbuffered channels when take operation is requested, this
  function searches an active and valid put candidate that will match
  the take operation."
  [puts handler]
  (letfn [(validate! [[putter val]]
            (try
              (pt/-lock! putter)
              (when (pt/-active? putter)
                [(pt/-commit! putter) val])
              (finally
                (pt/-unlock! putter))))]
    (try
      (pt/-lock! handler)
      (when (pt/-active? handler)
        (loop [items (seq puts)]
          (when-let [match (first items)]
            (if-let [match (validate! match)]
              (conj match (pt/-commit! handler))
            (recur (rest items))))))
      (finally
        (pt/-unlock! handler)))))

(defn- lookup-put-transfer
  "On unbuffered channels when put operation is requested, this
  function searches an active and valid take candidate that will match
  the put operation."
  [takes handler]
  (letfn [(validate! [taker]
            (try
              (pt/-lock! taker)
              (when (pt/-active? taker)
                [(pt/-commit! taker)])
              (finally
                (pt/-unlock! taker))))]
    (try
      (pt/-lock! handler)
      (when (pt/-active? handler)
        (loop [items (seq takes)]
          (when-let [match (first items)]
            (if-let [match (validate! match)]
              (conj match (pt/-commit! handler))
              (recur (rest items))))))
      (finally
        (pt/-unlock! handler)))))

(defn- lookup-pending-puts
  "This is the loop that processes the pending puts after a succesfull
  take operation (that has probably have freed a slot in the buffer)."
  [puts add-fn buf]
  (loop [items (seq puts)
         fns   nil
         done? false]
    (let [[putter val] (first items)]
      (if-let [put-fn (some-> putter commit!)]
        (let [fns   (cons put-fn fns)
              done? (reduced? (add-fn buf val))]
          (if (and (not done?) (not (pt/-full? buf)))
            (recur (rest items) fns done?)
            (recur nil fns done?)))
        [done? fns]))))

(defn- lookup-pending-takes
  "This is the loop that processes the pending takes after a succesfull
  put operation."
  [takes buf]
  (loop [items  (seq takes)
         result []]
    (let [taker (first items)]
      (if (and taker (pos? (pt/-size buf)))
        (if-let [take-fn (commit! taker)]
          (recur (rest items) (conj result (partial take-fn (pt/-poll! buf))))
          (recur (rest items) result))
        result))))

(defn- process-take-handler!
  "When buffer is full or no buffer, we need to do a common task: if
  task is blockable, enqueue it and if task is not blockable we just
  cancel it."
  [takes handler]
  (if (pt/-blockable? handler)
    (vswap! takes mlist/add-last! handler)
    (commit-and-run! handler nil))
  nil)

(defn- process-put-handler!
  "When buffer is full or no buffer, we need to do a common task: if
  task is blockable, enqueue it and if task is not blockable we just
  cancel it."
  [puts handler val]
  (if (pt/-blockable? handler)
    (vswap! puts mlist/add-last! [handler val])
    (commit-and-run! handler false))
  nil)

(deftype Channel [takes puts buf closed lock add-fn]
  pt/ILock
  (-lock! [_]
    (pt/-lock! lock))
  (-unlock! [_]
    (pt/-unlock! lock))

  pt/IChannel
  (-cleanup! [_]
    (loop [items  (seq @takes)
           result (mlist/create)]
      (if-let [taker (first items)]
        (if (pt/-active? taker)
          (recur (rest items)
                 (mlist/add-last! result taker))
          (recur (rest items)
                 result))
        (vreset! takes result)))
    (loop [items  (seq @puts)
           result (mlist/create)]
      (if-let [[putter val :as item] (first items)]
        (if (pt/-active? putter)
          (recur (rest items)
                 (mlist/add-last! result item))
          (recur (rest items)
                 result))
        (vreset! puts result))))

  (-abort! [_]
    (loop [items (seq @puts)]
      (when-let [[putter] (first items)]
        (when-let [put-fn (commit! putter)]
          (px/run! *executor* (partial put-fn false)))
        (recur (rest items)))))

  (-put! [this val handler]
    (when (nil? val)
      (throw (ex-info "Can't put nil on channel" {})))
    (try
      (pt/-lock! this)
      (pt/-cleanup! this)
      (if @closed
        (let [put-fn (commit! handler)]
          (put-fn false)
          nil)
        (if buf
          (if (pt/-full? buf)
            (when (pt/-active? handler)
              (process-put-handler! puts handler val))
            (when (commit-and-run! handler true)
              (let [done?     (reduced? (add-fn buf val))
                    takes-fns (lookup-pending-takes @takes buf)]
                (when done? (pt/-abort! this))
                (run! (partial px/run! *executor*) takes-fns)
                nil)))

          (if-let [[take-fn put-fn] (lookup-put-transfer @takes handler)]
            (do
              (put-fn true)
              (px/run! *executor* (partial take-fn val))
              nil)
            (when (pt/-active? handler)
              (process-put-handler! puts handler val)))))

      (finally
        (pt/-unlock! this))))

  (-take! [this handler]
    (try
      (pt/-lock! this)
      (pt/-cleanup! this)
      (if @closed
        (when-let [take-fn (commit! handler)]
          (take-fn (some-> buf pt/-poll!)))

        (if buf
          (if (pos? (pt/-size buf))
            (let [take-fn     (when-let [take-fn (commit! handler)]
                                (partial take-fn (pt/-poll! buf)))
                  [done? fns] (lookup-pending-puts @puts add-fn buf)]
              (when done? (pt/-abort! this))
              (run! #(px/run! *executor* (fn [] (% true))) fns)
              (when take-fn (take-fn))
              nil)

            (when (pt/-active? handler)
              (process-take-handler! takes handler)))

          (if-let [[put-fn val take-fn] (lookup-take-transfer @puts handler)]
            (do
              (take-fn val)
              (px/run! *executor* (partial put-fn true))
              nil)
            (process-take-handler! takes handler))))

      (finally
        (pt/-unlock! this))))

  pt/ICloseable
  (-closed? [this] @closed)
  (-close! [this]
    (when (compare-and-set! closed false true)
      (try
        (pt/-lock! this)
        (when (and buf (zero? (mlist/size @puts)))
          (add-fn buf))

        (loop [items (seq @takes)]
          (when-let [taker (first items)]
            (when-let [take-fn (commit! taker)]
              (px/run! *executor* (partial take-fn (some-> buf pt/-poll!))))
            (recur (rest items))))

        (pt/-abort! this)

        (finally
          (pt/-unlock! this))))))

(defn- ex-handler
  [ex]
  #?(:clj (-> (Thread/currentThread)
              (.getUncaughtExceptionHandler)
              (.uncaughtException (Thread/currentThread) ex))
     :cljs (js/console.error ex))
  nil)

(defn- handle
  [buf exh t]
  (let [else ((or exh ex-handler) t)]
    (if (nil? else)
      buf
      (pt/-offer! buf else))))

(defn- add-fn
  ([b] b)
  ([b itm]
   (assert (not (nil? itm)))
   (pt/-offer! b itm)))

(defn chan
  ([buf] (chan buf nil))
  ([buf xf] (chan buf xf nil))
  ([buf xf exh]
   (let [add-fn (if xf (xf add-fn) add-fn)
         add-fn (fn
                  ([buf]
                   (try
                     (add-fn buf)
                     (catch Throwable t
                       (handle buf exh t))))
                  ([buf val]
                   (try
                     (add-fn buf val)
                     (catch Throwable t
                       (handle buf exh t)))))]
     (Channel. (volatile! (mlist/create))
               (volatile! (mlist/create))
               buf
               (atom false)
               (mutex)
               add-fn))))
