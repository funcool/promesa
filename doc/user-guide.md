# User Guide

A promise library & async toolkit for Clojure and ClojureScript.


## Install

Leiningen:

deps.edn:

```clojure
funcool/promesa {:mvn/version "9.0.507"}
```

```clojure
[funcool/promesa "9.0.507"]
```

## Introduction

A **promise** is an abstraction that represents the result of an
asynchronous operation that has the notion of error. Backedn with
[CompletebleFuture][0] on the JVM and [Promise][1] on JS.

This is a list of all possible states for a promise:

- `resolved`: means that the promise contains a value.
- `rejected`: means that the promise contains an error.
- `pending`: means that the promise does not have value.

The promise can be considered *done* when it is resolved or rejected.

**NOTE:** keep in mind that the vast majority of things work
identically regardless of the runtime, but there are cases where the
limitations of the platform implementation imply differences or even
the omission of some functions.


## Working with `promises`

### Creating a promise

There are several different ways to create a promise instance. If you
just want to create a promise with a plain value, you can use the
polymorphic `promise` function:

```clojure
(require '[promesa.core :as p])

;; creates a promise from value
(p/promise 1)

;; creates a rejected promise
(p/promise (ex-info "error" {}))
```

It automatically coerces the provided value to the appropriate promise
instance: `rejected` when the provided value is an exception and
`resolved` in all other cases.

If you already know that the value is either `resolved` or `rejected`,
you can skip the coercion and use the `resolved` and `rejected`
functions:

```clojure
;; Create a resolved promise
(p/resolved 1)
;; => #object[java.util.concurrent.CompletableFuture 0x3e133219 "resolved"]

;; Create a rejected promise
(p/rejected (ex-info "error" {}))
;; => #object[java.util.concurrent.CompletableFuture 0x3e563293 "rejected"]
```

Another option is to create an empty promise using the `deferred`
function and provide the value asynchronously using `p/resolve!` and
`p/reject!`:

```clojure
(defn sleep
  [ms]
  (let [p (p/deferred)]
    (future (p/resolve! p))
    p))
```

Another option is using a factory function. If you are familiar with
JavaScript, this is a similar approach:

```clojure
@(p/create (fn [resolve reject] (resolve 1)))
;; => 1
```

**NOTE:** the `@` reader macro only works on JVM.

The factory will be executed synchronously (in the current thread) but
if you want to execute it asynchronously, you can provide an executor
(JVM only):


```clojure
(require '[promesa.exec :as exec])

@(p/create (fn [resolve reject] (resolve 1)) exec/default-executor)
;; => 1
```

Another way to create a promise is using the `do` macro:

```clojure
(p/do
  (let [a (rand-int 10)
        b (rand-int 10)]
    (+ a b)))
```

The `do` macro works similarly to clojure's `do` block, so you can
provide any expression, but only the last one will be returned. That
expression can be a plain value or another promise.

If an exception is raised inside the `do` block, it will return the
rejected promise instead of re-raising the exception on the stack.

If the `do` contains more than one expression, each expression will be
treated as a promise expression and will be executed sequentially,
each awaiting the resolution of the prior expression.

For example, this `do` macro:

```clojure
(p/do (expr1)
      (expr2)
      (expr3))
```

Is roughtly equivalent to `let` macro (explained below):

```clojure
(p/let [_ (expr1)
        _ (expr2)]
  (expr3))
```

Finally, _promesa_ exposes a `future` macro very similar to the
`clojure.core/future`:

```clojure
@(p/future (some-complex-task))
;; => "result-of-complex-task"
```

### Chaining computatons

This section explains the helpers and macros that **promesa** provides
for chain different (high-probably asynchonous) operations in a
sequence of operations.

### `then`

The most common way to chain a transformation to a promise is using
the general purpose `then` function:

```clojure
@(-> (p/resolved 1)
     (p/then inc))
;; => 2

;; flatten result
@(-> (p/resolved 1)
     (p/then (fn [x] (p/resolved (inc x)))))
;; => 2
```

As you can observe in the example, `then` handles functions that
return plain values as well as functions that return promise instances
(which will automatically be flattened).

**NOTE**: If you know that the chained function will always return
plain values, you can use the more performant `then'` variant of this
function.

### `map`

The `map` function works similarly to the `then'` function, the
difference is the order of arguments:

```clojure
(def result
  (->> (p/resolved 1)
       (p/map inc)))

@result
;; => 2
```

### `chain`

If you have multiple transformations and you want to apply them in one
step, there are the `chain` and `chain'` functions:

```clojure
(def result
  (-> (p/resolved 1)
      (p/chain inc inc inc)))

@result
;; => 4
```

**NOTE**: `chain` is analogous to `then` and `then'` but accept
multiple transformation functions.


### `->`, `->>` and `as->` (macros)

**NOTE**: `->` and `->>` introduced in 6.1.431, `as->` introduced in 6.1.434.

This threading macros simplifices chaining operation, removing the
need of using `then` all the time.

Lets look an example using `then` and later see how it can be improved
using the `->` threading macro:

```clojure
(-> (p/resolved {:a 1 :c 3})
    (p/then #(assoc % :b 2))
    (p/then #(dissoc % :c)))
```

Then, the same code can be simplified with:

```clojure
(p/-> (p/resolved {:a 1 :c 3})
      (assoc :b 2))
      (dissoc :c))
```

The threading macros hides all the accidental complexity of using
promise chaining.

The `->>` and `as->` are equivalent to the clojure.core macros, but
they work with promises in the same way as `->` example shows.


### `handle`

If you want to handle rejected and resolved callbacks in one unique
callback, then you can use the `handle` chain function:


```clojure
(def result
  (-> (p/promise 1)
      (p/handle (fn [result error]
                  (if error :rejected :resolved)))))

@result
;; => :resolved
```


### `finally`

And finally if you want to attach a (potentially side-effectful)
callback to be always executed notwithstanding if the promise is
rejected or resolved, there is a executed regardless of whether the
promise is rejected or resolved, there is a `finally` function (very
similar to try/finally):



```clojure
(def result
  (-> (p/promise 1)
      (p/finally (fn [_ _]
                  (println "finally")))))

@result
;; => 1
;; => stdout: "finally"
```

### Composition

### `let`

The _promesa_ library comes with convenient syntactic-sugar that
allows you to create a composition that looks like synchronous code
while using the Clojure's familiar `let` syntax:

```clojure
(require '[promesa.core :as p]
         '[promesa.exec :as px])

;; A function that emulates asynchronos behavior.
(defn sleep
  [wait]
  (p/create (fn [resolve reject]
              (px/schedule! wait #(resolve wait)))))

(def result
  (p/let [x (sleep 42)
          y (sleep 41)
          z 2]
    (+ x y z)))

@result
;; => 85
```

The `let` macro behaves identically to Clojure's `let` with the
exception that it always returns a promise. If an error occurs at any
step, the entire composition will be short-circuited, returning
exceptionally resolved promise.

Under the hood, the `let` macro evalutes to something like this:

```clojure
(p/then
  (sleep 42)
  (fn [x]
    (p/then
      (sleep 41)
      (fn [y]
        (p/then
          2
          (fn [z]
            (p/promise (do (+ x y z)))))))))
```

### `all`

In some circumstances you will want wait for completion of several
promises at the same time. To help with that, _promesa_ also provides
the `all` helper.

```clojure
(let [p (p/all [(do-some-io)
                (do-some-other-io)])]
  (p/then p (fn [[result1 result2]]
              (do-something-with-results result1 result2))))
```
### `plet` macro

The `plet` macro combines syntax of `let` with `all`; and enables a
simple declaration of parallel operations followed by a body
expression that will be executed when all parallel operations have
successfully resolved.

```clojure
@(p/plet [a (p/delay 100 1)
          b (p/delay 200 2)
          c (p/delay 120 3)]
   (+ a b c))
;; => 6
```

The `plet` macro is just a syntactic sugar on top of `all`. The
previous example can be written using `all` in this manner:

```clojure
(p/all [(p/delay 100 1)
        (p/delay 200 2)
        (p/delay 120 3)]
  (fn [[a b c]] (+ a b c)))
```

The real parallelism strictly depends on the underlying implementation
of the executed functions. If they does syncrhonous work, all the code
will be executed serially, almost identical to the standard let. Is
the user responsability of the final execution model.


### `any`

There are also circumstances where you only want the first
successfully resolved promise. For this case, you can use the `any`
combinator:

```clojure
(let [p (p/any [(p/delay 100 1)
                (p/delay 200 2)
                (p/delay 120 3)])]
  (p/then p (fn [x]
              (.log js/console "The first one finished: " x))))
```

### `race`

The `race` function method returns a promise that fulfills or rejects
as soon as one of the promises in an iterable fulfills or rejects,
with the value or reason from that promise:

```clojure
@(p/race [(p/delay 100 1)
          (p/delay 110 2)])
;; => 1
```


### Error handling

One of the advantages of using the promise abstraction is that it
natively has a notion of errors, so you don't need to reinvent it. If
some computation inside the composed promise chain/pipeline raises an
exception, the pipeline short-circuits and propagates the exception to
the last promise in the chain.

The `catch` function adds a new handler to the promise chain that will
be called when any of the previous promises in the chain are rejected
or an exception is raised. The `catch` function also returns a promise
that will be resolved or rejected depending on what happens inside the
catch handler.

Let see an example:

```clojure
(-> (p/rejected (ex-info "error" nil))
    (p/catch (fn [error]
               (.log js/console error))))
```

If you prefer `map`-like parameter ordering, the `err` function (and
`error` alias) works in same way as `catch` but has parameters ordered
like `map`:

```clojure
(->> (p/rejected (ex-info "error" nil))
     (p/error (fn [error]
                (.log js/console error))))
```

### Delays and Timeouts.

JavaScript, due to its single-threaded nature, does not allow you to
block or sleep. But, with promises you can emulate that functionality
using `delay` like so:

```clojure
(-> (p/delay 1000 "foobar")
    (p/then (fn [v]
              (println "Received:" v))))

;; After 1 second it will print the message
;; to the console: "Received: foobar"
```

The promise library also offers the ability to add a timeout to async
operations thanks to the `timeout` function:

```clojure
(-> (some-async-task)
    (p/timeout 200)
    (p/then #(println "Task finished" %))
    (p/catch #(println "Timeout" %)))
```

In this example, if the async task takes more that 200ms then the
promise will be rejected with a timeout error and then successfully
captured with the `catch` handler.


### Promise chaining & execution model

Let's try to understand how promise chained functions are executed and
how they interact with platform threads. **This section is mainly
affects the **JVM**.

Lets consider this example:

```clojure
@(-> (p/delay 100 1)
     (p/then' inc)
     (p/then' inc))
;; => 3
```

This will create a promise that will resolve to `1` in 100ms (in a
separate thread); then the first `inc` will be executed (in the same
thread), and then another `inc` is executed (in the same thread). In
total only one thread is involved.

This default execution model is usually preferrable because it don't
abuse the task scheduling and leverages function inlining on the JVM.

But it does have drawbacks: this approach will block the thread until
all of the chained callbacks are executed. For small chains this is
not a problem. However, if your chain has a lot of functions and
requires a lot of computation time, this might cause unexpected
latency. It may block other threads in the thread pool from doing
other, maybe more important, tasks.

For such cases, _promesa_ exposes an additional arity for provide a
user-defined executor to control where the chained callbacks are
executed:

```clojure
(require '[promesa.exec :as px])

@(-> (p/delay 100 1)
     (p/then inc px/*default-executor*)
     (p/then inc px/*default-executor*))
;; => 3
```

This will schedule a separate task for each chained callback, making
the whole system more responsive because you are no longer executing
big blocking functions; instead you are executing many small tasks.

The `px/*default-executor*` is a `ForkJoinPool` instance that is
highly optimized for lots of small tasks.

On JDK19 with Preview enabled you will also have the
`px/*vthread-executor*` that is an instance of *Virtual Thread per
task* executor.



## Scheduling & Executors

Additionally to the _promise_ abstraction, **promesa** library comes
with many helpers and factories for execution and scheduling of tasks
for asynchronous execution.

Although this API works in the JS runtime and some of the function has
general utility, the main target is the JVM platform.


### Async Tasks

Firstly, lets define **async task**: a function that is executed out
of current flow using a different thread. Here, **promesa** library
exposes mainly two functions:

- `promesa.exec/run!`: useful when you want run a function in a
  different thread and you don't care abour the return value; it
  returns a promise that will be fullfilled when the callback
  terminates.
- `promesa.exec/submit!` useful when you want run a function in a
  different thread and you need the return value; it returns a promise
  that will be fullfilled with the return value of the function.


Let see some examples:

```clojure
(require '[promesa.exec :as px])


@(px/run! (fn []
            (prn "I'm running in different thread")
            1))
;; => nil

@(px/submit! (fn []
               (prn "I'm running in different thread")
               1))
;; => 1
```

The both functions optionally accepts as first argument an executor
instance that allows specify the executor where you want execute the
specified function. If no executor is provided, the default one is
used (binded on the `promesa.exec/*default-executor*` dynamic var).

Also, in both cases, the returned promise is cancellable, so if for
some reason the function is still not execued, the cancellation will
prevent the execution. You can cancel a cancellable promise with
`p/cancel!` function.


### Delayed Tasks

This consists in a simple helper that allows scheduling execution of a
function after some amount of time.


```clojure
(require '[promesa.exec :as exec])
(exec/schedule! 1000 (fn []
                       (println "hello world")))
```

This example shows you how you can schedule a function call to be
executed 1 second in the future. It works the same way for both
Clojure and ClojureScript.

The tasks can be cancelled using its return value:

```clojure
(def task (exec/schedule! 1000 #(do-stuff)))

(p/cancel! task)
```

The execution model depends on the platform: on the **JVM** a default
scheduler executor is used and the the scheduled function will be
executed in different thread; on **JS** runtime the function will be
executed in a _microtask_.


### Executors Factories

A collection of factories function for create executors instances (JVM only):

- `px/cached-executor`: creates a thread pool that creates new threads
  as needed, but will reuse previously constructed threads when they
  are available.
- `px/fixed-executor`: creates a thread pool that reuses a fixed
  number of threads operating off a shared unbounded queue.
- `px/single-executor`: creates an Executor that uses a single worker
  thread operating off an unbounded queue
- `px/scheduled-executor`: creates a thread pool that can schedule
  commands to run after a given delay, or to execute periodically.
- `px/forkjoin-executor`: creates a thread pool that maintains enough
  threads to support the given parallelism level, and may use multiple
  queues to reduce contention.

Since v9.0.x there are new factories that uses the JDK>=19 preview API:

- `px/thread-per-task-executor`: creates an Executor that starts a new
  Thread for each task.
- `px/vthread-per-task-executor`: creates an Executor that starts a new
  virtual Thread for each task.


### Helpers

#### `pmap` (experimental)

This is a simplified `clojure.core/pmap` analogous function that allows
execute a potentially computationally expensive or io bound functions
in parallell.

It returns a lazy chunked seq (uses the clojure's default chunk size:
32) and the maximum parallelism is determined by the provided
executor.

Example:

```clojure
(defn slow-inc
  [x]
  (Thread/sleep 1000)
  (inc x))

(time
 (doall
  (px/pmap slow-inc (range 10))))

;; "Elapsed time: 2002.724345 msecs"
;; => (1 2 3 4 5 6 7 8 9 10)

(time
 (doall
  (map slow-inc (range 10))))

;; Elapsed time: 10001.912614 msecs"
;; => (1 2 3 4 5 6 7 8 9 10)
```

#### `with-executor` macro (experimental)

This allows run a scoped code with the `px/*default-executor*` binded
to the provided executor. The provided executor can be a function for
lazy executor instantiation.

It optionally accepts metadata on the executor part for indicate:

- `^:shutdown`: shutdown the pool before return
- `^:interrupt`: shutdown and interrupt before return

There an example on how you can customize the executor for **pmap**:

```clojure
(time
 (px/with-executor ^:shutdown (px/fixed-executor :parallelism 2)
   (doall (px/pmap slow-inc (range 10)))))

;; "Elapsed time: 5004.506274 msecs"
;; => (1 2 3 4 5 6 7 8 9 10)
```


## Execution patterns

### CSP

TODO: in development


### Bulkhead

In general, the goal of the bulkhead pattern is to avoid faults in one
part of a system to take the entire system down. The bulkhead
implementation in **promesa** limits the number of concurrent calls.

This [SO answer][2] explains the concept very well.


So lets stat with an example:

```clojure
(require '[promesa.exec.bulkhead :as pxb]
         '[promesa.exec :as px])

;; All parameters are optional and have default value
(def instance (pxb/create :concurrency 1
                          :queue-size 16
                          :executor px/*default-executor*))

@(px/submit! instance (fn []
                        (Thread/sleep 1000)
                        1))
;; => 1
```

At first glance, this seems like an executor instance because it
resembles the same API (aka `px/submit! call). And it proxies all
submits to the provided executor (or the default one if not provided).

When you submits a task to it, it does the following:

- Checkes if concurrency limit is not reached, if not, proceed to
  execute the function in the underlying executor.
- If concurrency limit is reached, it queues the execution until
  other tasks are finished.
- If queue limit is reached, the returned promise will be
  automatically rejected with an exception indicating that queue limit
  reached.

This allows control the concurrency and the queue size on access to
some resource.


NOTES:

- _As future improvements we consider adding an option for delimit the
  **max wait** and cancel/reject tasks after some timeout._
- _For now it is implemented only on JVM but I think is pretty easy to
  implement on CLJS, so if there are some interest on it, feel free to
  open and issue for just show interest or discuss how it can be
  contributed._

## Performance overhead

_promesa_ is a lightweight abstraction built on top of native
facilities (`CompletableFuture` in the JVM and `js/Promise` in
JavaScript). Internally we make heavy use of protocols in order to
expose a polymorphic and user friendly API, and this has little
overhead on top of raw usage of `CompletableFuture` or `Promise`.


## Contributing

Unlike Clojure and other Clojure contrib libs, this project does not
have many restrictions for contributions. Just open an issue or pull
request.


### Get the Code

_promesa_ is open source and can be found on
[github](https://github.com/funcool/promesa).

You can clone the public repository with this command:

```
git clone https://github.com/funcool/promesa
```

### Run tests

To run the tests execute the following:

For the JVM platform:

```
clojure -X:dev:test
```

And for JS platform:

```
clojure -A:dev -T:build build-cljs-tests
node target/tests.js
```

You will need to have Node.js installed on your system.


### License

_promesa_ is licensed under MPL-2.0 license:

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.

Copyright (c) Andrey Antukh <niwi@niwi.nz>
```


[0]: https://docs.oracle.com/en/java/javase/19/docs/api/java.base/java/util/concurrent/CompletableFuture.html
[1]: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise
[2]: (https://stackoverflow.com/questions/30391809/what-is-bulkhead-pattern-used-by-hystrix)
