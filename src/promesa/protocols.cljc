(ns promesa.protocols)

(defprotocol IFuture
  "A basic future abstraction."
  (-map [_ callback] "Chain a promise.")
  (-bind [_ callback] "Chain a promise.")
  (-catch [_ callback] "Catch a error in a promise."))

(defprotocol IPromise
  "A basic promise abstraction."
  (-deliver [_ value] "Deliver a value into promise."))

(defprotocol IState
  "Additional state related abstraction."
  (-resolved? [_] "Returns true if a promise is resolved.")
  (-rejected? [_] "Returns true if a promise is rejected.")
  (-pending? [_] "Retutns true if a promise is pending."))

(defprotocol IPromiseFactory
  "A promise constructor abstraction."
  (-promise [_] "Create a promise instance."))

;; (defprotocol IAwaitable
;;   "Similar to IDeref but unwraps the exception. Intended to use
;;   only for jvm promises."
;;   (-await
;;     [awaitable]
;;     [awaitable ms]
;;     [awaitable ms default]))

