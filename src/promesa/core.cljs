;; Copyright (c) 2015 Andrey Antukh
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;;
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns promesa.core
  (:refer-clojure :exclude [delay spread some])
  (:require [cats.core :as m]
            [cats.protocols :as proto]
            [org.bluebird]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare then)
(declare promise)

(def ^{:no-doc true}
  promise-monad
  (reify
    proto/Functor
    (fmap [mn f mv]
      (then mv f))

    proto/Monad
    (mreturn [_ v]
      (promise v))

    (mbind [mn mv f]
      (let [ctx m/*context*]
        (then mv (fn [v]
                    (m/with-monad ctx
                      (f v))))))))

(extend-type js/Promise
  proto/Context
  (get-context [_] promise-monad)

  proto/Extract
  (extract [it]
    (.value it))

  IDeref
  (-deref [it]
    (.value it)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn resolved
  "Return a resolved promise with provided value."
  [v]
  (.resolve js/Promise v))

(defn rejected
  "Return a rejected promise with provided reason."
  [v]
  (.reject js/Promise v))

(defn promise
  "The promise instance constructor."
  [v]
  (cond
    (fn? v) (js/Promise. v)
    (instance? js/Error v) (rejected v)
    :else (resolved v)))

(defn promise?
  "Returns true if `p` is a primise
  instance."
  [p]
  (instance? js/Promise p))

(defn fulfilled?
  "Returns true if promise `p` is
  already fulfilled."
  [p]
  {:pre [(promise? p)]}
  (.isFulfilled p))

(defn rejected?
  "Returns true if promise `p` is
  already rejected."
  [p]
  {:pre [(promise? p)]}
  (.isRejected p))

(defn pending?
  "Returns true if promise `p` is
  stil pending."
  [p]
  {:pre [(promise? p)]}
  (.isPending p))

(defn cancellable?
  "Returns true if promise `p` is
  cancelable."
  [p]
  {:pre [(promise? p)]}
  (.isCancellable p))

(defn all
  "Given an array of promises, return a promise
  that is fulfilled  when all the items in the
  array are fulfilled."
  [promises]
  (.all js/Promise (clj->js promises)))

(defn any
  "Given an array of promises, return a promise
  that is fulfilled when first one item in the
  array is fulfilled."
  [promises]
  (.any js/Promise (clj->js promises)))

(defn some
  "Given an array of promises, return a promise
  that is fulfilled when `n` number of promises
  is fulfilled."
  [n promises]
  (.some js/Promise (clj->js promises) n))

(defn delay
  "Given a timeout in miliseconds and optional
  value, returns a promise that will fulfilled
  with provided value (or nil) after the
  time is reached."
  ([t] (delay t nil))
  ([t v]
   (.delay js/Promise v t)))

(defn timeout
  "Returns a cancellable promise that will be fulfilled
  with this promise's fulfillment value or rejection reason.
  However, if this promise is not fulfilled or rejected
  within `ms` milliseconds, the returned promise is cancelled
  with a TimeoutError"
  ([p t] (.timeout p t))
  ([p t v] (.timeout p t v)))

(defn then
  "A chain helper for promises."
  [p callback]
  (.then p callback))

(defn spread
  "A chain helper like `then` but recevies a
  resolved promises unrolled as parameters."
  [p callback]
  (.spread p callback))

(defn finally
  "A chain helper that associate handler to
  the promise that will be called regardless
  if it is resolved or rejected."
  [p callback]
  (.finally p callback))

(defn catch
  "Catch all promise chain helper."
  ([p callback]
   (.catch p callback))
  ([p type callback]
   (let [type (condp = type
                :timeout (.-TimeoutError js/Promise)
                :cancel (.-CancellationError js/Promise)
                type)]
     (.catch p type callback))))

(defn error
  "Catch operational errors promise chain helper."
  [p callback]
  (.error p callback))

(defn value
  "Get the fulfillment value of this promise.
  Throws an error if the promise isn't fulfilled."
  [p]
  (.value p))

(defn reason
  "Get the rejection reason of this promise.
  Throws an error if the promise isn't rejected."
  [p]
  (.reason p))

(defn promisify
  "Given a nodejs like function that accepts a callback
  as the last argument and return an other function
  that returns a promise."
  [callable]
  ;; (.promisify js/Promise callable)
  ;; Use own implementation because
  ;; due to strange reasons it not works
  ;; properly with bluebird implementation.
  (fn [& args]
    (promise (fn [resolve]
               (let [args (-> (vec args)
                              (conj resolve))]
                 (apply callable args))))))

(defn cancelable
  "Mark a promise as cancellable."
  [p]
  (.cancellable p))

(defn cancel
  "Cancel a cancellable promise."
  [p]
  (.cancel p))
