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

  EXPERIMENTAL API"

  (:require
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp.buffers :as buffers]
   [promesa.exec.csp.channel :as channel]
   [promesa.protocols :as pt]))

(defmacro go
  [& body]
  `(->> (px/wrap-bindings (fn [] ~@body))
        (p/thread-call channel/*executor*)))

(defmacro go-loop
  [bindings & body]
  `(go (loop ~bindings ~@body)))

(defn chan
  ([] (chan nil nil nil))
  ([buf] (chan buf nil nil))
  ([buf xf] (chan buf xf nil))
  ([buf xf exh]
   (let [buf (if (number? buf)
               (buffers/fixed buf)
               buf)]
     (channel/chan buf xf exh))))

(defn put!
  [port val]
  (let [d (p/deferred)]
    (pt/-put! port val (channel/promise->handler d))
    d))

(defn take!
  [port]
  (let [d (p/deferred)]
    (pt/-take! port (channel/promise->handler d))
    d))

(defn >!
  "Perform a blocking put operation on the channel."
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
  "Perform a blocking take operation on the channel."
  ([port]
   (-> (take! port)
       (deref)))
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

(defn alts!
  [ports & {:keys [priority]}]
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
      (when-let [[op port val] (first ports)]
        (case op
          (:> :put)  (pt/-put! port val (handler port))
          (:< :take) (pt/-take! port (handler port)))
        (recur (rest ports))))
    ret))

(defn close!
  [port]
  (pt/-close! port)
  nil)

(defn closed?
  [port]
  (pt/-closed? port))

(defn timeout
  [ms]
  (let [ch (chan)]
    (px/schedule! ms #(pt/-close! ch))
    ch))

(defn slidding-buffer
  [n]
  (buffers/slidding n))

(defn dropping-buffer
  [n]
  (buffers/dropping n))

(defn fixed-buffer
  [n]
  (buffers/fixed n))

(defn offer!
  [port val]
  (let [o (volatile! nil)]
    (pt/-put! port val (channel/volatile->handler o))
    @o))

(defn poll!
  [port]
  (let [o (volatile! nil)]
    (pt/-take! port (channel/volatile->handler o))
    @o))

(defn pipe
  ([from to] (pipe from to true))
  ([from to close?]
   (go-loop []
     (let [v (<! from)]
       (if (nil? v)
         (when close? (close! to))
         (when (>! to v)
           (recur)))))
   to))

(defn onto-chan!
  ([ch coll] (onto-chan! ch coll true))
  ([ch coll close?]
   (go-loop [items (seq coll)]
     (if (and items (>! ch (first items)))
       (recur (next items))
       (when close?
         (close! ch))))))
