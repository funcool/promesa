;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns promesa.core
  (:refer-clojure :exclude [delay spread promise
                            await map mapcat run!
                            future let loop recur
                            -> ->> as-> with-redefs do
                            doseq])
  (:require
   [promesa.protocols :as pt]
   [clojure.core :as c]
   [promesa.exec :as exec]
   [promesa.impl :as impl]
   [promesa.util :as util])
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
  "The coerce based promise constructor. Creates an appropriate promise
  instance depending on the provided value.

  If an executor is provided, it will be used to resolve this
  promise."
  ([v]
   (pt/-promise v))
  ([v executor]
   (pt/-map (pt/-promise v) identity executor)))

(defn wrap
  "A convenience alias for `promise` coercion function that only accepts
  a single argument."
  [v]
  (pt/-promise v))

(defn create
  "Create a promise instance from a factory function. If an executor is
  provided, the factory will be executed in the provided executor.

  A factory function looks like `(fn [resolve reject] (resolve 1))`."
  ([f]
   (c/let [d (impl/deferred)]
     (try
       (f #(pt/-resolve! d %)
          #(pt/-reject! d %))
       (catch #?(:clj Throwable :cljs :default) e
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
  (satisfies? pt/IPromise v))

(defn deferred?
  "Return true if `v` is a promise instance (alias to `promise?`)."
  [v]
  #?(:clj (instance? CompletionStage v)
     :cljs (instance? impl/*default-promise* v)))

#?(:cljs
   (defn thenable?
     "Returns true if `v` is a promise like object."
     [v]
     (and (object? v) (fn? (unchecked-get v "then")))))

;; Predicates

#?(:clj
   (defn resolved?
     "Returns true if promise `p` is already fulfilled."
     [p]
     (pt/-resolved? p)))

#?(:clj
   (defn rejected?
     "Returns true if promise `p` is already rejected."
     [p]
     (pt/-rejected? p)))

#?(:clj
   (defn pending?
     "Returns true if promise `p` is stil pending."
     [p]
     (pt/-pending? p)))

#?(:clj
   (defn extract
     "Returns the current promise value."
     [p]
     (pt/-extract p)))

#?(:clj
   (def done?
     "Returns true if promise `p` is already done."
     (complement pending?)))

;; Chaining

(defn then
  "Chains a function `f` to be executed when the promise `p` is
  successfully resolved. Returns a promise that will be resolved with
  the return value of calling `f` with value as single argument; `f`
  can return a plain value or promise instance, an automatic
  unwrapping will be performed.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor."
  ([p f]
   (pt/-bind (pt/-promise p) (comp pt/-promise f)))
  ([p f executor]
   (pt/-bind (pt/-promise p) (comp pt/-promise f) executor)))

(defn then'
  {:deprecated "9.3"
   :no-doc true}
  ([p f]
   (pt/-map (pt/-promise p) f))
  ([p f executor]
   (pt/-map (pt/-promise p) f executor)))

(defn bind
  "Chains a function `f` to be executed with when the promise `p` is
  successfully resolved. Returns a promise that will mirror the
  promise instance returned by calling `f` with the value as single
  argument; `f` **must** return a promise instance.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor."
  ([p f]
   (pt/-bind (pt/-promise p) f))
  ([p f executor]
   (pt/-bind (pt/-promise p) f executor)))

(defn map
  "Chains a function `f` to be executed when the promise `p` is
  successfully resolved. Returns a promise that will be resolved with
  the return value of calling `f` with value as single argument.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  This function is intended to be used with `->>`."
  ([f p]
   (pt/-map (pt/-promise p) f))
  ([executor f p]
   (pt/-map (pt/-promise p) f executor)))

(defn fmap
  "A convenience alias for `map`."
  ([f p]
   (pt/-map (pt/-promise p) f))
  ([executor f p]
   (pt/-map (pt/-promise p) f executor)))

(defn mapcat
  "Chains a function `f` to be executed when the promise `p` is
  successfully resolved. Returns a promise that will mirror the
  promise instance returned by calling `f` with the value as single
  argument; `f` **must** return a promise instance.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  This funciton is intended to be used with `->>`."
  ([f p]
   (pt/-bind (pt/-promise p) f))
  ([executor f p]
   (pt/-bind (pt/-promise p) f executor)))

(defn mcat
  "A convenience alias for `mapcat`."
  ([f p]
   (pt/-bind (pt/-promise p) f))
  ([executor f p]
   (pt/-bind (pt/-promise p) f executor)))

(defn chain
  "Chain variable number of functions to be executed serially using
  `then`."
  ([p f] (then p f))
  ([p f & fs] (reduce #(then %1 %2) p (cons f fs))))

(defn chain'
  {:deprecated "9.3" :no-doc true}
  ([p f] (then' p f))
  ([p f & fs] (reduce pt/-map (pt/-promise p) (cons f fs))))

(defn handle
  "Chains a function `f` to be executed when the promise `p` is completed
  (resolved or rejected) and returns a promise completed (resolving or
  rejecting) with the return value of calling `f` with both: value and
  the exception; `f` can return a new plain value or promise instance,
  and automatic unwrapping will be performed.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  For performance sensitive code, look at `hmap` and `hcat`."
  ([p f]
   #?(:cljs (pt/-handle (pt/-promise p) f)
      :clj  (c/-> (pt/-handle (pt/-promise p) (comp pt/-promise f))
                  (impl/unwrap-completion-stage))))
  ([p f executor]
   #?(:cljs (pt/-handle (pt/-promise p) f executor)
      :clj  (c/-> (pt/-handle (pt/-promise p) (comp pt/-promise f) executor)
                  (impl/unwrap-completion-stage)))))

(defn finally
  "Like `handle` but ignores the return value. Returns a promise that
  will mirror the original one."
  ([p f]
   (pt/-finally (pt/-promise p) f))
  ([p f executor]
   (pt/-finally (pt/-promise p) f executor)))

(defn hmap
  "Chains a function `f` to be executed when the promise `p` is completed
  (resolved or rejected) and returns a promise completed (resolving or
  rejecting) with the return value of calling `f` with both: value and
  the exception.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  Intended to be used with `->>`."
  ([f p]
   (pt/-handle (pt/-promise p) f))
  ([executor f p]
   (pt/-handle (pt/-promise p) f executor)))

(defn hcat
  "Chains a function `f` to be executed when the promise `p` is completed
  (resolved or rejected) and returns a promise that will mirror the
  promise instance returned by calling `f` with both: value and the
  exception.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  Intended to be used with `->>`."
  ([f p]
   #?(:cljs (pt/-handle (pt/-promise p) f)
      :clj  (c/-> (pt/-handle (pt/-promise p) f)
                  (impl/unwrap-completion-stage))))
  ([executor f p]
   #?(:cljs (pt/-handle (pt/-promise p) f executor)
      :clj  (c/-> (pt/-handle (pt/-promise p) f executor)
                  (impl/unwrap-completion-stage)))))

(defn fnly
  "Inverted arguments version of `finally`; intended to be used with
  `->>`."
  ([f p]
   (pt/-finally (pt/-promise p) f))
  ([executor f p]
   (pt/-finally (pt/-promise p) f executor)))

(defn catch
  "Chains a function `f` to be executed when the promise `p` is
  rejected. Returns a promise that will be resolved with the return
  value of calling `f` with exception as single argument; `f` can
  return a plain value or promise instance, an automatic unwrapping
  will be performed.

  The computation will be executed in the completion thread, look at
  `merr` if you want the ability to schedule the computation to other
  thread."
  ([p f]
   (pt/-catch (pt/-promise p) (comp pt/-promise f)))
  ([p pred-or-type f]
   (c/let [accept? (if (ifn? pred-or-type)
                     pred-or-type
                     #(instance? pred-or-type %))]
     (pt/-catch
      (pt/-promise p)
      (fn [e]
        (if (accept? e)
          (pt/-promise (f e))
          (impl/rejected e)))))))

(defn catch'
  {:deprecated "9.3" :no-doc true}
  ([p f] (catch p f))
  ([p pred-or-type f] (catch p pred-or-type f)))

(defn error
  {:deprecated "9.3" :no-doc true}
  ([f p] (catch p f))
  ([f pred-or-type p] (catch p type f)))

(defn merr
  "Chains a function `f` to be executed when the promise `p` is
  rejected. Returns a promise that will mirror the promise returned by
  calling `f` with exception as single argument; `f` **must** return a
  promise instance.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  This is intended to be used with `->>`."
  ([f p] (pt/-catch p f))
  ([executor f p] (pt/-catch p f executor)))

(defn all
  "Given an array of promises, return a promise that is fulfilled when
  all the items in the array are fulfilled.

  Example:

  ```
  (-> (p/all [(promise :first-promise)
              (promise :second-promise)]
      (then (fn [[first-result second-result]]))
       (println (str first-result \", \" second-result)
  ```

  Will print to out `:first-promise, :second-promise`.

  If at least one of the promises is rejected, the resulting promise
  will be rejected."
  [promises]
  #?(:cljs (c/-> (.all impl/*default-promise* (into-array promises))
                 (then vec))
     :clj (c/let [promises (clojure.core/map pt/-promise promises)]
            (c/->> (CompletableFuture/allOf (into-array CompletableFuture promises))
                   (map (fn [_]
                          (c/mapv pt/-extract promises)))))))
(defn race
  [promises]
  #?(:cljs (.race impl/*default-promise* (into-array (c/map pt/-promise promises)))
     :clj (CompletableFuture/anyOf (into-array CompletableFuture (c/map pt/-promise promises)))))

(defn any
  "Given an array of promises, return a promise that is fulfilled when
  first one item in the array is fulfilled."
  ([promises]
   (any promises ::default))
  ([promises default]
   (c/let [items (into #{} promises)
           state (volatile! {:pending items
                             :rejections []
                             :resolved? false})
           lock  (util/mutex)]
     (create
      (fn [resolve reject]
        (c/doseq [p promises]
          (pt/-handle
           (pt/-promise p)
           (fn [v exception]
             (pt/-lock! lock)
             (try
               (if exception
                 (when-not (:resolved? @state)
                   (c/let [state (vswap! state (fn [state]
                                                 (c/-> state
                                                       (update :pending disj p)
                                                       (update :rejections conj exception))))]
                     (when-not (seq (:pending state))
                       (if (= default ::default)
                         (reject (ex-info "No promises resolved"
                                          {:rejections (:rejections state)}))
                         (resolve default)))))

                 (when-not (:resolved? @state)
                   (c/let [{:keys [pending]} (vswap! state (fn [state]
                                                             (c/-> state
                                                                   (assoc :resolved? true)
                                                                   (update :pending disj p))))]
                     #?(:clj (c/run! pt/-cancel! pending))
                     (resolve v))))
               (finally
                 (pt/-unlock! lock)))))))))))


(defn run!
  "A promise aware run! function. Executed in terms of `then` rules."
  ([f coll] (run! f coll exec/same-thread-executor))
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
  "Given a function that accepts a callback as the last argument, return a
  function that returns a promise. Callback is expected to take one
  parameter (result of a computation)."
  [callable]
  (fn [& args]
    (create (fn [resolve reject]
               (c/let [args (c/-> (vec args) (conj resolve))]
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

#?(:cljs
   (goog/inherits TimeoutException js/Error))

(defn timeout
  "Returns a cancellable promise that will be fulfilled with this
  promise's fulfillment value or rejection reason.  However, if this
  promise is not fulfilled or rejected within `ms` milliseconds, the
  returned promise is cancelled with a TimeoutError."
  ([p t] (timeout p t ::default :default))
  ([p t v] (timeout p t v :default))
  ([p t v scheduler]
   (c/let [timeout (deferred)
           tid     (exec/schedule! scheduler t #(if (= v ::default)
                                                  (reject! timeout (TimeoutException. "Operation timed out."))
                                                  (resolve! timeout v)))]
     (race [(fnly (fn [_ _] (pt/-cancel! tid)) p) timeout]))))

(defn delay
  "Given a timeout in miliseconds and optional value, returns a promise
  that will be fulfilled with provided value (or nil) after the time is
  reached."
  ([t] (delay t nil :default))
  ([t v] (delay t v :default))
  ([t v scheduler]
   (c/let [d (deferred)]
     (exec/schedule! scheduler t #(resolve! d v))
     d)))

(defmacro do!
  "Execute potentially side effectful code and return a promise resolved
  to the last expression after awaiting the result of each
  expression."
  [& exprs]
  `(pt/-bind
    (pt/-promise nil)
    (fn [_#]
      ~(condp = (count exprs)
         0 `(pt/-promise nil)
         1 `(pt/-promise ~(first exprs))
         (reduce (fn [acc e]
                   `(pt/-bind (pt/-promise ~e) (fn [_#] ~acc)))
                 `(pt/-promise ~(last exprs))
                 (reverse (butlast exprs)))))))


(defmacro do
  "An alias for do!"
  [& exprs]
  `(do! ~@exprs))

(defmacro let
  "A `let` alternative that always returns promise and waits for all the
  promises on the bindings."
  [bindings & body]
  `(pt/-bind
    (pt/-promise nil)
    (fn [_#]
      ~(c/->> (reverse (partition 2 bindings))
              (reduce (fn [acc [l r]]
                        `(pt/-bind (pt/-promise ~r) (fn [~l] ~acc)))
                      `(do! ~@body))))))

(defmacro plet
  "A parallel let; executes all the bindings in parallel and when all
  bindings are resolved, executes the body."
  [bindings & body]
  `(pt/-bind
    (pt/-promise nil)
    (fn [_#]
      ~(c/let [bindings (partition 2 bindings)]
         `(c/-> (all ~(mapv second bindings))
                (then (fn [[~@(mapv first bindings)]]
                        (do! ~@body))))))))

(defn thread-call
  "Analogous to `clojure.core.async/thread` that returns a promise
  instance instead of the `Future`. Useful for executing synchronous
  code in a separate thread (also works in cljs)."
  ([f] (exec/submit! :thread (exec/wrap-bindings f)))
  ([executor f] (exec/submit! executor (exec/wrap-bindings f))))

(defn vthread-call
  "A shortcut for `(p/thread-call :vthread f)`."
  [f]
  (thread-call :vthread f))

(defmacro thread
  "Analogous to `clojure.core.async/thread` that returns a promise instance
  instead of the `Future`."
  [& body]
  `(thread-call (^once fn [] ~@body)))

(defmacro vthread
  "Analogous to `clojure.core.async/thread` that returns a promise instance
  instead of the `Future`. Useful for executing synchronous code in a
  separate thread (also works in cljs)."
  [& body]
  `(vthread-call (^once fn [] ~@body)))

(defmacro future
  "Analogous macro to `clojure.core/future` that returns promise
  instance instead of the `Future`. Exposed just for convenience and
  works as an alias to `thread`."
  [& body]
  `(thread-call :default (^once fn [] ~@body)))

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
                     (c/-> (promesa.core/all params#)
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

(defmacro ->
  "Like the clojure.core/->, but it will handle promises in values
  and make sure the next form gets the value realized instead of
  the promise.

  Example fetching data in the browser with CLJS:

  (p/-> (js/fetch #js {...}) ; returns a promise
        .-body)

  The result of a thread is a promise that will resolve to the
  end of the thread chain."
  [x & forms]
  (c/let [fns (mapv (fn [arg]
                      (c/let [[f & args] (if (sequential? arg)
                                           arg
                                           (list arg))]
                        `(fn [p#] (~f p# ~@args)))) forms)]
    `(chain (promise ~x) ~@fns)))

(defmacro ->>
  "Like the clojure.core/->>, but it will handle promises in values
  and make sure the next form gets the value realized instead of
  the promise.

  Example fetching data in the browser with CLJS:

  (p/->> (js/fetch #js {...}) ; returns a promise
         .-body
         read-string
         (mapv inc)

  The result of a thread is a promise that will resolve to the
  end of the thread chain."
  [x & forms]
  (c/let [fns (mapv (fn [arg]
                      (c/let [[f & args] (if (sequential? arg)
                                           arg
                                           (list arg))]
                        `(fn [p#] (~f ~@args p#)))) forms)]
    `(chain (promise ~x) ~@fns)))

(defmacro as->
  "Like clojure.core/as->, but it will handle promises in values
   and make sure the next form gets the value realized instead of
   the promise."
  [expr name & forms]
  `(let [~name ~expr
         ~@(interleave (repeat name) (butlast forms))]
     ~(if (empty? forms)
        name
        (last forms))))

(defmacro with-redefs
  "Like clojure.core/with-redefs, but it will handle promises in
   body and wait until they resolve or reject before restoring the
   bindings. Useful for mocking async APIs."
  [bindings & body]
  (c/let [names         (take-nth 2 bindings)
          vals          (take-nth 2 (drop 1 bindings))
          orig-val-syms (c/map (comp gensym #(str % "-orig-val__") name) names)
          temp-val-syms (c/map (comp gensym #(str % "-temp-val__") name) names)
          binds         (c/map vector names temp-val-syms)
          resets        (reverse (c/map vector names orig-val-syms))
          bind-value    (if (:ns &env)
                          (fn [[k v]] (list 'set! k v))
                          (fn [[k v]] (list 'alter-var-root (list 'var k) (list 'constantly v))))]
    `(c/let [~@(interleave orig-val-syms names)
             ~@(interleave temp-val-syms vals)]
       ~@(c/map bind-value binds)
       (c/-> (promesa.core/do! ~@body)
             (promesa.core/finally
               (fn [_# _#]
                 ~@(c/map bind-value resets)))))))

(defmacro doseq
  "Simplified version of `doseq` which takes one binding and a seq, and
  runs over it using `promesa.core/run!`"
  [[binding xs] & body]
  `(run! (fn [~binding]
           (promesa.core/do ~@body))
         ~xs))
