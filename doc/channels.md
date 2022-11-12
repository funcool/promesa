# Channels (CSP pattern)

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
- **Analogous performance**; in my own stress tests it has the same performance as core.async.

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

[0]: https://github.com/funcool/promesa/blob/master/doc/csp-walkthrought.clj
[1]: https://clojure.org/news/2013/06/28/clojure-clore-async-channels
[3]: https://github.com/clojure/core.async
