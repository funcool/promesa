;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns ^:no-doc promesa.util
  (:require [promesa.protocols :as pt])
  #?(:clj
     (:import
      java.lang.reflect.Method
      java.util.function.Function
      java.util.function.BiFunction
      java.util.function.BiConsumer
      java.util.function.Supplier
      java.util.function.Consumer
      java.util.concurrent.locks.ReentrantLock
      java.util.concurrent.CountDownLatch)))

#?(:clj
   (deftype SupplierWrapper [f]
     Supplier
     (get [_] (f))))

#?(:clj
   (deftype FunctionWrapper [f]
     Function
     (apply [_ v]
       (f v))))

#?(:clj
   (deftype BiFunctionWrapper [f]
     BiFunction
     (apply [_ a b]
       (f a b))))

#?(:clj
   (deftype BiConsumerWrapper [f]
     BiConsumer
     (accept [_ a b]
       (f a b))))

(defn has-method?
  [klass name]
  (let [methods (into #{}
                      (map (fn [method] (.getName ^Method method)))
                      (.getDeclaredMethods ^Class klass))]
    (contains? methods name)))

(defn maybe-deref
  [o]
  (if (delay? o)
    (deref o)
    o))

(defn mutex
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

(defn wait-all!
  [promises]
  (let [^CountDownLatch cdown (CountDownLatch. (count promises))]
    (doseq [p promises]
      (pt/-finally p (fn [_ _] (.countDown cdown))))
    (.await ^CountDownLatch cdown)))
