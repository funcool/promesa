;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.protocols
  "A generic promise abstraction and related protocols.")

(defprotocol IPromise
  "A promise abstraction."
  (-bind [_ f] [_ f executor]
    "Apply function to a computation and flatten.")

  (-map [_ f] [_ f executor]
    "Apply function to a computation")
  (-then [_ f] [_ f executor]
    "Apply function to a computation and flatten if promise found.")
  (-mapErr [_ f] [_ f executor]
    "Apply function to a failed computation.")
  (-thenErr [_ f] [_ f executor]
    "Apply function to a failed computation. and flatten if promise found.")

  (-handle [_ f] [_ f executor]
    "Apply function to a computation identpendently if is failed or
    successful and flatten if promise found.")
  (-finally [_ f] [_ f executor]
    "Apply function to a computation identpendently if is failed or
    successful; the return value is ignored."))

#?(:clj
   (defprotocol IState
     "Additional state/introspection abstraction."
     (-extract [_] "Extract the current value.")
     (-resolved? [_] "Returns true if a promise is resolved.")
     (-rejected? [_] "Returns true if a promise is rejected.")
     (-pending? [_] "Retutns true if a promise is pending.")))

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
