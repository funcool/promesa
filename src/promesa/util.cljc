;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.util
  (:require [promesa.protocols :as pt])
  #?(:clj
     (:import
      java.lang.reflect.Method
      java.time.Duration
      java.util.concurrent.CompletionException
      java.util.concurrent.CompletionStage
      java.util.concurrent.CountDownLatch
      java.util.concurrent.locks.ReentrantLock
      ;; java.util.function.BiConsumer
      ;; java.util.function.BiFunction
      ;; java.util.function.Consumer
      ;; java.util.function.Function
      ;; java.util.function.Supplier
      )))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (extend-protocol clojure.core/Inst
     Duration
     (inst-ms* [v] (.toMillis ^Duration v))))

#?(:clj
   (deftype Supplier [f]
     java.util.function.Supplier
     (get [_] (f))))

#?(:clj
   (deftype Function [f]
     java.util.function.Function
     (apply [_ v]
       (f v))))

#?(:clj
   (def f-identity (->Function identity)))

#?(:clj
   (defn unwrap-completion-stage
     {:no-doc true}
     [it]
     (.thenCompose ^CompletionStage it
                   ^java.util.function.Function f-identity)))

#?(:clj
   (defn unwrap-completion-exception
     {:no-doc true}
     [cause]
     (if (instance? CompletionException cause)
       (.getCause ^CompletionException cause)
       cause)))

#?(:clj
   (deftype Function2 [f]
     java.util.function.BiFunction
     (apply [_ r e]
       (f r (unwrap-completion-exception e)))))

#?(:clj
   (deftype Consumer2 [f]
     java.util.function.BiConsumer
     (accept [_ r e]
       (f r (unwrap-completion-exception e)))))

(defn handler
  "Create a handler, mainly for combine two separate functions
  into a single callbale."
  [fv fc]
  (fn [v c]
    (if c (fc c) (fv v))))

(defn has-method?
  {:no-doc true}
  [klass name]
  (let [methods (into #{}
                      (map (fn [method] (.getName ^Method method)))
                      (.getDeclaredMethods ^Class klass))]
    (contains? methods name)))

(defn maybe-deref
  {:no-doc true}
  [o]
  (if (delay? o)
    (deref o)
    o))

(defn mutex
  {:no-doc true}
  []
  #?(:clj
     (let [m (ReentrantLock.)]
       (reify
         pt/ILock
         (-lock! [_] (.lock m))
         (-unlock! [_] (.unlock m))))
     :cljs
     (reify
       pt/ILock
       (-lock! [_])
       (-unlock! [_]))))


(defn try*
  {:no-doc true}
  [f on-error]
  (try (f) (catch #?(:clj Throwable :cljs :default) e (on-error e))))

;; http://clj-me.cgrand.net/2013/09/11/macros-closures-and-unexpected-object-retention/
;; Explains the use of ^:once metadata

(defmacro ignoring
  [& exprs]
  `(try* (^:once fn* [] ~@exprs) (constantly nil)))

(defmacro try!
  [& exprs]
  `(try* (^:once fn* [] ~@exprs) identity))

