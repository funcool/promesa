;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec.csp
  "A core.async analogous implementation of channels that uses promises
  instead of callbacks for all operations and are intended to be used
  as-is (using blocking operations) in go-blocks backed by virtual
  threads.

  There are no macro transformations, go blocks are just alias for the
  `promesa.core/vthread` macro that launches an virtual thread.

  This code is based on the same ideas as core.async but the
  implementation is written from scratch, for make it more
  simplier (and smaller, because it does not intend to solve all the
  corner cases that core.async does right now).

  This code is implemented in CLJS for make available the channel
  abstraction to the CLJS, but the main use case for this ns is
  targeted to the JVM, where you will be able to take advantage of
  virtual threads and seamless blocking operations on channels.

  **EXPERIMENTAL API**"

  (:require
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp.buffers :as buffers]
   [promesa.exec.csp.channel :as channel]
   [promesa.protocols :as pt]))

(defmacro go
  "Schedules the body to be executed asychronously, potentially using
  virtual thread if available (a normal thread will be used in other
  case). Returns a promise instance that resolves with the return
  value when the asynchronous block finishes."
  [& body]
  `(->> (px/wrap-bindings (fn [] ~@body))
        (p/thread-call channel/*executor*)))

(defmacro go-loop
  "A convencience helper macro that combines go + loop."
  [bindings & body]
  `(go (loop ~bindings ~@body)))

(defn chan
  "Creates a new channel instance, it optionally accepts buffer,
  transducer and error handler."
  ([] (chan nil nil nil))
  ([buf] (chan buf nil nil))
  ([buf xf] (chan buf xf nil))
  ([buf xf exh]
   (let [buf (if (number? buf)
               (buffers/fixed buf)
               buf)]
     (channel/chan buf xf exh))))

(defn put!
  "Schedules a put operation on the channel. Returns a promise
  instance that will resolve to: false if channel is closed, true if
  put is succeed. If channel has buffer, it will return immediatelly
  with resolved promise."
  [port val]
  (let [d (p/deferred)]
    (pt/-put! port val (channel/promise->handler d))
    d))

(defn take!
  "Schedules a take operation on the channel. Returns a promise
  instance that will resolve to: nil if channel is closed, obj if
  value is found. If channel has non-empty buffer the take operation
  will succeed immediatelly with resolved promise."
  [port]
  (let [d (p/deferred)]
    (pt/-take! port (channel/promise->handler d))
    d))

(defn >!
  "Perform a blocking put operation with optional timeout
  handling. Analogous to @(put! port val)."
  ([port val]
   (-> (put! port val)
       (deref)))
  ([port val timeout-ms]
   (>! port val timeout-ms nil))
  ([port val timeout-ms timeout-val]
   (let [d (p/deferred)
         h (channel/promise->handler d)
         t (px/schedule! timeout-ms
                         #(when-let [f (channel/commit! h)]
                            (f timeout-val)))]
     (pt/-put! port val h)
     (deref (p/finally d (fn [_ _] (p/cancel! t)))))))

(defn <!
  "Perform a blocking put operation with optional timeout
  handling. Analogous to @(take! port)."
  ([port]
   (-> port take! deref))
  ([port timeout-ms]
   (<! port timeout-ms nil))
  ([port timeout-ms timeout-val]
   (let [d (p/deferred)
         h (channel/promise->handler d)
         t (px/schedule! timeout-ms
                         #(when-let [f (channel/commit! h)]
                            (f timeout-val)))]
     (pt/-take! port h)
     (deref (p/finally d (fn [_ _] (p/cancel! t)))))))

(defn alts
  ([ports] (alts ports {}))
  ([ports {:keys [priority]}]
   (let [ret     (p/deferred)
         lock    (channel/promise->handler ret)
         ports   (if priority ports (shuffle ports))
         handler (fn [port]
                   (reify
                     pt/ILock
                     (-lock! [_] (pt/-lock! lock))
                     (-unlock! [_] (pt/-unlock! lock))

                     pt/IHandler
                     (-active? [_] (pt/-active? lock))
                     (-blockable? [_] (pt/-blockable? lock))
                     (-commit! [_]
                       (when-let [f (pt/-commit! lock)]
                         (fn [val]
                           (f [val port]))))))]
     (loop [ports (seq ports)]
       (when-let [port (first ports)]
         (if (vector? port)
           (let [[port val] port]
             (pt/-put! port val (handler port)))
           (pt/-take! port (handler port)))
         (recur (rest ports))))
     ret)))

(defn alts!
  "Completes at most one of several operations on channel. Receives a
  vector of operations and optional keyword options.

  A channel operation is defined as a vector of 2 elements for take,
  and 3 elements for put. Unless the :priority option is true and if
  more than one channel operation is ready, a non-deterministic choice
  will be made.

  Returns a promise instance that will be resolved when a single
  operation is ready to a vector [val channel] where val is return
  value of the operation and channel identifies the channel where the
  the operation is succeeded."
  [ports & {:as opts}]
  (deref (alts ports opts)))

(defn close!
  "Close the channel."
  [port]
  (pt/-close! port)
  nil)

(defn closed?
  "Returns true if channel is closed."
  [port]
  (pt/-closed? port))

(defn sleep
  "Turn the current thread to sleep."
  [ms]
  (Thread/sleep (int ms)))

(defn timeout-chan
  "Returns a channel that will be closed in the specified timeout. The
  default scheduler will be used. You can provide your own as optional
  first argument."
  ([ms]
   (let [ch (chan)]
     (px/schedule! ms #(pt/-close! ch))
     ch))
  ([scheduler ms]
   (let [ch (chan)]
     (px/schedule! scheduler ms #(pt/-close! ch))
     ch)))

(defn timeout
  "Returns a promise that will be resolved in the specified timeout. The
  default scheduler will be used. You can provide your own as optional
  first argument."
  [ms]
  (go (Thread/sleep (int ms))))

(defn slidding-buffer
  "Create a slidding buffer instance."
  [n]
  (buffers/slidding n))

(defn dropping-buffer
  "Create a dropping buffer instance."
  [n]
  (buffers/dropping n))

(defn fixed-buffer
  "Create a fixed size buffer instance."
  [n]
  (buffers/fixed n))

(defn offer!
  "Puts a val into channel if it's possible to do so immediately.
  Returns a resolved promise with `true` if the operation
  succeeded. Never blocks."
  [port val]
  (let [o (volatile! nil)]
    (pt/-put! port val (channel/volatile->handler o))
    @o))

(defn poll!
  "Takes a val from port if it's possible to do so
  immediatelly. Returns a resolved promise with the value if
  succeeded, `nil` otherwise."
  [port]
  (let [o (volatile! nil)]
    (pt/-take! port (channel/volatile->handler o))
    @o))

(defn pipe
  "Takes elements from the from channel and supplies them to the to
  channel. By default, the to channel will be closed when the from
  channel closes, but can be determined by the close?  parameter. Will
  stop consuming the from channel if the to channel closes."
  ([from to] (pipe from to true))
  ([from to close?]
   (go-loop []
     (let [v (<! from)]
       (if (nil? v)
         (when close? (pt/-close! to))
         (when (>! to v)
           (recur)))))
   to))

(defn onto-chan!
  "Puts the contents of coll into the supplied channel.

  By default the channel will be closed after the items are copied,
  but can be determined by the close? parameter. Returns a channel
  which will close after the items are copied."
  ([ch coll] (onto-chan! ch coll true))
  ([ch coll close?]
   (go-loop [items (seq coll)]
     (if (and items (>! ch (first items)))
       (recur (next items))
       (when close?
         (pt/-close! ch))))))
