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
      java.util.concurrent.CompletionException
      java.util.concurrent.CompletionStage
      java.util.concurrent.ExecutionException
      java.util.concurrent.TimeoutException)))

#?(:clj (set! *warn-on-reflection* true))

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
   (pt/-fmap (pt/-promise v) identity executor)))

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
  (impl/promise? v))

(defn deferred?
  "Return true if `v` is a deferred instance."
  [v]
  (impl/deferred? v))

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
  ([p]
   (pt/-extract p))
  ([p default]
   (pt/-extract p default)))

(defn done?
  "Returns true if promise `p` is already done."
  [p]
  (not (pt/-pending? p)))

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
   (pt/-then (pt/-promise p) f))
  ([p f executor]
   (pt/-then (pt/-promise p) f executor)))

(defn then'
  "Chains a function `f` to be executed when the promise `p` is
  successfully resolved. Returns a promise that will be resolved with
  the return value of calling `f` with value as single argument; `f`
  should return a plain value, no automatic unwrapping will be
  performed.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor."
  ([p f]
   (pt/-fmap (pt/-promise p) f))
  ([p f executor]
   (pt/-fmap (pt/-promise p) f executor)))

(defn bind
  "Chains a function `f` to be executed with when the promise `p` is
  successfully resolved. Returns a promise that will mirror the
  promise instance returned by calling `f` with the value as single
  argument; `f` **must** return a promise instance.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor."
  ([p f]
   (pt/-mcat (pt/-promise p) f))
  ([p f executor]
   (pt/-mcat (pt/-promise p) f executor)))

(defn map
  "Chains a function `f` to be executed when the promise `p` is
  successfully resolved. Returns a promise that will be resolved with
  the return value of calling `f` with value as single argument.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  This function is intended to be used with `->>`."
  ([f p]
   (pt/-fmap (pt/-promise p) f))
  ([executor f p]
   (pt/-fmap (pt/-promise p) f executor)))

(defn fmap
  "A convenience alias for `map`."
  ([f p]
   (pt/-fmap (pt/-promise p) f))
  ([executor f p]
   (pt/-fmap (pt/-promise p) f executor)))

(defn mapcat
  "Chains a function `f` to be executed when the promise `p` is
  successfully resolved. Returns a promise that will mirror the
  promise instance returned by calling `f` with the value as single
  argument; `f` **must** return a promise instance.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  This funciton is intended to be used with `->>`."
  ([f p]
   (pt/-mcat (pt/-promise p) f))
  ([executor f p]
   (pt/-mcat (pt/-promise p) f executor)))

(defn mcat
  "A convenience alias for `mapcat`."
  ([f p]
   (pt/-mcat (pt/-promise p) f))
  ([executor f p]
   (pt/-mcat (pt/-promise p) f executor)))

(defn chain
  "Chain variable number of functions to be executed serially using
  `then`."
  ([p f] (then p f))
  ([p f & fs] (reduce then p (cons f fs))))

(defn chain'
  "Chain variable number of functions to be executed serially using
  `map`."
  ([p f] (then' p f))
  ([p f & fs] (reduce #(map %2 %1) (pt/-promise p) (cons f fs))))

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
   #?(:cljs (c/-> (pt/-promise p)
                  (pt/-hmap (comp pt/-promise f))
                  (pt/-mcat identity))
      :clj  (c/-> (pt/-promise p)
                  (pt/-hmap (comp pt/-promise f))
                  (util/unwrap-completion-stage))))
  ([p f executor]
   #?(:cljs (c/-> (pt/-promise p)
                  (pt/-hmap (comp pt/-promise f) executor)
                  (pt/-mcat identity executor))
      :clj  (c/-> (pt/-promise p)
                  (pt/-hmap (comp pt/-promise f) executor)
                  (util/unwrap-completion-stage)))))

(defn finally
  "Like `handle` but ignores the return value. Returns a promise that
  will mirror the original one."
  ([p f]
   (c/-> (pt/-promise p)
         (pt/-fnly f)))
  ([p f executor]
   (c/-> (pt/-promise p)
         (pt/-fnly f executor))))

(defn hmap
  "Chains a function `f` to be executed when the promise `p` is completed
  (resolved or rejected) and returns a promise completed (resolving or
  rejecting) with the return value of calling `f` with both: value and
  the exception.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  Intended to be used with `->>`."
  ([f p]
   (pt/-hmap (pt/-promise p) f))
  ([executor f p]
   (pt/-hmap (pt/-promise p) f executor)))

(defn hcat
  "Chains a function `f` to be executed when the promise `p` is completed
  (resolved or rejected) and returns a promise that will mirror the
  promise instance returned by calling `f` with both: value and the
  exception. The `f` function must return a promise instance.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  Intended to be used with `->>`."
  ([f p]
   #?(:cljs (c/-> (pt/-promise p)
                  (pt/-hmap f)
                  (pt/-mcat identity))
      :clj  (c/-> (pt/-promise p)
                  (pt/-hmap f)
                  (util/unwrap-completion-stage))))
  ([executor f p]
   #?(:cljs (c/-> (pt/-promise p)
                  (pt/-hmap f executor)
                  (pt/-mcat identity executor))
      :clj  (c/-> (pt/-promise p)
                  (pt/-hmap f executor)
                  (util/unwrap-completion-stage)))))

(defn fnly
  "Inverted arguments version of `finally`; intended to be used with
  `->>`."
  ([f p]
   (pt/-fnly (pt/-promise p) f))
  ([executor f p]
   (pt/-fnly (pt/-promise p) f executor)))

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
   (pt/-merr (pt/-promise p) #(pt/-promise (f %))))
  ([p pred-or-type f]
   (c/let [accept? (if (ifn? pred-or-type)
                     pred-or-type
                     #(instance? pred-or-type %))]
     (pt/-merr
      (pt/-promise p)
      (fn [e]
        (if (accept? e)
          (pt/-promise (f e))
          (impl/rejected e)))))))

(defn merr
  "Chains a function `f` to be executed when the promise `p` is
  rejected. Returns a promise that will mirror the promise returned by
  calling `f` with exception as single argument; `f` **must** return a
  promise instance or throw an exception.

  The computation will be executed in the completion thread by
  default; you also can provide a custom executor.

  This is intended to be used with `->>`."
  ([f p] (pt/-merr p f))
  ([executor f p] (pt/-merr p f executor)))

(defn error
  "Same as `catch` but with parameters inverted.

  DEPRECATED"

  ([f p] (catch p f))
  ([f type p] (catch p type f)))

(defn all
  "Given an array of promises, return a promise that is fulfilled when
  all the items in the array are fulfilled.

  Example:

  ```
  (-> (p/all [(promise :first-promise)
              (promise :second-promise)])
      (then (fn [[first-result second-result]])
              (println (str first-result \", \" second-result))))
  ```

  Will print to out `:first-promise, :second-promise`.

  If at least one of the promises is rejected, the resulting promise
  will be rejected."
  [promises]
  (impl/all promises))

(defn race
  [promises]
  (impl/race promises))

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
          (pt/-fnly
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

(defn wait-all*
  "Given an array of promises, return a promise that is fulfilled when
  all the items in the array are resolved (independently if
  successfully or exceptionally).

  Example:

  ```
  (->> (p/wait-all* [(promise :first-promise)
                     (promise :second-promise)])
       (p/fmap (fn [_]
                 (println \"done\"))))
  ```

  Rejected promises also counts as resolved."
  [promises]
  (c/let [promises (set promises)
          total    (count promises)
          prom     (deferred)]
    (if (pos? total)
      (c/let [counter (atom total)]
        (c/run! #(pt/-fnly % (fn [_ _]
                               (when (= 0 (swap! counter dec))
                                 (pt/-resolve! prom nil))))
                promises))
      (pt/-resolve! prom nil))
    prom))

(defn wait-all
  "Given a variable number of promises, returns a promise which resolves
  to `nil` when all provided promises complete (rejected or resolved).

  **EXPERIMENTAL**"
  [& promises]
  (wait-all* (into #{} promises)))

#?(:clj
   (defn wait-all!
     "A blocking version of `wait-all`."
     [promises]
     (pt/-await! (wait-all promises))))

(defn run!
  "A promise aware run! function. Executed in terms of `then` rules."
  ([f coll]
   (c/-> (c/reduce #(then %1 (fn [_] (f %2))) (impl/resolved nil) coll)
         (pt/-fmap (constantly nil))))
  ([f coll executor]
   (c/-> (c/reduce #(then %1 (fn [_] (f %2)) executor) (impl/resolved nil) coll)
         (pt/-fmap (constantly nil)))))

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

(defmacro do*
  "An exception unsafe do-like macro. Supposes that we are already
  wrapped in promise context so avoids defensive wrapping."
  [& exprs]
  (condp = (count exprs)
    0 `(impl/resolved nil)
    1 `(pt/-promise ~(first exprs))
    (reduce (fn [acc e]
              `(pt/-mcat (pt/-promise ~e) (fn [_#] ~acc)))
            `(pt/-promise ~(last exprs))
            (reverse (butlast exprs)))))

(defmacro do
  "Execute potentially side effectful code and return a promise resolved
  to the last expression after awaiting the result of each
  expression."
  [& exprs]
  `(pt/-mcat
    (pt/-promise nil)
    (fn [_#]
      (promesa.core/do* ~@exprs))))

(defmacro do!
  "A convenience alias for `do` macro."
  [& exprs]
  `(promesa.core/do ~@exprs))

(defmacro let*
  "An exception unsafe let-like macro. Supposes that we are already
  wrapped in promise context so avoids defensive wrapping."
  [bindings & body]
  (assert (even? (count bindings)) (str "Uneven binding vector: " bindings))
  (c/->> (reverse (partition 2 bindings))
         (reduce (fn [acc [l r]]
                   `(pt/-mcat (pt/-promise ~r) (fn [~l] ~acc)))
                 `(do* ~@body))))

(defmacro let
  "A `let` alternative that always returns promise and waits for all the
  promises on the bindings."
  [bindings & body]
  (if (seq bindings)
    `(pt/-mcat
      (pt/-promise nil)
      (fn [_#] (promesa.core/let* ~bindings ~@body)))
    `(promesa.core/do ~@body)))

(defmacro plet
  "A parallel let; executes all the bindings in parallel and when all
  bindings are resolved, executes the body."
  [bindings & body]
  (assert (even? (count bindings)) (str "Uneven binding vector: " bindings))
  `(pt/-mcat
    (pt/-promise nil)
    (fn [_#]
      ~(c/let [bindings (partition 2 bindings)]
         `(c/-> (all ~(mapv second bindings))
                (bind (fn [[~@(c/map first bindings)]]
                        (promesa.core/do* ~@body))))))))

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

(defrecord Recur [bindings])
(defn recur?
  [o]
  (instance? Recur o))

(defmacro loop
  [bindings & body]
  (c/let [binds (partition 2 2 bindings)
          names (c/map first binds)
          fvals (c/map second binds)
          tsym  (gensym "loop-fn-")
          res-s (gensym "res-")
          err-s (gensym "err-")
          rej-s (gensym "reject-fn-")
          rsv-s (gensym "resolve-fn-")]
    `(create
      (fn [~rsv-s ~rej-s]
        (c/let [~tsym (fn ~tsym [~@names]
                        (c/->> (promesa.core/let [~@(c/mapcat (fn [nsym] [nsym nsym]) names)] ~@body)
                               (promesa.core/fnly
                                (fn [~res-s ~err-s]
                                  ;; (prn "result" res# err#)
                                  (if (some? ~err-s)
                                    (~rej-s ~err-s)
                                    (if (recur? ~res-s)
                                      (do
                                        (promesa.exec/run!
                                         :vthread
                                         (promesa.exec/wrap-bindings
                                          ~(if (seq names)
                                             `(fn [] (apply ~tsym (:bindings ~res-s)))
                                           tsym)))
                                      nil)
                                      (~rsv-s ~res-s)))))))]
          (promesa.exec/run!
           :vthread
           (promesa.exec/wrap-bindings
            ~(if (seq names)
               `(fn [] (~tsym ~@fvals))
               tsym))))))))

(defmacro recur
  [& args]
  `(->Recur [~@args]))

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
           (promesa.core/do* ~@body))
         ~xs))

#?(:clj
(defn await!
  "Generic await operation. Block current thread until some operation
  terminates. Returns `nil` on timeout; does not catch any other
  exception.

  Default implementation for Thread, CompletableFuture and
  CountDownLatch.

  The return value is implementation specific."
  ([resource]
   (try
     (pt/-await! resource)
     (catch ExecutionException e
       (throw (.getCause e)))
     (catch CompletionException e
       (throw (.getCause e)))))
  ([resource duration]
   (try
     (pt/-await! resource duration)
     (catch ExecutionException e
       (throw (.getCause e)))
     (catch CompletionException e
       (throw (.getCause e)))
     (catch TimeoutException _
       nil)))))

#?(:clj
(defn await
  "A exception safer variant of `await!`. Returns `nil` on timeout
  exception, forwards interrupted exception and all other exceptions
  are returned as value, so user is responsible for checking if the returned
  value is exception or not."
  ([resource]
   (try
     (pt/-await! resource)
     (catch ExecutionException e
       (.getCause e))
     (catch CompletionException e
       (.getCause e))
     (catch InterruptedException cause
       (throw cause))
     (catch Throwable cause
       cause)))
  ([resource duration]
   (try
     (pt/-await! resource duration)
     (catch TimeoutException _
       nil)
     (catch ExecutionException e
       (.getCause e))
     (catch CompletionException e
       (.getCause e))
     (catch InterruptedException cause
       (throw cause))
     (catch Throwable cause
       cause)))))
