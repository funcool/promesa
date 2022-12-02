;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns ^:no-doc promesa.exec.csp.mutable-list
  "A internal abstraction for a mutable list that is used internally
  by channels."
  (:refer-clojure :exclude [count])
  (:require
   [promesa.exec :as px])
  #?(:clj
     (:import java.util.LinkedList)))

#?(:clj (set! *warn-on-reflection* true))

(defn create
  []
  #?(:clj (LinkedList.)
     :cljs #js []))

(defn add-first!
  [o v]
  #?(:clj  (do (.addFirst ^LinkedList o v) o)
     :cljs (do (.unshift ^js o v) o)))

(defn add-last!
  [o v]
  #?(:clj  (do (.add ^LinkedList o v) o)
     :cljs (do (.push ^js o v) o)))

(defn remove-last!
  "Remove the last element from list and return it. If no elements,
  `nil` is returned."
  [o]
  #?(:clj  (try
             (.removeLast ^LinkedList o)
             (catch java.util.NoSuchElementException _
               nil))
     :cljs (.pop ^js o)))

(defn size
  [o]
  #?(:clj  (.size ^LinkedList o)
     :cljs (.-length ^js o)))
