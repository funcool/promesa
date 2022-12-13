;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec.async-atom
  "Async Atom

  An hybrid between an agent and an atom."
  (:refer-clojure :exclude [atom send])
  (:require
   [clojure.core :as c]
   [promesa.exec :as px]
   [promesa.protocols :as pt])
  (:import
   clojure.lang.IFn
   clojure.lang.IRef))

(set! *warn-on-reflection* true)

(deftype AAtom [executor state]
  IRef
  (deref [_]
    (.deref ^IRef state))
  (setValidator [_ vf]
    (.setValidator ^IRef state ^IFn vf))
  (getWatches [_]
    (.getWatches ^IRef state))
  (addWatch [_ k f]
    (.addWatch ^IRef state ^Object k ^IFn f))
  (removeWatch [_ k]
    (.removeWatch ^IRef state ^Object k))

  pt/IExecutor
  (-run! [_ f]
    (pt/-run! executor #(swap! state f)))
  (-submit! [_ f]
    (pt/-submit! executor #(swap! state f))))

(defn atom
  "Create a new async atom instance with `state` as initial value."
  [state]
  (let [factory  (px/thread-factory :name (str "promesa/async-atom/" (px/get-next)))
        executor (px/single-executor :factory factory)]
    (AAtom. executor (c/atom state))))

(defn wrap
  "Wrap an existing atom into an Async Atom instance."
  [atm]
  (let [factory  (px/thread-factory :name (str "promesa/async-atom/" (px/get-next)))
        executor (px/single-executor :factory factory)]
    (AAtom. executor atm)))

(defn send
  "Dispatch an action to an async atom. Returns a completable future
  witch will be resolved when operation completes with the internal
  value after function application."
  [atm f & args]
  (pt/-submit! atm #(apply f % args)))
