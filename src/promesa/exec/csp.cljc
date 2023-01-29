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
  (:refer-clojure :exclude [take])
  (:require
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp.buffers :as buffers]
   [promesa.exec.csp.channel :as channel]
   [promesa.protocols :as pt]
   [promesa.util :as pu]))

#?(:clj (set! *warn-on-reflection* true))

(defmacro go
  "Schedules the body to be executed asychronously, potentially using
  virtual thread if available (a normal thread will be used in other
  case). Returns a promise instance that resolves with the return
  value when the asynchronous block finishes.

  Forwards dynamic bindings."
  [& body]
  (when (:ns &env)
    (throw (UnsupportedOperationException. "cljs not supported")))
  `(->> (px/wrap-bindings (fn [] ~@body))
        (p/thread-call channel/*executor*)))

(defmacro go-loop
  "A convencience helper macro that combines go + loop."
  [bindings & body]
  `(go (loop ~bindings ~@body)))

(declare offer!)
(declare close!)
(declare chan)

(defmacro go-chan
  "A convencience go macro version that returns a channel instead
  of a promise instance."
  [& body]
  `(let [c# (chan 1)]
     (->> (go ~@body)
          (p/fnly (fn [v# e#]
                    (offer! c# (or v# e#))
                    (close! c#))))
     c#))

(defn chan
  "Creates a new channel instance, it optionally accepts buffer,
  transducer and error handler. If buffer is an integer, it will be
  used as initial capacity for the expanding buffer."
  ([] (chan nil nil nil))
  ([buf] (chan buf nil nil))
  ([buf xf] (chan buf xf nil))
  ([buf xf exh]
   (assert (or (nil? buf)
               (and (int? buf) (pos? buf))
               (satisfies? pt/IBuffer buf))
           "`buf` can be nil, positive int or IBuffer instance")
   (assert (or (nil? xf) (and (fn? xf) (some? buf)))
           "xf can be nil or fn (if fn, buf should be present")
   (let [buf (if (number? buf)
               (buffers/expanding buf)
               buf)]
     (channel/chan buf xf exh))))

(defn put
  "Schedules a put operation on the channel. Returns a promise
  instance that will resolve to: false if channel is closed, true if
  put is succeed. If channel has buffer, it will return immediatelly
  with resolved promise.

  Optionally accepts a timeout-duration and timeout-value. The
  `timeout-duration` can be a long or Duration instance measured in
  milliseconds."
  ([port val]
   (channel/put port val))
  ([port val timeout-duration]
   (channel/put port val timeout-duration nil))
  ([port val timeout-duration timeout-value]
   (channel/put port val timeout-duration timeout-value)))

#?(:clj
   (defn put!
     "A blocking version of `put`."
     ([port val]
      (p/await! (put port val)))
     ([port val timeout-duration]
      (p/await! (put port val timeout-duration nil)))
     ([port val timeout-duration timeout-value]
      (p/await! (put port val timeout-duration timeout-value)))))

#?(:clj
   (defn >!
     "A convenience alias for `put!`."
     ([port val]
      (p/await! (put port val)))
     ([port val timeout-duration]
      (p/await! (put port val timeout-duration nil)))
     ([port val timeout-duration timeout-value]
      (p/await! (put port val timeout-duration timeout-value)))))

(defn take
  "Schedules a take operation on the channel. Returns a promise instance
  that will resolve to: nil if channel is closed, obj if value is
  found. If channel has non-empty buffer the take operation will
  succeed immediatelly with resolved promise.

  Optionally accepts a timeout-duration and timeout-value. The
  `timeout-duration` can be a long or Duration instance measured in
  milliseconds."
  ([port]
   (channel/take port))
  ([port timeout-duration]
   (channel/take port timeout-duration nil))
  ([port timeout-duration timeout-value]
   (channel/take port timeout-duration timeout-value)))

#?(:clj
   (defn take!
     "Blocking version of `take`."
     ([port]
      (p/await! (take port)))
     ([port timeout-duration]
      (p/await! (take port timeout-duration nil)))
     ([port timeout-duration timeout-value]
      (p/await! (take port timeout-duration timeout-value)))))

#?(:clj
   (defn <!
     "A convenience alias for `take!`."
     ([port]
      (p/await! (take port)))
     ([port timeout-duration]
      (p/await! (take port timeout-duration nil)))
     ([port timeout-duration timeout-value]
      (p/await! (take port timeout-duration timeout-value)))))

(defn- alts*
  [ports {:keys [priority]}]
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
    ret))

(defn alts
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
  (alts* ports opts))

#?(:clj
   (defn alts!
     "A blocking variant of `alts`."
     [ports & {:as opts}]
     (p/await! (alts* ports opts))))

(defn close!
  "Close the channel."
  [port]
  (pt/-close! port)
  nil)

(defn closed?
  "Returns true if channel is closed."
  [port]
  (pt/-closed? port))

(defn chan?
  "Returns true if `o` is instance of Channel or satisfies IChannel protocol."
  [o]
  (channel/chan? o))

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
  default scheduler will be used."
  [ms]
  (p/delay ms nil :default))

(defn sliding-buffer
  "Create a sliding buffer instance."
  [n]
  (buffers/sliding n))

(defn dropping-buffer
  "Create a dropping buffer instance."
  [n]
  (buffers/dropping n))

(defn fixed-buffer
  "Create a fixed size buffer instance."
  [n]
  (buffers/fixed n))

(defn once-buffer
  "Create a once buffer instance."
  []
  (buffers/once))

(defn expanding-buffer
  "Create a fixed size (but expanding) buffer instance.

  This buffer is used by default if you pass an integer as buffer on
  channel constructor."
  [n]
  (buffers/expanding n))

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
  stop consuming the from channel if the to channel closes.

  Do not creates vthreads (or threads).
  "
  ([from to] (pipe from to true))
  ([from to close?]
   (p/loop []
     (->> (take from)
          (p/mcat (fn [v]
                    (if (nil? v)
                      (do
                        (when close? (pt/-close! to))
                        (p/resolved nil))
                      (->> (put to v)
                           (p/map (fn [res]
                                    (when res
                                      (p/recur))))))))))
   to))

(defn onto-chan!
  "Puts the contents of coll into the supplied channel.

  By default the channel will be closed after the items are copied,
  but can be determined by the close? parameter. Returns a promise
  that will be resolved with `nil` once the items are copied.

  Do not creates vthreads (or threads)."
  ([ch coll] (onto-chan! ch coll true))
  ([ch coll close?]
   (p/loop [items (seq coll)]
     (if items
       (->> (put ch (first items))
            (p/map (fn [res]
                     (if res
                       (p/recur (next items))
                       (p/recur nil)))))
       (when close?
         (pt/-close! ch))))))

(defn mult*
  "Create a multiplexer with an externally provided channel. From now,
  you can use the external channel or the multiplexer instace to put
  values in because multiplexer implements the IWriteChannel protocol.

  Optionally accepts `close?` argument, that determines if the channel will
  be closed when `close!` is called on multiplexer o not.

  Do not creates vthreads (or threads)."
  ([ch] (mult* ch false))
  ([ch close?]
   (let [state (atom {})
         mx    (reify
                 pt/IChannelMultiplexer
                 (-tap! [_ ch close?]
                   (swap! state assoc ch close?))
                 (-untap! [_ ch]
                   (swap! state dissoc ch))

                 pt/ICloseable
                 (-close! [_]
                   (when close? (pt/-close! ch))
                   (->> @state
                        (filter (comp true? peek))
                        (run! (comp pt/-close! key))))

                 pt/IWriteChannel
                 (-put! [_ val handler]
                   (pt/-put! ch val handler)))]

     (p/loop []
       (->> (take ch)
            (p/mcat (fn [v]
                      (if (nil? v)
                        (do (pt/-close! mx) (p/resolved nil))
                        (->> (p/wait-all* (for [ch (-> @state keys vec)]
                                            (->> (put ch v)
                                                 (p/fnly (fn [v _]
                                                           (when (nil? v)
                                                             (pt/-untap! mx ch)))))))
                             (p/finally (fn [_ _] (p/recur)))))))))

     mx)))

(defn mult
  "Creates an instance of multiplexer.

  A multiplexer instance acts like a write-only channel what enables a
  broadcast-like (instead of a queue-like) behavior. Channels
  containing copies of this multiplexer can be attached using `tap!`
  and dettached with `untap!`.

  Each item is forwarded to all attached channels in parallel and
  synchronously; use buffers to prevent slow taps from holding up the
  multiplexer.

  If there are no taps, all received items will be dropped. Closed
  channels will be automatically removed from multiplexer.

  Do not creates vthreads (or threads)."
  ([] (mult nil nil nil))
  ([buf] (mult buf nil nil))
  ([buf xform] (mult buf xform nil))
  ([buf xform exh]
   (let [ch (chan buf xform exh)]
     (mult* ch true))))

(defn tap!
  "Copies the multiplexer source onto the provided channel."
  ([mult ch]
   (pt/-tap! mult ch true)
   ch)
  ([mult ch close?]
   (pt/-tap! mult ch close?)
   ch))

(defn untap!
  "Disconnects a channel from the multiplexer."
  [mult ch]
  (pt/-untap! mult ch)
  ch)

#?(:clj
(defn pipeline
  "Create a processing pipeline with the ability to specify the process
  function `proc-fn`, the type of concurrency primitive to
  use (`:thread` or `:vthread`) and the parallelism.

  The `proc-fn` should be a function that takes the value and the
  result channel; the result channel should be closed once the
  processing unit is finished.

  By default the output channel is closed when pipeline is terminated,
  but it can be specified by user using the `:close?` parameter.

  Returns a promise which will be resolved once the pipeline is
  terminated.

  Example:

    (def inp (sp/chan))
    (def out (sp/chan (map inc)))

    (sp/pipeline :typ :vthread
                 :close? true
                 :n 10
                 :in inp
                 :out out
                 :f proc-fn)

    (sp/go
      (loop []
        (when-let [val (sp/<! out)]
          (prn \"RES:\" val)
          (recur)))
      (prn \"RES: END\"))

    (p/await! (sp/onto-chan! inp [\"1\" \"2\" \"3\" \"4\"] true))

  EXPERIMENTAL: API subject to be changed or removed in future
  releases."
  [& {:keys [typ in out f close? n exh]
      :or {typ :thread close? true exh channel/ex-handler}}]
  (assert (pos? n) "the worker number should be positive number")
  (assert (chan? in) "`in` parameter is required")
  (assert (chan? out) "`outpu` parameter is required")
  (assert (fn? f) "`f` parameter is required")
  (let [jch (chan n)
        rch (chan n)
        xfm (comp
             (map (fn [i]
                    #(try
                       (loop []
                         (when-let [[val rch] (<! jch)]
                           (f val rch)
                           (recur)))
                       (catch Throwable cause
                         (exh cause)
                         (close! jch)
                         (close! rch)
                         (when close?
                           (close! out))))))
             (map (fn [f]
                    (if (= typ :vthread)
                      (p/vthread (f))
                      (p/thread (f))))))]

     (go-loop []
       (if-let [val (<! in)]
         (let [res-ch (chan)]
           (if (>! jch [val res-ch])
             (if (>! rch res-ch)
               (recur)
               (close! jch))
             (close! rch)))
         (do
           (close! rch)
           (close! jch))))

     (go-loop []
       (if-let [rch' (<! rch)]
         (do
           (loop []
             (when-let [val (<! rch')]
               (if (>! out val)
                 (recur)
                 (do
                   (close! jch)
                   (close! rch)))))
           (recur))
         (when close?
           (close! out))))

     (-> (into #{} xfm (range n))
         (p/wait-all*)))))
