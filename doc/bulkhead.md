# Bulkhead (concurrency limiter)

The bulkhead pattern prevents faults in one part of the system from
taking the entire system limiting the number of concurrent calls.

This [SO answer][0] explains the concept very well.

## Getting Started

There are two different implementations: `:sync` and `:async`.

- `:async` (or `:executor`) - runs the task in an executor.
- `:sync` (or `:semaphore`) - runs the task in the current thread.

Independently of the implementation, the bulkhead works in this whay:

- Checks if concurrency limit is not reached, if not, proceed to
  execute the function in the underlying executor (on in the same
  thread).
- If concurrency limit is reached, it queues the execution until other
  tasks are finished (using queue in `async` or just blocking the
  current thrad in case of `sync`).
- If queue limit is reached, return a rejection.

So let's start with some examples:

```clojure
(require '[promesa.exec.bulkhead :as pxb]
         '[promesa.exec :as px])

;; All parameters are optional and have defaults
(def instance (pxb/create :type :async :permits 1 :queue 16))

@(px/submit instance
            (fn []
              (Thread/sleep 1000)
              1))
;; => 1
```

At first glance, this seems like an executor instance because it
resembles the same API (aka `px/submit` call, with the particularity
that the `sync` or `semaphore` based bulkhead implementation executes
the task in the current thread, blocking the call on the submit).

Additionally to the executor interface, it also implemens the new
`IInvokable` protocol, so you can execute a synchronous call
(independently of the final bulkhead implementation) thaks to the
`px/invoke` helper. Internally, on async bulkhead is just a
combination of `px/submit` with `px/join`, but on the `sync` bulkhead
implementation it does not uses the CompletableFuture machinary and
just executes the function almost with no indirections.

NOTE: The `sync` bulkhead works fine with JVM virtual threads.



## Available options

The `create` function accept the following parameters and its defaults:

- `:type`: can be `:sync` or `:async`
- `:executor`: only when async type is used, allows provide custome
  executor, if not provided the default one is used
- `:permits`: the max permits (concurrent jobs) allowed (defaults to 1)
- `:queue`: the max queued jobs allowed (defaults to
  Integer/MAX_VALUE), when maximum queue is reached, the task
  submision will be rejected
- `:timeout`: this settings means two different things depending on
  the used implementation (although in practical terms they represent
  something analogous):
  - on the `async` it means the timeout to put the task on the queue
    and is effective only if the queue is full, and it happens
    synchronously (before the task is submited to the internal
    executor)
  - on the `sync` it means the timeout of aquiring the semaphore
    and is always happens synchronously.

[0]: https://stackoverflow.com/questions/30391809/what-is-bulkhead-pattern-used-by-hystrix
