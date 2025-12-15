;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns ^:no-doc promesa.exec.csp.mutable-list
  "A internal abstraction for a mutable list that is used internally
  by channels."
  (:refer-clojure :exclude [count empty?])
  (:require
   [promesa.exec :as px])
  #?(:clj
     (:import java.util.LinkedList)))

#?(:clj (set! *warn-on-reflection* true))


;; Node type with mutable fields
#?(:cljs
   (deftype Node [^:mutable value
                  ^:mutable next
                  ^:mutable prev]))

#?(:cljs
   (deftype LinkedList [^:mutable head
                        ^:mutable tail
                        ^:mutable size]
     Object
     (addFirst [this v]
       (let [n (Node. v nil nil)]
         (if (nil? (.-head this))
           (do (set! (.-head this) n)
               (set! (.-tail this) n))
           (do (set! (.-next n) (.-head this))
               (set! (.-prev ^Node (.-head this)) n)
               (set! (.-head this) n)))
         (set! (.-size this) (inc (.-size this)))
         this))

     (addLast [this v]
       (let [n (Node. v nil nil)]
         (if (nil? (.-tail this))
           (do (set! (.-head this) n)
               (set! (.-tail this) n))
           (do (set! (.-prev n) (.-tail this))
               (set! (.-next (.-tail this)) n)
               (set! (.-tail this) n)))
         (set! (.-size this) (inc (.-size this)))
         this))

     (removeFirst [this]
       (when-let [h (.-head this)]
         (let [val (.-value h)]
           (set! (.-head this) (.-next h))
           (if (.-head this)
             (set! (.-prev ^Node (.-head this)) nil)
             (set! (.-tail this) nil))
           (set! (.-size this) (dec (.-size this)))
           val)))

     (removeLast [this]
       (when-let [t (.-tail this)]
         (let [val (.-value t)]
           (set! (.-tail  this) (.-prev ^Node t))
           (if (.-tail this)
             (set! (.-next (.-tail this)) nil)
             (set! (.-head this) nil))
           (set! (.-size this) (dec (.-size this)))
           val)))

     cljs.core/ISeqable
     (-seq [this]
       (letfn [(walk [n]
                 (when n
                   (lazy-seq
                    (cons (.-value n) (walk (.-next n))))))]
         (walk (.-head this))))))

(defn create
  []
  #?(:clj (LinkedList.)
     :cljs (LinkedList. nil nil 0)))

(defn add-first
  [o v]
  (.addFirst ^LinkedList o v)
  o)

(defn add-last
  [o v]
  (.addLast ^LinkedList o v)
  o)

(defn remove-last
  "Remove the last element from list and return it. If no elements,
  `nil` is returned."
  [o]
  (try
    (.removeLast ^LinkedList o)
    (catch #?(:clj java.util.NoSuchElementException :cljs :default) _
      nil)))

(defn remove-first
  "Remove the first element from list and return it. If no elements,
  `nil` is returned."
  [o]
  (try
    (.removeFirst ^LinkedList o)
    (catch #?(:clj java.util.NoSuchElementException :cljs :default) _
      nil)))

(defn size
  [o]
  #?(:clj  (.size ^LinkedList o)
     :cljs (.-size ^LinkedList o)))

(defn empty?
  [o]
  (zero? (size o)))
