# Bulkhead (concurrency limiter)

In general, the bulkhead pattern prevents faults in one part of the system from taking the
entire system down. The bulkhead implementation in **promesa** limits the number of concurrent
calls.

This [SO answer][0] explains the concept very well.


So let's start with an example:

```clojure
(require '[promesa.exec.bulkhead :as pxb]
         '[promesa.exec :as px])

;; All parameters are optional and have default value
(def instance (pxb/create :permits 1 :queue 16))

@(px/submit! instance (fn []
                        (Thread/sleep 1000)
                        1))
;; => 1
```

At first glance, this seems like an executor instance because it resembles the same API (aka
`px/submit!` call).

When you submit a task to it, it does the following:

- Checks if concurrency limit is not reached, if not, proceed to execute the function in the
  underlying executor.
- If concurrency limit is reached, it queues the execution until other tasks are finished.
- If queue limit is reached, the returned promise will be automatically rejected with an exception
  indicating that queue limit was reached.

This allows you to control the concurrency and the queue size on access to some resource.

[0]: https://stackoverflow.com/questions/30391809/what-is-bulkhead-pattern-used-by-hystrix
