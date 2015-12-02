(ns promesa.protocols)

(defprotocol IPromise
  "A basic future abstraction."
  (-map [_ callback] "Chain a promise.")
  (-bind [_ callback] "Chain a promise.")
  (-catch [_ callback] "Catch a error in a promise."))

(defprotocol ICancellablePromise
  "A cancellation abstraction for promises."
  (-cancel [_] "Cancel promise.")
  (-cancelled? [_] "Check if promise is cancelled."))

(defprotocol IState
  "Additional state related abstraction."
  (-resolved? [_] "Returns true if a promise is resolved.")
  (-rejected? [_] "Returns true if a promise is rejected.")
  (-pending? [_] "Retutns true if a promise is pending."))

(defprotocol IPromiseFactory
  "A promise constructor abstraction."
  (-promise [_] "Create a promise instance."))
