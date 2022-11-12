# Bulkhead (concurrency limiter)

In general, the goal of the bulkhead pattern is to avoid faults in one part of a system to take the
entire system down. The bulkhead implementation in **promesa** limits the number of concurrent
calls.

This [SO answer][0] explains the concept very well.


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

At first glance, this seems like an executor instance because it resembles the same API (aka
`px/submit! call).

When you submits a task to it, it does the following:

- Checkes if concurrency limit is not reached, if not, proceed to execute the function in the
  underlying executor.
- If concurrency limit is reached, it queues the execution until other tasks are finished.
- If queue limit is reached, the returned promise will be automatically rejected with an exception
  indicating that queue limit reached.

This allows control the concurrency and the queue size on access to some resource.


NOTES:

- _As future improvements we consider adding an option for delimit the **max wait** and
  cancel/reject tasks after some timeout._
- _For now it is implemented only on JVM but I think is pretty easy to implement on CLJS, so if
  there are some interest on it, feel free to open and issue for just show interest or discuss how
  it can be contributed._

[0]: https://stackoverflow.com/questions/30391809/what-is-bulkhead-pattern-used-by-hystrix
