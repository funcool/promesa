;; Copyright (c) 2015 Andrey Antukh <niwi@niwi.nz>
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
            [cats.context :as mc]
            [cats.protocols :as mp]
            [promesa.protocols :as p]
            [org.bluebird]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare promise-context)

(extend-type js/Promise
  mp/Contextual
  (-get-context [_] promise-context)

  mp/Extract
  (-extract [it]
    (if (.isRejected it)
      (.reason it)
      (.value it)))

  p/IPromise
  (-then [it cb]
    (.then it cb))

  (-catch
    ([it cb]
     (.catch it cb))
    ([it type cb]
     (let [type (case type
                  :timeout (.-TimeoutError js/Promise)
                  :cancel (.-CancellationError js/Promise)
                  type)]
       (.catch it type cb))))

  p/IState
  (-resolved? [it]
    (.isFulfilled it))

  (-rejected? [it]
    (.isRejected it))

  (-done? [it]
    (not (.isPending it))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Constructors

(defn resolved
  "Return a resolved promise with provided value."
  [v]
  (.resolve js/Promise v))

(defn rejected
  "Return a rejected promise with provided reason."
  [v]
  (.reject js/Promise v))

(defn promise
  "The promise constructor."
  [v]
  (cond
    (fn? v) (js/Promise. v)
    (instance? js/Error v) (rejected v)
    :else (resolved v)))

;; Predicates

(defn promise?
  "Returns true if `p` is a primise instance."
  [p]
  (satisfies? p/IPromise p))

(defn resolved?
  "Returns true if promise `p` is already fulfilled."
  [p]
  (p/-resolved? p))

(defn fulfilled?
  "Convenience alias for the `resolved?` predicate."
  [p]
  (p/-resolved? p))

(defn rejected?
  "Returns true if promise `p` is already rejected."
  [p]
  (p/-rejected? p))

(defn pending?
  "Returns true if promise `p` is stil pending."
  [p]
  (not (p/-done? p)))

(defn done?
  "Returns true if promise `p` is already done."
  [p]
  (p/-done? p))

;; Chain operations

(defn then
  "A chain helper for promises."
  [p callback]
  (p/-then p callback))

(defn chain
  "A variadic chain operation."
  [p & funcs]
  (reduce #(then %1 %2) p funcs))

(defn catch
  "Catch all promise chain helper."
  ([p callback]
   (p/-catch p callback))
  ([p type callback]
   (p/-catch p type callback)))

(defn branch
  "A branching helper for promises."
  [p callback errback]
  (.then p callback errback))


;; Helpers

(defn all
  "Given an array of promises, return a promise
  that is fulfilled  when all the items in the
  array are fulfilled."
  [promises]
  (m/sequence promises))

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
  (then (.some js/Promise (clj->js promises) n)
        #(js->clj %)))

(defn promisify
  "Given a nodejs like function that accepts a callback
  as the last argument and return an other function
  that returns a promise."
  [callable]
  (fn [& args]
    (promise (fn [resolve]
               (let [args (-> (vec args)
                              (conj resolve))]
                 (apply callable args))))))

(defn timeout
  "Returns a cancellable promise that will be fulfilled
  with this promise's fulfillment value or rejection reason.
  However, if this promise is not fulfilled or rejected
  within `ms` milliseconds, the returned promise is cancelled
  with a TimeoutError"
  ([p t] (.timeout p t))
  ([p t v] (.timeout p t v)))

(defn delay
  "Given a timeout in miliseconds and optional
  value, returns a promise that will fulfilled
  with provided value (or nil) after the
  time is reached."
  ([t] (delay t nil))
  ([t v]
   (.delay js/Promise v t)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monad Type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:no-doc true}
  promise-context
  (reify
    mp/Context
    (-get-level [_] mc/+level-default+)

    mp/Functor
    (-fmap [mn f mv]
      (then mv f))

    mp/Applicative
    (-pure [_ v]
      (promise v))

    (-fapply [_ pf pv]
      (then (all [pf pv])
            (fn [[f v]]
              (f v))))

    mp/Semigroup
    (-mappend [_ mv mv']
      (p/-then (m/sequence [mv mv'])
               (fn [[mvv mvv']]
                 (let [ctx (mp/-get-context mvv)]
                   (mp/-mappend ctx mvv mvv')))))

    mp/Monad
    (-mreturn [_ v]
      (promise v))

    (-mbind [mn mv f]
      (then mv f))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pretty printing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- promise->map
  [p]
  (if (pending? p)
    {:status :pending}
    (let [v (m/extract p)]
      (if (rejected? p)
        {:status :rejected
         :error v}
        {:status :resolved
         :value v}))))

(defn- promise->str
  [p]
  (str "#<Promise " (pr-str (promise->map p)) ">"))

(extend-type js/Promise
  IPrintWithWriter
  (-pr-writer [p writer _]
    (-write writer (promise->str p))))
