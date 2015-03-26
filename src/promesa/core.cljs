;; Copyright (c) 2015 Andrey Antukh
;; Copyright (c) 2015 Alejandro Gómez
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
  (:refer-clojure :exclude [delay])
  (:require [cats.core :as m]
            [cats.protocols :as proto]
            [org.bluebird]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare then)

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
    (.value it)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn promise
  "The promise instance constructor."
  [v]
  (cond
    (fn? v) (js/Promise. v)
    :else (.resolve js/Promise v)))

(defn promise?
  [p]
  (instance? js/Promise p))

(defn fulfilled?
  [p]
  {:pre [(promise? p)]}
  (.isFulFilled p))

(defn rejected?
  [p]
  {:pre [(promise? p)]}
  (.isRejected p))

(defn pending?
  [p]
  {:pre [(promise? p)]}
  (.isPending p))

(defn all
  [& promises]
  (.all js/Promise promises))

(defn any
  [& promises]
  (.any js/Promise promises))

(defn delay
  ([t] (delay t nil))
  ([t v]
   (.delay js/Promise v t)))

(defn then
  [p callback]
  (.then p callback))

(defn catch
  [p callback]
  (.catch p callback))

