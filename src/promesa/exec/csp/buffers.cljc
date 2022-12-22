;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns ^:no-doc promesa.exec.csp.buffers
  (:require
   [promesa.exec.csp.mutable-list :as mlist]
   [promesa.protocols :as pt]))

#?(:clj (set! *warn-on-reflection* true))

(defn fixed
  "Fixed size buffer, does not expands it size under any circumstance.

  Take care when used with transducers that can insert multiple
  elements at once (like mapcat), if buffer is full, it will discard
  all other values."
  [n]
  (let [buf (mlist/create)]
    (reify
      pt/IBuffer
      (-full? [_]
        (>= (mlist/size buf) n))
      (-poll! [this]
        (mlist/remove-last! buf))
      (-offer! [this o]
        (if (>= (mlist/size buf) n)
          false
          (do
            (mlist/add-first! buf o)
            true)))
      (-size [_]
        (mlist/size buf))

      pt/ICloseable
      (-closed? [this] nil)
      (-close! [this] nil))))

(defn expanding
  "Fixed but with the ability to expand.

  Usefull when used with mapcat-like transducers that can insert
  multiple items in a single operation and can temporary exceed the
  predetermined size."
  [n]
  (let [buf (mlist/create)]
    (reify
      pt/IBuffer
      (-full? [_]
        (>= (mlist/size buf) n))
      (-poll! [this]
        (mlist/remove-last! buf))
      (-offer! [this o]
        (mlist/add-first! buf o)
        true)
      (-size [_]
        (mlist/size buf))

      pt/ICloseable
      (-closed? [this] nil)
      (-close! [this] nil))))

(defn dropping
  [n]
  (let [buf (mlist/create)]
    (reify
      pt/IBuffer
      (-full? [_] false)
      (-poll! [this]
        (mlist/remove-last! buf))
      (-offer! [this o]
        (when-not (>= (mlist/size buf) n)
          (mlist/add-first! buf o))
        true)
      (-size [_]
        (mlist/size buf))

      pt/ICloseable
      (-closed? [this] nil)
      (-close! [this] nil))))

(defn sliding
  "A buffer that works as sliding window, if max capacity is reached,
  the oldest items will start to discard."
  [n]
  (let [buf (mlist/create)]
    (reify
      pt/IBuffer
      (-full? [_] false)
      (-poll! [this]
        (mlist/remove-last! buf))
      (-offer! [this o]
        (when (= (mlist/size buf) n)
          (mlist/remove-last! buf))
        (mlist/add-first! buf o)
        true)
      (-size [_]
        (mlist/size buf))

      pt/ICloseable
      (-closed? [this] nil)
      (-close! [this] nil))))

(def no-val
  #?(:clj (Object.)
     :cljs (js/Symbol "sem")))

(deftype OnceBuffer [^:unsynchronized-mutable ^Object value
                     ^:unsynchronized-mutable ^Boolean closed]
  pt/IBuffer
  (-full? [_] false)
  (-poll! [this]
    (when-not closed
      value))

  (-offer! [this o]
    (when (identical? value no-val)
      (set! value o)
      true))

  (-size [_]
    (if (or (true? closed)
            (identical? value no-val))
      0
      1))

  pt/ICloseable
  (-closed? [this] closed)
  (-close! [this] (set! closed true)))

(defn once
  "Creates a promise like buffer that holds a single value and only
  the first one. Once filled, the only option for it to be emptied is
  closing."
  []
  (OnceBuffer. no-val false))
