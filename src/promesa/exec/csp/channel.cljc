;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns ^:no-doc promesa.exec.csp.channel
  (:refer-clojure :exclude [take])
  (:require
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp.mutable-list :as mlist]
   [promesa.protocols :as pt]
   [promesa.util :as util]))

#?(:clj (set! *warn-on-reflection* true))

(defn volatile->handler
  {:no-doc true}
  [vo]
  (let [lock    (util/mutex)
        active? (atom true)]
    (reify
      pt/ILock
      (-lock [_] (pt/-lock lock))
      (-unlock [_] (pt/-unlock lock))

      pt/IHandler
      (-active? [_] @active?)
      (-blockable? [_] false)
      (-commit [_]
        (and (compare-and-set! active? true false)
             (fn
               ([v]
                (vreset! vo [v nil]))
               ([v c]
                (vreset! vo [v c]))))))))

(defn promise->handler
  {:no-doc true}
  ([p] (promise->handler p true))
  ([p blockable?]
   (let [lock    (util/mutex)
         active? (atom true)]
     (reify
       pt/ILock
       (-lock [_] (pt/-lock lock))
       (-unlock [_] (pt/-unlock lock))

       pt/IHandler
       (-active? [_]
         (and (p/pending? p)
              (deref active?)))
       (-blockable? [_] blockable?)
       (-commit [_]
         (and (compare-and-set! active? true false)
              (fn
                ([v]
                 (when (p/pending? p)
                   (p/resolve p v)))
                ([v c]
                 (when (p/pending? p)
                   (if c
                     (p/reject p c)
                     (p/resolve p v)))))))))))

(defn commit
  "A convenience helper that locks, checks and return commited handler
  callbale given an instance of handler."
  {:no-doc true}
  [handler]
  (try
    (pt/-lock handler)
    (when (pt/-active? handler)
      (pt/-commit handler))
    (finally
      (pt/-unlock handler))))

(defn- commit-and-run
  "A convenience helper that commits handler and if success, executes
  the handler immediatelly with the provided value as first argument."
  [handler rval]
  (when-let [handler-fn (commit handler)]
    (handler-fn rval)
    true))

(defn- lookup-take-transfer
  "On unbuffered channels when take operation is requested, this
  function searches an active and valid put candidate that will match
  the take operation."
  [puts handler]
  (letfn [(validate [[putter val]]
            (try
              (pt/-lock putter)
              (when (pt/-active? putter)
                [(pt/-commit putter) val])
              (finally
                (pt/-unlock putter))))]
    (try
      (pt/-lock handler)
      (when (pt/-active? handler)
        (loop []
          (when-let [match (mlist/remove-first puts)]
            (if-let [match (validate match)]
              (conj match (pt/-commit handler))
              (recur)))))
      (finally
        (pt/-unlock handler)))))

(defn- lookup-put-transfer
  "On unbuffered channels when put operation is requested, this
  function searches an active and valid take candidate that will match
  the put operation."
  [takes handler]
  (letfn [(validate [taker]
            (try
              (pt/-lock taker)
              (when (pt/-active? taker)
                [(pt/-commit taker)])
              (finally
                (pt/-unlock taker))))]
    (try
      (pt/-lock handler)
      (when (pt/-active? handler)
        (loop []
          (when-let [match (mlist/remove-first takes)]
            (if-let [match (validate match)]
              (conj match (pt/-commit handler))
              (recur)))))
      (finally
        (pt/-unlock handler)))))

(defn- process-pending-puts
  "This is the loop that processes the pending puts after a succesfull
  take operation (that has probably have freed a slot in the buffer)."
  [this executor puts add-fn buf]
  (loop [done? false]
    (if (or (pt/-full? buf) done?)
      done?
      (if-let [[putter val] (mlist/remove-first puts)]
        (do
          (if-let [put-fn (commit putter)]
            (let [done? (reduced? (add-fn this buf val))]
              (px/exec executor (partial put-fn true))
              (recur done?))
            (recur done?)))

        done?))))

(defn- abort
  "Process all pending put handlers and execute them ignoring the values"
  [executor puts]
  (loop []
    (when-let [[putter] (mlist/remove-first puts)]
      (when-let [put-fn (commit putter)]
        (px/exec executor (partial put-fn true)))
      (recur))))

(defn- process-pending-takes
  "This is the loop that processes the pending takes after a succesfull
  put operation."
  [executor takes buf]
  (loop []
    (when (pos? (pt/-size buf))
      (when-let [taker (mlist/remove-first takes)]
        (when-let [take-fn (commit taker)]
          (px/exec executor (partial take-fn (pt/-poll buf))))
        (recur)))))

(defn- process-take-handler
  "When buffer is full or no buffer, we need to do a common task: if
  task is blockable, enqueue it and if task is not blockable we just
  cancel it."
  [takes handler]
  (if (pt/-blockable? handler)
    (mlist/add-last takes handler)
    (commit-and-run handler nil))
  nil)

(defn- process-put-handler
  "When buffer is full or no buffer, if task is blockable, enqueue it
  and if task is not blockable we just cancel it."
  [puts handler val]
  (if (pt/-blockable? handler)
    (mlist/add-last puts [handler val])
    (commit-and-run handler false))
  nil)

(defn take
  {:no-doc true}
  ([port]
   (let [d (p/deferred)]
     (pt/-take port (promise->handler d))
     d))
  ([port timeout-duration timeout-value]
   (let [d (p/deferred)
         h (promise->handler d)
         t (px/schedule timeout-duration
                        #(when-let [f (commit h)]
                           (f timeout-value)))]
     (pt/-take port h)
     (p/finally d (fn [_ _] (p/cancel t))))))

(defn put
  {:no-doc true}
  ([port val]
   (let [d (p/deferred)]
     (pt/-put port val (promise->handler d))
     d))
  ([port val timeout-duration timeout-value]
   (let [d (p/deferred)
         h (promise->handler d)
         t (px/schedule timeout-duration
                        #(when-let [f (commit h)]
                           (f timeout-value)))]
     (pt/-put port val h)
     (p/finally d (fn [_ _] (p/cancel t))))))

#?(:clj
   (defn chan->seq
     "Creates a seq that traverses channel until it is closed."
     [ch]
     (lazy-seq
      (when-let [val (pt/-join (take ch))]
        (cons val (chan->seq ch))))))

(deftype Channel [^:mutable ^:unsynchronized-mutable takes
                  ^:mutable ^:unsynchronized-mutable puts
                  ^:mutable ^:unsynchronized-mutable error
                  buf closed lock executor add-fn mdata]

  pt/ILock
  (-lock [_]
    (pt/-lock lock))
  (-unlock [_]
    (pt/-unlock lock))

  #?@(:bb []
      :cljs
      [cljs.core/IWithMeta
       (-with-meta [_ mdata] (Channel. takes puts error buf closed lock executor add-fn mdata))
       cljs.core/IMeta
       (-meta [_] mdata)]

      :clj
      [clojure.lang.IObj
       (meta [_] mdata)
       (withMeta [_ mdata] (Channel. takes puts error buf closed lock executor add-fn mdata))])

  #?@(:bb []
      :clj
      [clojure.lang.Seqable
       (seq [this] (chan->seq this))])

  pt/IChannelInternal
  (-cleanup [_]
    (loop [result (mlist/create)]
      (if-let [taker (mlist/remove-first takes)]
        (if (pt/-active? taker)
          (recur (mlist/add-last result taker))
          (recur result))
        (set! takes result)))
    (loop [result (mlist/create)]
      (if-let [[putter val :as item] (mlist/remove-first puts)]
        (if (pt/-active? putter)
          (recur (mlist/add-last result item))
          (recur result))
        (set! puts result))))

  pt/IWriteChannel
  (-put [this val handler]
    (when (nil? val)
      (throw (ex-info "Can't put nil on channel" {})))

    (pt/-lock this)
    (try
      (pt/-cleanup this)
      (if @closed
        (let [put-fn (commit handler)]
          (put-fn false)
          nil)
        (if buf
          (if (pt/-full? buf)
            (do
              (when (pt/-active? handler)
                (process-put-handler puts handler val))
              nil)

            (do
              (when (commit-and-run handler true)
                (when (reduced? (add-fn this buf val))
                  (pt/-close this))
                (process-pending-takes executor takes buf)
                nil)))

          (if-let [[take-fn put-fn] (lookup-put-transfer takes handler)]
            (do
              (put-fn true)
              (px/exec executor (partial take-fn val))
              nil)
            (when (pt/-active? handler)
              (process-put-handler puts handler val)))))

      (finally
        (pt/-unlock this))))

  pt/IReadChannel
  (-take [this handler]
    (try
      (pt/-lock this)
      (pt/-cleanup this)

      (if (and (not (nil? buf)) (pos? (pt/-size buf)))
        (when-let [take-fn (commit handler)]
          (let [val (pt/-poll buf)]
            (take-fn val nil))

          ;; Proces pending puts
          (let [done? (process-pending-puts this executor puts add-fn buf)]
            (if done?
              (do
                (when (pos? (mlist/size puts))
                  (abort executor puts))
                (pt/-close this))

              (when (and @closed (zero? (mlist/size puts)))
                (add-fn this buf)))

            nil))

        (if-let [[put-fn val take-fn] (lookup-take-transfer puts handler)]
          (do
            (take-fn val)
            (px/exec executor (partial put-fn true))
            nil)

          (if @closed
            (when-let [take-fn (commit handler)]
              (if-let [val (some-> buf pt/-poll)]
                (take-fn val nil)
                (take-fn nil error)))

            (when (pt/-active? handler)
              (process-take-handler takes handler)))))

      (finally
        (pt/-unlock this))))

  pt/ICloseable
  (-closed? [this] @closed)

  (-close [this] (pt/-close this nil))
  (-close [this cause]
    (pt/-lock this)
    (try
      (when (compare-and-set! closed false true)
        ;; assign a new cause, only if the `error` field is not set
        (when (and cause (not error))
          (set! error cause))


        (when (mlist/empty? puts)
          ;; flush transducer if we have buf and no pending puts
          (some->> buf (add-fn this)))

        ;; If we have pending takes, this also means we have no
        ;; pending puts
        (when (pos? (mlist/size takes))
          (loop []
            (when-let [taker (mlist/remove-first takes)]
              (when-let [take-fn (commit taker)]
                (if-let [val (some-> buf pt/-poll)]
                  (px/exec executor #(take-fn val nil))
                  (px/exec executor #(take-fn nil error))))
              (recur)))))

      (finally
        (some-> buf pt/-close)
        (pt/-unlock this)))))

(defn- add-fn
  ([b] b)
  ([b itm]
   (assert (not (nil? itm)))
   (pt/-offer b itm)
   b))

(defn channel?
  "Returns true if `o` is a channel instance or implements
  IReadChannel or IWriteChannel protocol."
  [o]
  (or (instance? Channel o)
      (satisfies? pt/IReadChannel o)
      (satisfies? pt/IWriteChannel o)))

(defn chan?
  "Returns true if `o` is a full duplex channel."
  [o]
  (or (instance? Channel o)
      (and (satisfies? pt/IReadChannel o)
           (satisfies? pt/IWriteChannel o))))

(defn rchan?
  "Returns true if `o` is a channel that supports read operations."
  [o]
  (or (instance? Channel o)
      (satisfies? pt/IReadChannel o)))

(defn wchan?
  "Returns true if `o` is a channel that supports write operations."
  [o]
  (or (instance? Channel o)
      (satisfies? pt/IReadChannel o)))

(defn throw-uncaught
  {:no-doc true}
  [_ cause]
  #?(:clj  (px/throw-uncaught cause)
     :cljs (js/console.error cause))
  nil)

(defn close-with-exception
  "A exception handler that closes the channel with error if an error."
  [ch cause]
  (pt/-close ch cause)
  nil)

(defn chan
  [buf xf exh exc]
  (let [add-fn (if xf (xf add-fn) add-fn)
        add-fn (fn
                 ;; This arity is called on closing the channel so
                 ;; return value doen't matter.
                 ([ch buf]
                  (try
                    (add-fn buf)
                    (catch #?(:clj Throwable :cljs :default) t
                      (when-let [v (exh ch t)]
                        (pt/-offer buf v)))))
                 ([ch buf val]
                  (try
                    (add-fn buf val)
                    (catch #?(:clj Throwable :cljs :default) t
                      (when-let [v (exh ch t)]
                        (pt/-offer buf v))))))
        ]
    (Channel. (mlist/create)
              (mlist/create)
              nil
              buf
              (atom false)
              (util/mutex)
              exc
              add-fn
              nil)))
