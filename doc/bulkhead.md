# Bulkhead (concurrency limiter)

The bulkhead pattern prevents faults in one part of the system from
taking the entire system limiting the number of concurrent calls.

This [SO answer][0] explains the concept very well.

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

The `sync` bulkhead works fine with JVM virtual threads.

[0]: https://stackoverflow.com/questions/30391809/what-is-bulkhead-pattern-used-by-hystrix
