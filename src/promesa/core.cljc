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

(ns promesa.core
  (:refer-clojure :exclude [delay spread promise
                            await map mapcat run!
                            future let loop recur])
  (:require
   [promesa.protocols :as pt]
   [clojure.core :as c]
   [promesa.exec :as exec]
   [promesa.impl :as impl])
  #?(:cljs (:require-macros [promesa.core]))
  #?(:clj
     (:import
      java.util.concurrent.CompletableFuture
      java.util.concurrent.CompletionStage
      java.util.concurrent.TimeoutException)))

;; --- Promise

(defn resolved
  "Return a resolved promise with provided value."
  [v]
  (impl/resolved v))

(defn rejected
  "Return a rejected promise with provided reason."
  [v]
  (impl/rejected v))

(defn deferred
  "Creates an empty promise instance."
  []
  (impl/deferred))

(defn promise
  "The coerce based promise constructor. Creates a appropriate promise
  instance depending on the provided value.

  If an executor is provided, it will be used to resolve this
  promise."
  ([v] (pt/-promise v))
  ([v executor]
   (pt/-map v identity executor)))

(defn create
  "Create a promise instance from a factory function. If an executor is
  provided, the factory will be executed in the provided executor.

  A factory function looks like `(fn [resolve reject] (resolve 1))`."
  ([f]
   (c/let [d (impl/deferred)]
     (try
       (f #(pt/-resolve! d %)
          #(pt/-reject! d %))
       (catch #?(:clj Exception :cljs :default) e
         (pt/-reject! d e)))
     d))
  ([f executor]
   (c/let [d (impl/deferred)]
     (exec/run! executor (fn []
                           (try
                             (f #(pt/-resolve! d %)
                                #(pt/-reject! d %))
                             (catch #?(:clj Exception :cljs :default) e
                               (pt/-reject! d e)))))
     d)))

(defn promise?
  "Return true if `v` is a promise instance."
  [v]
  #?(:clj (instance? CompletionStage v)
     :cljs (instance? impl/*default-promise* v)))

(defn deferred?
  "Return true if `v` is a promise instance (alias to `promise?`."
  [v]
  #?(:clj (instance? CompletionStage v)
     :cljs (instance? impl/*default-promise* v)))

#?(:cljs
   (defn thenable?
     "Returns true if `v` is a promise like object."
     [v]
     (and (object? v) (fn? (unchecked-get v "then")))))

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

(defn wrap
  [v]
  (if (promise? v)
    v
    (pt/-promise v)))

(defn then
  "Chains a computation `f` (function) to be executed when the promise
  `p` is successfully resolved.

  The computation will be executed in the calling thread by default;
  you also can provide a custom executor.

  If the function `f` returns a promise instance, it will be
  automatically unwrapped."
  ([p f]
   (pt/-then p f))
  ([p f executor]
   (pt/-then p f executor)))

(defn bind
  "A convenient alias for `then`."
  ([p f]
   (pt/-then p f))
  ([p f executor]
   (pt/-then p f executor)))

(defn then'
  "Chains a computation `f` (function) to be executed when the promise
  `p` is successfully resolved.

  The computation will be executed in the calling thread by default;
  you also can provide a custom executor.

  Don't perform flatten on the result."
  ([p f] (pt/-map p f))
  ([p f executor] (pt/-map p f executor)))

(defn map
  "Chains a computation `f` (function) to be executed when the promise
  `p` is successfully resolved.

  Unlike `then` this does not performs automatic promise flattening.
  This is designed to be used with `->>`."
  ([f p] (pt/-map p f))
  ([executor f p] (pt/-map p f executor)))

(defn mapcat
  "Chains a computation `f` (function) to be executed when the promise
  `p` is successfully resolved. always expecting that `f` returns a
  promise that will be automatically unwrapped.

  This is just a stricter version of `then` with reversed arguments in
  the same way as `map`.

  This is designed to be used with `->>`."
  ([f p] (pt/-bind p f))
  ([executor f p] (pt/-bind p f executor)))

(defn chain
  "Chain variable number of computations to be executed
  serially. Analogous to `then` that accepts variable number of
  functions."
  ([p f] (then p f))
  ([p f & fs] (reduce #(then %1 %2) p (cons f fs))))

(defn chain'
  "Chain variable number of computations to be executed serially. Unlike
  `chain` does not flattens the return value of each step (probably
  this is more performant than `chain`)."
  ([p f] (then' p f))
  ([p f & fs] (reduce pt/-map p (cons f fs))))

(defn handle
  "Executes `f` when the promise `p` is resolved or is rejected. Returns
  a promise resolved with the return value of `f` function."
  ([p f] (pt/-handle p f))
  ([p f executor] (pt/-handle p f executor)))

(defn finally
  "Attach a potentially side-effectful handler to promise that will be
  executed independently if promise is resolved or rejected.

  Returns the original promise and the return value of `f` function is
  ignored."
  ([p f] (pt/-finally p f))
  ([p f executor] (pt/-finally p f executor)))

(defn catch
  "Executes `f` when the promise `p` is rejected. Returns a promise
  resolved with the return value of `f` function handler."
  ([p f]
   (pt/-thenErr p f))
  ([p pred-or-type f]
   (c/let [accept? (if (ifn? pred-or-type)
                     pred-or-type
                     #(instance? pred-or-type %))]
     (pt/-thenErr p (fn [e]
                      (if (accept? e)
                        (f e)
                        (impl/rejected e)))))))

(defn catch'
  "Executes `f` when the promise `p` is rejected. Returns a promise
  resolved with the return value of `f` function handler."
  ([p f]
   (pt/-mapErr p f))
  ([p pred-or-type f]
   (c/let [accept? (if (ifn? pred-or-type)
                     pred-or-type
                     #(instance? pred-or-type %))]
     (pt/-mapErr p (fn [e]
                     (if (accept? e)
                        (f e)
                        (throw e)))))))

(defn error
  "Same as `catch` but with parameters inverted."
  ([f p] (catch p f))
  ([f type p] (catch p type f)))

(def err
  "A short alias for `error` function."
  error)

(defn all
  "Given an array of promises, return a promise
  that is fulfilled  when all the items in the
  array are fulfilled.

  Example:

  (-> (all [(promise :first-promise)
            (promise :second-promise)]
      (then (fn [[first-result second-result]]))
       (println (str first-result \", \" second-result)

  Will print out
  :first-promise, :second-promise.

  If at least one of the promises is rejected, the resulting promise will be
  rejected."
  [promises]
  #?(:cljs (-> (.all impl/*default-promise* (into-array promises))
               (then' vec))
     :clj (c/let [promises (clojure.core/map pt/-promise promises)]
            (then' (->> (into-array CompletableFuture promises)
                        (CompletableFuture/allOf))
                   (fn [_]
                     (mapv pt/-extract promises))))))

(defn race
  [promises]
  #?(:cljs (.race impl/*default-promise* (into-array (cljs.core/map pt/-promise promises)))
     :clj (CompletableFuture/anyOf (->> (clojure.core/map pt/-promise promises)
                                        (into-array CompletableFuture)))))

(defn any
  "Given an array of promises, return a promise that is fulfilled when
  first one item in the array is fulfilled."
  ([promises]
   (any promises ::default))
  ([promises default]
   (c/let [state (atom {:resolved false
                      :counter (count promises)
                      :rejections []})]
     (create
      (fn [resolve reject]
        (doseq [p promises]
          (-> (promise p)
              (then (fn [v]
                      (when-not (:resolved @state)
                        (swap! state (fn [state]
                                       (-> state
                                           (assoc :resolved true)
                                           (update :counter dec))))
                        (resolve v))))
              (catch (fn [e]
                       (swap! state (fn [state]
                                      (-> state
                                          (update  :counter dec)
                                          (update :rejections conj e))))
                       (c/let [{:keys [resolved counter rejections]} @state]
                         (when (and (not resolved) (= counter 0))
                           (if (= default ::default)
                             (reject (ex-info "No promises resolved"
                                              {:rejections rejections}))
                             (resolve default)))))))))))))

(defn run!
  "A promise aware run! function."
  ([f coll] (run! f coll exec/current-thread-executor))
  ([f coll executor] (reduce #(then %1 (fn [_] (f %2))) (promise nil executor) coll)))

;; Cancellation

(defn cancel!
  "Cancel the promise."
  [p]
  (pt/-cancel! p)
  p)

(defn cancelled?
  "Return true if `v` is a cancelled promise."
  [v]
  (pt/-cancelled? v))

;; Completable

(defn resolve!
  "Resolve a completable promise with a value."
  ([o] (pt/-resolve! o nil))
  ([o v] (pt/-resolve! o v)))

(defn reject!
  "Reject a completable promise with an error."
  [p e]
  (pt/-reject! p e))

;; --- Utils

(defn promisify
  "Given a function that accepts a callback as the last argument return other
  function that returns a promise. Callback is expected to take single
  parameter (result of a computation)."
  [callable]
  (fn [& args]
    (create (fn [resolve reject]
               (c/let [args (-> (vec args) (conj resolve))]
                 (try
                   (apply callable args)
                   (catch #?(:clj Throwable :cljs js/Error) e
                     (reject e))))))))

#?(:cljs
   (defn ^{:jsdoc ["@constructor"]}
     TimeoutException [message]
     (this-as it
       (.call js/Error it message {} nil)
       it)))

#?(:cljs (goog/inherits TimeoutException js/Error))

(defn timeout
  "Returns a cancellable promise that will be fulfilled with this
  promise's fulfillment value or rejection reason.  However, if this
  promise is not fulfilled or rejected within `ms` milliseconds, the
  returned promise is cancelled with a TimeoutError"
  ([p t] (timeout p t ::default exec/default-scheduler))
  ([p t v] (timeout p t v exec/default-scheduler))
  ([p t v scheduler]
   (c/let [timeout (deferred)
           tid     (exec/schedule! scheduler t #(if (= v ::default)
                                                  (reject! timeout (TimeoutException. "Operation timed out."))
                                                  (resolve! timeout v)))]
     (race [(finally p (fn [_ _] (pt/-cancel! tid))) timeout]))))

(defn delay
  "Given a timeout in miliseconds and optional value, returns a promise
  that will fulfilled with provided value (or nil) after the time is
  reached."
  ([t] (delay t nil exec/default-scheduler))
  ([t v] (delay t v exec/default-scheduler))
  ([t v scheduler]
   (c/let [d (deferred)]
     (exec/schedule! scheduler t #(resolve! d v))
     d)))

(defmacro do!
  "Execute potentially side effectful code and return a promise resolved
  to the last expression. Always awaiting the result of each
  expression."
  [& exprs]
  `(pt/-bind nil (fn [_#]
                   ~(condp = (count exprs)
                      0 `(pt/-promise nil)
                      1 `(pt/-promise ~(first exprs))
                      (reduce (fn [acc e]
                                `(pt/-bind ~e (fn [_#] ~acc)))
                              `(pt/-promise ~(last exprs))
                              (reverse (butlast exprs)))))))

(defmacro let
  "A `let` alternative that always returns promise and waits for all the
  promises on the bindings."
  [bindings & body]
  `(pt/-bind nil (fn [_#]
                   ~(->> (reverse (partition 2 bindings))
                         (reduce (fn [acc [l r]]
                                   `(pt/-bind ~r (fn [~l] ~acc)))
                                 `(do! ~@body))))))

(defmacro plet
  "A parallel let; executes all the bindings in parallel and when all
  bindings are resolved, executes the body."
  [bindings & body]
  `(pt/-bind nil (fn [_#]
                   ~(c/let [bindings (partition 2 bindings)]
                      `(-> (all ~(mapv second bindings))
                           (then (fn [[~@(mapv first bindings)]]
                                   (do! ~@body))))))))

(defmacro future
  "Analogous to `clojure.core/future` that returns a promise instance
  instead of the `Future`. Usefull for execute synchronous code in a
  separate thread (also works in cljs)."
  [& body]
  `(-> (exec/submit! (fn []
                       (c/let [f# (fn [] ~@body)]
                         (pt/-promise (f#)))))
       (pt/-bind identity)))

(def ^:dynamic *loop-run-fn* exec/run!)

(defmacro loop
  [bindings & body]
  (c/let [bindings (partition 2 2 bindings)
          names (mapv first bindings)
          fvals (mapv second bindings)
          tsym (gensym "loop")
          dsym (gensym "deferred")
          rsym (gensym "run")]
    `(c/let [~rsym *loop-run-fn*
             ~dsym (promesa.core/deferred)
             ~tsym (fn ~tsym [params#]
                     (-> (promesa.core/all params#)
                         (promesa.core/then (fn [[~@names]]
                                              ;; (prn "exec" ~@names)
                                              (do! ~@body)))
                         (promesa.core/handle
                          (fn [res# err#]
                            ;; (prn "result" res# err#)
                            (cond
                              (not (nil? err#))
                              (promesa.core/reject! ~dsym err#)

                              (and (map? res#) (= (:type res#) :promesa.core/recur))
                              (do (~rsym (fn [] (~tsym (:args res#))))
                                  nil)

                              :else
                              (promesa.core/resolve! ~dsym res#))))))]
       (~rsym (fn [] (~tsym ~fvals)))
       ~dsym)))

(defmacro recur
  [& args]
  `(array-map :type :promesa.core/recur :args [~@args]))
