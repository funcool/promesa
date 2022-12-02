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
      java.time.Duration
      java.util.concurrent.CountDownLatch
      java.util.concurrent.locks.ReentrantLock
      java.util.function.BiConsumer
      java.util.function.BiFunction
      java.util.function.Consumer
      java.util.function.Function
      java.util.function.Supplier)))

#?(:clj (set! *warn-on-reflection* true))


#?(:clj
   (extend-protocol clojure.core/Inst
     Duration
     (inst-ms* [v] (.toMillis ^Duration v))))

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

(defn count-down-latch
  [n]
  (let [cdown (CountDownLatch. (int n))]
    (reify
      pt/IAwaitable
      (-await [_] (pt/-await cdown))
      (-await [_ d] (pt/-await cdown d))

      clojure.lang.IFn
      (invoke [_]
        (.countDown ^CountDownLatch cdown))
      (invoke [_ _]
        (.countDown ^CountDownLatch cdown))
      (invoke [_ _ _]
        (.countDown ^CountDownLatch cdown)))))

(defn wait-all!
  [promises]
  (let [total (count promises)]
    (when (pos? total)
      (let [cdown-fn (count-down-latch total)]
        (doseq [p promises]
          (pt/-finally p cdown-fn))
        (pt/-await cdown-fn)))))
