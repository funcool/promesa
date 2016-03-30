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
  #?(:cljs(:require [org.bluebird]))
  #?(:clj
     (:import java.util.concurrent.CompletableFuture
              java.util.concurrent.CompletionStage
              java.util.concurrent.TimeoutException
              java.util.concurrent.ExecutionException
              java.util.concurrent.CompletionException
              java.util.concurrent.TimeUnit
              java.util.concurrent.Future
              java.util.concurrent.Executor
              java.util.concurrent.Executors
              java.util.concurrent.ForkJoinPool
              java.util.concurrent.ScheduledExecutorService
              java.util.function.Function
              java.util.function.Supplier)))

#?(:cljs
   (def ^:const Promise (js/Promise.noConflict)))

#?(:cljs
   (.config Promise #js {:cancellation true
                         :warnings false}))
#?(:clj
   (def ^:no-doc ^:redef +executor+
     (ForkJoinPool/commonPool)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scheduler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ICancellable
  "A cancellation abstraction."
  (-cancel [_])
  (-cancelled? [_]))

(defprotocol IScheduler
  (-schedule [_ ms func]))

#?(:cljs
   (defn- scheduled-task
     [cur done?]
     (let [cancelled (volatile! false)]
       (reify
         cljs.core/IPending
         (-realized? [_] @done?)

         ICancellable
         (-cancelled? [_] @cancelled)
         (-cancel [_]
           (when-not @cancelled
             (vreset! cancelled true)
             (js/clearTimeout cur))))))

   :clj
   (defn- scheduled-task
     [^Future fut]
     (reify
       clojure.lang.IDeref
       (deref [_]
         (.get fut))

       clojure.lang.IBlockingDeref
       (deref [_ ms default]
         (try
           (.get fut ms TimeUnit/MILLISECONDS)
           (catch TimeoutException e
             default)))

       clojure.lang.IPending
       (isRealized [_] (and (.isDone fut)
                            (not (.isCancelled fut))))

       ICancellable
       (-cancelled? [_]
         (.isCancelled fut))
       (-cancel [_]
         (when-not (.isCancelled fut)
           (.cancel fut true))))))

#?(:clj
   (extend-type ScheduledExecutorService
     IScheduler
     (-schedule [this ms func]
       (let [fut (.schedule this func ms TimeUnit/MILLISECONDS)]
         (scheduled-task fut)))))

#?(:cljs
   (defn- scheduler
     []
     (reify IScheduler
       (-schedule [_ ms func]
         (let [done? (volatile! false)
               task (fn []
                      (try
                        (func)
                        (finally
                          (vreset! done? true))))
               cur (js/setTimeout task ms)]
           (scheduled-task cur done?)))))
   :clj
   (defn- scheduler
     []
     (Executors/newScheduledThreadPool 1)))

(def ^:no-doc ^:redef +scheduler+
  "A default scheduler instance."
  (scheduler))

(defn schedule
  "Schedule a callable to be executed after the `ms` delay
  is reached.

  In JVM it uses a scheduled executor service and in JS
  it uses the `setTimeout` function."
  [ms func]
  (-schedule +scheduler+ ms func))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Promise
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IPromise
  "A basic future abstraction."
  (-map [_ callback] "Chain a promise.")
  (-bind [_ callback] "Chain a promise.")
  (-catch [_ callback] "Catch a error in a promise."))

(defprotocol IState
  "Additional state/introspection abstraction."
  (-extract [_] "Extract the current value.")
  (-resolved? [_] "Returns true if a promise is resolved.")
  (-rejected? [_] "Returns true if a promise is rejected.")
  (-pending? [_] "Retutns true if a promise is pending."))

(defprotocol IPromiseFactory
  "A promise constructor abstraction."
  (-promise [_] "Create a promise instance."))

#?(:cljs
   (extend-type Promise
     ICancellable
     (-cancel [it]
       (.cancel it))
     (-cancelled? [it]
       (.isCancelled it))

     IPromise
     (-map [it cb]
       (.then it #(cb %)))
     (-bind [it cb]
       (.then it #(cb %)))
     (-catch [it cb]
       (.catch it #(cb %)))

     IState
     (-extract [it]
       (if (.isRejected it)
         (.reason it)
         (.value it)))
     (-resolved? [it]
       (.isFulfilled it))
     (-rejected? [it]
       (.isRejected it))
     (-pending? [it]
       (.isPending it))))


#?(:clj
   (extend-type CompletionStage
     ICancellable
     (-cancel [it]
       (.cancel it true))
     (-cancelled? [it]
       (.isCancelled it))

     IPromise
     (-map [it cb]
       (.thenApplyAsync it (reify Function
                             (apply [_ v]
                               (cb v)))
                        +executor+))

     (-bind [it cb]
       (.thenComposeAsync it (reify Function
                               (apply [_ v]
                                 (cb v)))
                          +executor+))

     (-catch [it cb]
       (.exceptionally it (reify Function
                            (apply [_ e]
                              (if (instance? CompletionException e)
                                (cb (.getCause e))
                                (cb e))))))

     IState
     (-extract [it]
       (try
         (.getNow it nil)
         (catch ExecutionException e
           (.getCause e))
         (catch CompletionException e
           (.getCause e))))

     (-resolved? [it]
       (and (not (.isCompletedExceptionally it))
            (not (.isCancelled it))
            (.isDone it)))

     (-rejected? [it]
       (.isCompletedExceptionally it))

     (-pending? [it]
       (and (not (.isCompletedExceptionally it))
            (not (.isCancelled it))
            (not (.isDone it))))))

#?(:clj
   (extend-protocol IPromiseFactory
     clojure.lang.Fn
     (-promise [func]
       (let [p (CompletableFuture.)
             reject #(.completeExceptionally p %)
             resolve #(.complete p %)]
         (try
           (func resolve reject)
           (catch Throwable e
             (reject e)))
         p))

     Throwable
     (-promise [e]
       (let [p (CompletableFuture.)]
         (.completeExceptionally p e)
         p))

     CompletionStage
     (-promise [cs] cs)

     Object
     (-promise [v]
       (let [p (CompletableFuture.)]
         (.complete p v)
         p))

     nil
     (-promise [v]
       (doto (CompletableFuture.)
         (.complete v)))))

#?(:cljs
   (extend-protocol IPromiseFactory
     function
     (-promise [func]
       (Promise. func))

     Promise
     (-promise [p] p)

     js/Error
     (-promise [e]
       (.reject Promise e))

     object
     (-promise [v]
       (.resolve Promise v))

     number
     (-promise [v]
       (.resolve Promise v))

     boolean
     (-promise [v]
       (.resolve Promise v))

     string
     (-promise [v]
       (.resolve Promise v))

     nil
     (-promise [v]
       (.resolve Promise v))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Constructors

(defn resolved
  "Return a resolved promise with provided value."
  [v]
  #?(:cljs (.resolve Promise v)
     :clj (let [p (CompletableFuture.)]
            (.complete p v)
            p)))

(defn rejected
  "Return a rejected promise with provided reason."
  [v]
  #?(:cljs (.reject Promise v)
     :clj (let [p (CompletableFuture.)]
            (.completeExceptionally p v)
            p)))

(defn promise
  "The promise constructor."
  [v]
  (-promise v))

(defn promise?
  "Return true if `v` is a promise instance."
  [v]
  #?(:clj (instance? CompletionStage v)
     :cljs (instance? Promise v)))

;; Predicates

(defn resolved?
  "Returns true if promise `p` is already fulfilled."
  [p]
  (-resolved? p))

(defn rejected?
  "Returns true if promise `p` is already rejected."
  [p]
  (-rejected? p))

(defn pending?
  "Returns true if promise `p` is stil pending."
  [p]
  (-pending? p))

(defn extract
  "Returns the current promise value."
  [p]
  (-extract p))

(defn done?
  "Returns true if promise `p` is already done."
  [p]
  (not (-pending? p)))

;; Chaining

(defn map
  "Apply a function to the promise value and
  return a new promise with the result."
  [f p]
  (-map p f))

(defn mapcat
  "Same as `map` but removes one level of
  promise neesting. Useful when the map function
  returns a promise instead of value.

  In JS environment this function is analogous
  to `map` because the promise abstraction overloads
  the `map` operator."
  [f p]
  (-bind p f))

(defn then
  "Same as `map` but with parameters inverted
  for convenience and for familiarity with
  javascript's promises `.then` operator."
  [p f]
  (-map p f))

(defn bind
  "A chain helper for promises."
  [p callback]
  (-bind p callback))

(defn chain
  "Like then but accepts multiple parameters."
  [p & funcs]
  (reduce #(then %1 %2) p funcs))

(defn branch
  [p success failure]
  (-> p
      (-map success)
      (-catch failure)))

(defn catch
  "Catch all promise chain helper."
  ([p f]
   (-catch p f))
  ([p type f]
   (-catch p (fn [e]
                 (if (instance? type e)
                   (f e)
                   (throw e))))))

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
  #?(:clj (-> (then #(callback))
              (catch #(callback)))
     :cljs (.finally p callback)))

(defn all
  "Given an array of promises, return a promise
  that is fulfilled  when all the items in the
  array are fulfilled."
  [promises]
  #?(:cljs (then (.all Promise (clj->js promises))
                 #(js->clj %))
     :clj (let [xf (clojure.core/map -promise)
                ps (into [] xf promises)]
            (then (-> (into-array CompletableFuture ps)
                      (CompletableFuture/allOf))
                  (fn [_]
                    (mapv -extract ps))))))

(defn any
  "Given an array of promises, return a promise
  that is fulfilled when first one item in the
  array is fulfilled."
  [promises]
  #?(:cljs (.any Promise (clj->js promises))
     :clj (->> (sequence (clojure.core/map -promise) promises)
               (into-array CompletableFuture)
               (CompletableFuture/anyOf))))

;; Cancellation

(defn cancel!
  "Cancel the promise."
  [p]
  (-cancel p)
  p)

(defn cancelled?
  "Return true if `v` is a cancelled promise."
  [v]
  (-cancelled? v))

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

(defn await
  [& args]
  (throw (ex-info "Should be only used in alet macro." {})))

#?(:clj
   (defmacro alet
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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pretty printing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn promise->str
  [p]
  (str "#<Promise["
       (cond
         (pending? p) "~"
         (rejected? p) (str "error=" (extract p))
         :else (str "value=" (extract p)))
       "]>"))

#?(:clj
   (defmethod print-method java.util.concurrent.CompletionStage
     [p ^java.io.Writer writer]
     (.write writer (promise->str p)))
   :cljs
   (extend-type Promise
     IPrintWithWriter
     (-pr-writer [p writer opts]
       (-write writer (promise->str p)))))
