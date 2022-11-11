# Execution Patterns

## Channels (CSP)

A [core.async][3] alternative implementation that laverages JDK19 Virtual Threads; therefore, it is
mainly available in the JVM. It combines a new and simplified channel implementation, JDK virtual
thrads and composability of promises (CompletableFuture's).

There are [Code Walkthrought][0] where you can learn the main API usage patterns. Also, you can read
the [core.async rationale][1] for better understanding the main ideas of the CSP pattern.


The main highlights and differences with [core.async][3] are:

- **There are no macro transformations**, the `go` macro is a convenient alias for `p/vthread` (or
  `p/thread` when vthreads are not available); there are not limitation on using blocking calls
  inside `go` macro neither many other inconveniences of core.async go macro, mainly thanks to the
  JDK19 with preview enabled Virtual Threads.
- **No callbacks**, functions returns promises or blocks.
- **No take/put limits**; you can attach more than 1024 pending tasks to a channel.
- **Simplier mental model**, there are no differences between parking and blocking operations.
- **Analgous performance**; in my own stress tests it has the same performance as core.async.

There are also some internal differences that you should know:

- The promesa implementation cancells immediatelly all pending puts when channel is closed in
  contrast to core.async that leaves them operative until all puts are succeded.
- The promesa implementation takes a bit less grandular locking than core.async, but on the end it
  should not have any effect on the final performance or usability.

**The promesa channel and csp patterns implementation do not intend to be a replacement for
core.async; and there are cases where [core.async][3] is preferable; the main usage target for
promesa channels and csp patterns implementation is for JVM based backends with JDK>=19.**

**Although the main focus is the use in JVM, where is all the potential; the channel
implementation and all internal buffers are implemented in CLJC. This means that, if there is
interest, we can think about exposing channels api using promises. In any case, _the usefulness of
channel implementation in CLJS remains to be seen._**


## Bulkhead

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

[0]: https://github.com/funcool/promesa/blob/master/doc/csp-walkthrought.clj
[1]: https://clojure.org/news/2013/06/28/clojure-clore-async-channels
[2]: https://stackoverflow.com/questions/30391809/what-is-bulkhead-pattern-used-by-hystrix
[3]: https://github.com/clojure/core.async
