;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec.csp.buffers
  (:require
   [promesa.exec.csp.mutable-list :as mlist]
   [promesa.protocols :as pt]))

(defn fixed
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
        (mlist/size buf)))))

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
        (mlist/size buf)))))

(defn sliding
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
        (mlist/size buf)))))
