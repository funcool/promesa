;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
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

(ns promesa.core
  (:refer-clojure :exclude [delay spread promise await map mapcat])
  (:require [promesa.impl.promise :as pm]
            [promesa.impl.proto :as pt]
            [promesa.impl.scheduler :as ps])
  #?(:clj
     (:import java.util.concurrent.CompletableFuture
              java.util.concurrent.CompletionStage)))

;; --- Global Constants

#?(:cljs (def ^:const Promise pm/Promise))

#?(:clj
   (defn set-executor!
     "Replace the default executor instance with
     your own instance."
     [executor]
     (alter-var-root #'pm/+executor+ (constantly executor))))

;; --- Scheduling helpers

(defn schedule
  "Schedule a callable to be executed after the `ms` delay
  is reached.

  In JVM it uses a scheduled executor service and in JS
  it uses the `setTimeout` function."
  [ms func]
  (ps/schedule ms func))

;; --- Promise

(defn resolved
  "Return a resolved promise with provided value."
  [v]
  (pm/resolved v))

(defn rejected
  "Return a rejected promise with provided reason."
  [v]
  (pm/rejected v))

(defn promise
  "The promise constructor."
  [v]
  (pt/-promise v))

(defn promise?
  "Return true if `v` is a promise instance."
  [v]
  #?(:clj (instance? CompletionStage v)
     :cljs (instance? Promise v)))

;; Predicates

(defn resolved?
  "Returns true if promise `p` is already fulfilled."
  [p]
  (pt/-resolved? p))

(defn rejected?
  "Returns true if promise `p` is already rejected."
  [p]
  (pt/-rejected? p))

(defn pending?
  "Returns true if promise `p` is stil pending."
  [p]
  (pt/-pending? p))

(defn extract
  "Returns the current promise value."
  [p]
  (pt/-extract p))

(def done?
  "Returns true if promise `p` is already done."
  (complement pending?))

;; Chaining

(defn map
  "Apply a function to the promise value and
  return a new promise with the result."
  [f p]
  (pt/-map p f))

(defn mapcat
  "Same as `map` but removes one level of
  promise neesting. Useful when the map function
  returns a promise instead of value.

  In JS environment this function is analogous
  to `map` because the promise abstraction overloads
  the `map` operator."
  [f p]
  (pt/-bind p f))

(defn bind
  "A chain helper for promises."
  [p f]
  (pt/-bind p f))

(defn then
  "Same as `map` but with parameters inverted
  for convenience and for familiarity with
  javascript's promises `.then` operator."
  [p f]
  (pt/-map p f))

(defn chain
  "Like then but accepts multiple parameters."
  [p & funcs]
  (reduce #(then %1 %2) p funcs))

(defn branch
  [p success failure]
  (-> p
      (pt/-map success)
      (pt/-catch failure)))

(defn catch
  "Catch all promise chain helper."
  ([p f]
   (pt/-catch p f))
  ([p pred-or-type f]
   (let [accept? (if (ifn? pred-or-type)
                   pred-or-type
                   #(instance? pred-or-type %))]
     (pt/-catch p (fn [e]
                    (if (accept? e)
                      (f e)
                      (pm/rejected e)))))))

(defn error
  "Same as `catch` but with parameters inverted."
  ([f p] (catch p f))
  ([f type p] (catch p type f)))

(def err
  "A short alias for `error` function."
  error)

(defn finally
  "Attach handler to promise that will be
  executed independently if promise is
  resolved or rejected."
  [p callback]
  #?(:clj (-> p
              (then #(callback))
              (catch #(callback)))
     :cljs (.finally p callback)))

(defn all
  "Given an array of promises, return a promise
  that is fulfilled  when all the items in the
  array are fulfilled."
  [promises]
  #?(:cljs (then (.all Promise (into-array promises)) vec)
     :clj (let [promises (clojure.core/map pt/-promise promises)]
            (then (->> (into-array CompletableFuture promises)
                       (CompletableFuture/allOf))
                  (fn [_]
                    (mapv pt/-extract promises))))))

(defn any
  "Given an array of promises, return a promise
  that is fulfilled when first one item in the
  array is fulfilled."
  [promises]
  #?(:cljs (.any Promise (into-array promises))
     :clj (->> (clojure.core/map pt/-promise promises)
               (into-array CompletableFuture)
               (CompletableFuture/anyOf))))

;; Cancellation

(defn cancel!
  "Cancel the promise."
  [p]
  (pt/-cancel p)
  p)

(defn cancelled?
  "Return true if `v` is a cancelled promise."
  [v]
  (pt/-cancelled? v))

;; Utils

(defn promisify
  "Given a function that accepts a callback as the last argument return other
  function that returns a promise. Callback is expected to take single
  parameter (result of a computation)."
  [callable]
  (fn [& args]
    (promise (fn [resolve reject]
               (let [args (-> (vec args)
                              (conj resolve))]
                 (try
                   (apply callable args)
                   (catch #?(:clj Throwable :cljs js/Error) e
                     (reject e))))))))

#?(:cljs
   (defn timeout
     "Returns a cancellable promise that will be fulfilled
     with this promise's fulfillment value or rejection reason.
     However, if this promise is not fulfilled or rejected
     within `ms` milliseconds, the returned promise is cancelled
     with a TimeoutError"
     ([p t] (.timeout p t))
     ([p t v] (.timeout p t v))))

(defn delay
  "Given a timeout in miliseconds and optional
  value, returns a promise that will fulfilled
  with provided value (or nil) after the
  time is reached."
  ([t] (delay t nil))
  ([t v]
   #?(:cljs (.then (.delay Promise t)
                   (constantly v))
      :clj (let [p (CompletableFuture.)]
             (schedule t #(.complete p v))
             p))))

(defn attempt
  "A helper for start promise chain without worry about
  synchronous or asynchronous exceptions. Returns a promise
  resolved with the return value of the callback."
  [callback]
  #?(:cljs (promise (fn [resolve] (resolve (callback))))
     :clj  (promise (fn [resolve reject]
                      (let [result (callback)]
                        (if (promise? result)
                          (then result resolve)
                          (resolve result)))))))

#?(:clj
   (defmacro do*
     "A sugar syntax on top of `attempt`."
     [& body]
     `(attempt #(do ~@body))))

(defn await
  [& args]
  (throw (ex-info "Should be only used in alet macro." {})))

#?(:clj
   (defmacro alet
     "A `let` alternative that always returns promise and allows
     use `await` marker function in order to emulate the async/await
     syntax and make the let expression look like synchronous where
     async operations are performed."
     [bindings & body]
     (->> (reverse (partition 2 bindings))
          (reduce (fn [acc [l r]]
                    (if (and (coll? r) (symbol? (first r)))
                      (let [mark (name (first r))]
                        (if (= mark "await")
                          `(bind ~(second r) (fn [~l] ~acc))
                          `(let [~l ~r] ~acc)))
                      `(let [~l ~r] ~acc)))
                  `(promise (do ~@body))))))

