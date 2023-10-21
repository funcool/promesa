;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.exec.semaphore
  "Concurrency limiter: Semaphore"
  (:require
   [promesa.protocols :as pt])
  (:import
   java.time.Duration
   java.util.concurrent.Semaphore
   java.util.concurrent.TimeUnit))

(set! *warn-on-reflection* true)

(extend-type Semaphore
  pt/ISemaphore
  (-try-acquire!
    ([this] (.tryAcquire ^Semaphore this))
    ([this permits] (.tryAcquire ^Semaphore this (int permits)))
    ([this permits timeout]
     (let [timeout (if (instance? Duration timeout)
                     (.toMillis ^Duration timeout)
                     timeout)]
       (.tryAcquire ^Semaphore this
                    (int permits)
                    (long timeout)
                    TimeUnit/MILLISECONDS))))
  (-acquire!
    ([this] (.acquire ^Semaphore this) true)
    ([this permits] (.acquire ^Semaphore this (int permits)) true))
  (-release!
    ([this] (.release ^Semaphore this))
    ([this permits] (.release ^Semaphore this (int permits)))))

(defn acquire!
  ([sem] (pt/-acquire! sem))
  ([sem & {:keys [permits timeout blocking] :or {blocking true permits 1}}]
   (if timeout
     (pt/-try-acquire! sem permits timeout)
     (if blocking
       (pt/-acquire! sem permits)
       (pt/-try-acquire! sem permits)))))

(defn release!
  ([sem] (pt/-release! sem))
  ([sem & {:keys [permits]}]
   (pt/-release! sem permits)))

(defn create
  "Creates a Semaphore instance."
  ^Semaphore
  [& {:keys [permits] :or {permits 1}}]
  (Semaphore. (int permits)))
