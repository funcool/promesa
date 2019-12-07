;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;;
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

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
  (-run! [_ task] "Run a task and return a promise.")
  (-submit! [_ task] "Submit a task and return a promise."))

(defprotocol IScheduler
  "A generic abstraction for scheduler facilities."
  (-schedule! [_ ms func] "Schedule a function to be executed in future."))
