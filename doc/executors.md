# Scheduling & Executors

## Introduction

In addition to the _promise_ abstraction, **promesa** comes
with many helpers and factories for execution and scheduling tasks
for asynchronous execution.

Although this API works in the JS runtime and some of the functionality has
general utility, the main target is the JVM platform.


## Async Tasks

An **async task** is a function that is executed out
of current flow using a different thread. Here, **promesa** library
exposes mainly two functions:

- `promesa.exec/run`: useful when you want to run a function in a
  different thread and you don't care about the return value.
  It returns a promise that will be fulfilled when the callback
  terminates.
- `promesa.exec/submit` useful when you want to run a function in a
  different thread and you need the return value. It returns a promise
  that will be fulfilled with the return value of the function.

Let see some examples:

```clojure
(require '[promesa.exec :as px])


@(px/run (fn []
           (prn "I'm running in different thread")
           1))
;; => nil

@(px/submit (fn []
              (prn "I'm running in different thread")
              1))
;; => 1
```

Both functions optionally accept an executor instance as the first argument.
This executor is used to run the specified function.
If no executor is provided, the default one is
used (bound on the `promesa.exec/*default-executor*` dynamic var).

Also, in both cases, the returned promise is cancellable, so if for
some reason the function is still not executed, the cancellation will
prevent the execution. You can cancel a cancellable promise with
`p/cancel!` function.


## Delayed Tasks

This consists in a simple helper that allows scheduling execution of a
function after some amount of time.


```clojure
(require '[promesa.exec :as exec])
(exec/schedule 1000 (fn []
                      (println "hello world")))
```

This example shows you how you can schedule a function call to be
executed 1 second in the future. It works the same way for both
Clojure and ClojureScript.

The tasks can be cancelled using its return value:

```clojure
(def task (exec/schedule 1000 #(do-stuff)))

(p/cancel! task)
```

The execution model depends on the platform: on the **JVM** a default
scheduler executor is used and the the scheduled function will be
executed in different thread; on **JS** runtime the function will be
executed in a _microtask_.


## Executors Factories

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


## Helpers

### `pmap` (experimental)

This is a simplified `clojure.core/pmap` analogous function that allows
execution of a potentially computationally expensive or IO bound functions
in parallel.

It returns a lazy chunked seq (uses Clojure's default chunk size of 32)
and the maximum parallelism is determined by the provided executor.

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

### `with-executor` macro (experimental)

This allows running scoped code with the `px/*default-executor*` bound
to the provided executor. The provided executor can be a function for
lazy executor instantiation.

It optionally accepts metadata on the executor:

- `^:shutdown`: shutdown the pool before return
- `^:interrupt`: shutdown and interrupt before return

Here is an example of customizing the executor for **pmap**:

```clojure
(time
 (px/with-executor ^:shutdown (px/fixed-executor :parallelism 2)
   (doall (px/pmap slow-inc (range 10)))))

;; "Elapsed time: 5004.506274 msecs"
;; => (1 2 3 4 5 6 7 8 9 10)
```

[0]: https://docs.oracle.com/en/java/javase/19/docs/api/java.base/java/util/concurrent/CompletableFuture.html
[1]: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise
[2]: (https://stackoverflow.com/questions/30391809/what-is-bulkhead-pattern-used-by-hystrix)
[3]: https://github.com/clojure/core.async

