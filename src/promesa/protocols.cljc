;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.protocols
  "A generic promise abstraction and related protocols.")

(defprotocol IPromise
  (-fmap [_ f] [_ f executor]
    "Apply function to a computation")

  (-merr [_ f] [_ f executor]
    "Apply function to a failed computation and flatten 1 level")

  (-mcat [_ f] [_ f executor]
    "Apply function to a computation and flatten 1 level")

  (-hmap [_ f] [_ f executor]
    "Apply function to a computation independently if is failed or
    successful.")

  (-fnly [_ f] [_ f executor]
    "Apply function to a computation independently if is failed or
    successful; the return value is ignored.")

  (-then [_ f] [_ f executor]
    "Apply function to a computation and flatten multiple levels")

  )


(defprotocol IState
  "Additional state/introspection abstraction."
  (-extract [_] "Extract the current value.")
  (-resolved? [_] "Returns true if a promise is resolved.")
  (-rejected? [_] "Returns true if a promise is rejected.")
  (-pending? [_] "Retutns true if a promise is pending."))

(defprotocol IPromiseFactory
  "A promise constructor abstraction."
  (-promise [_] "Create a promise instance from other types"))

(defprotocol ICancellable
  "A cancellation abstraction."
  (-cancel! [_])
  (-cancelled? [_]))

(defprotocol ICompletable
  (-resolve! [_ v] "Deliver a value to empty promise.")
  (-reject! [_ e] "Deliver an error to empty promise."))

(defprotocol IExecutor
  (-run! [_ task] "Submit a task and return a promise.")
  (-submit! [_ task] "Submit a task and return a promise."))

(defprotocol IScheduler
  "A generic abstraction for scheduler facilities."
  (-schedule! [_ ms func] "Schedule a function to be executed in future."))

(defprotocol ISemaphore
  "An experimental semaphore protocol, used internally; no public api"
  (-try-acquire! [_] [_ n] "Try acquire 1 or n permits; not blocking operation")
  (-acquire! [_] [_ n] "Acquire 1 or N permits")
  (-release! [_] [_ n] "Release 1 or N permits"))

(defprotocol ILock
  "An experimental lock protocol, used internally; no public api"
  (-lock! [_])
  (-unlock! [_]))

(defprotocol IReadChannel
  (-take! [_ handler]))

(defprotocol IWriteChannel
  (-put! [_ val handler]))

(defprotocol IChannelInternal
  (^:no-doc -cleanup! [_]))

(defprotocol IChannelMultiplexer
  (^:no-doc -tap! [_ ch close?])
  (^:no-doc -untap! [_ ch]))

(defprotocol ICloseable
  (-closed? [_])
  (-close! [_]))

(defprotocol IBuffer
  (-full? [_])
  (-poll! [_])
  (-offer! [_ val])
  (-size [_]))

(defprotocol IHandler
  (-active? [_])
  (-commit! [_])
  (-blockable? [_]))

#?(:clj
   (defprotocol IAwaitable
     (-await! [_] [_ duration] "block current thread await termination")))
