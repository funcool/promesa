;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.protocols
  "A generic promise abstraction and related protocols.")

(defprotocol IPromise
  (-fmap [it f] [it f executor]
    "Apply function to a computation")

  (-merr [it f] [it f executor]
    "Apply function to a failed computation and flatten 1 level")

  (-mcat [it f] [it f executor]
    "Apply function to a computation and flatten 1 level")

  (-hmap [it f] [it f executor]
    "Apply function to a computation independently if is failed or
    successful.")

  (-fnly [it f] [it f executor]
    "Apply function to a computation independently if is failed or
    successful; the return value is ignored.")

  (-then [it f] [it f executor]
    "Apply function to a computation and flatten multiple levels")
  )

(defprotocol IState
  "Additional state/introspection abstraction."
  (-extract [it] [it default] "Extract the current value.")
  (-resolved? [it] "Returns true if a promise is resolved.")
  (-rejected? [it] "Returns true if a promise is rejected.")
  (-pending? [it] "Retutns true if a promise is pending."))

(defprotocol IPromiseFactory
  "A promise constructor abstraction."
  (-promise [it] "Create a promise instance from other types"))

(defprotocol ICancellable
  "A cancellation abstraction."
  (-cancel! [it])
  (-cancelled? [it]))

(defprotocol ICompletable
  (-resolve! [it v] "Deliver a value to empty promise.")
  (-reject! [it e] "Deliver an error to empty promise."))

(defprotocol IExecutor
  (-exec! [it task] "Submit a task and return nil")
  (-run! [it task] "Submit a task and return a promise.")
  (-submit! [it task] "Submit a task and return a promise."))

(defprotocol IScheduler
  "A generic abstraction for scheduler facilities."
  (-schedule! [it ms func] "Schedule a function to be executed in future."))

(defprotocol ISemaphore
  "An experimental semaphore protocol, used internally; no public api"
  (-try-acquire! [it] [it n] [it n t] "Try acquire n or n permits, non-blocking or optional timeout")
  (-acquire! [it] [it n] "Acquire 1 or N permits")
  (-release! [it] [it n] "Release 1 or N permits"))

(defprotocol ILock
  "An experimental lock protocol, used internally; no public api"
  (-lock! [it])
  (-unlock! [it]))

(defprotocol IReadChannel
  (-take! [it handler]))

(defprotocol IWriteChannel
  (-put! [it val handler]))

(defprotocol IChannelInternal
  (^:no-doc -cleanup! [it]))

(defprotocol IChannelMultiplexer
  (^:no-doc -tap! [it ch close?])
  (^:no-doc -untap! [it ch]))

(defprotocol ICloseable
  (-closed? [it])
  (-close! [it] [it reason]))

(defprotocol IBuffer
  (-full? [it])
  (-poll! [it])
  (-offer! [it val])
  (-size [it]))

(defprotocol IHandler
  (-active? [it])
  (-commit! [it])
  (-blockable? [it]))

#?(:clj
   (defprotocol IAwaitable
     (-await! [it] [it duration] "block current thread await termination")))
