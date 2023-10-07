# Channels (CSP pattern)

An implementation of channel abstraction and CSP patterns for Clojure and ClojureScript.
It's a [core.async][3] alternative implementation of channel abstraction that leverages
platform facilities for concurrency (no go macro transformations, leverages virtual threads on the JVM).

**NOTE**: Virtual threads are only available on JDK 21+ (or JDK 19 with experimental features enabled).

See the [Code Walkthrough][0] to learn the main API usage patterns. Also,
you can read the [core.async rationale][1] for better understanding the main ideas of the
CSP pattern.

**NOTE**: Although the main focus is the use in JVM, where all of the potential is, the
channel implementation is also available on CLJS. There are no go macros on CLJS, but all
the operators (including `alts`) can be used with already available promesa API and
syntactic abstractions (such as `promesa.core/loop` and `promesa.core/recur`). Read the
docstring to know if the operator/helper internally uses vthreads or not.


## Differences with `core.async`

The main highlights and differences with [core.async][3] are:

- **There are no macro transformations**, the `go` macro is a convenient alias for
  `p/vthread` (or `p/thread` when vthreads are not available); there are not limitation on
  using blocking calls inside `go` macro neither many other inconveniences of core.async
  go macro, mainly thanks to virtual threads. _They are only available on JVM_.
- **No callbacks**, functions returns promises or blocks; you can use the promise
  composition API or thread blocking API, whatever you want.
- **No take/put limits**; you can attach more than 1024 pending tasks to a channel.
- **Simpler mental model**, there are no differences between parking and blocking
  operations.
- **Analogous performance**; in my own stress tests it has the same performance as
  core.async.
- **First class errors** support on channels.

There are also some internal differences that you should know:

- The promesa implementation immediately cancels all pending puts when channel is closed
  in contrast to core.async that leaves them operative until all puts are succeeded.
- The promesa implementation takes less of a granular locking approach than core.async, but
  it should not have any effect on the final performance or usability.
- Operators or channel helpers do not use vthreads internally so they can be used safely
  on CLJS or JVM without virtual threads.


## Getting Started

This documentation assumes you have a bit of knowledge of core.async API.


#### Working with channels API

Let's create a channel and put a value in it:

```clojure
(require '[promesa.exec.csp :as sp])

(def ch (sp/chan :buf 2))

;; perform a blocking put operation using a blocking operation
(sp/put! ch :a)
;; => true

;; Or perform a blocking put operation using `put` function
;; that returns a promise/future-like object (CompletableFuture)
@(sp/put ch :b)
;; => true
```

Now, let's try to retrieve data from channel:

```clojure
;; Using a blocking helper, analogous to clojure.core.async/<!!
(sp/take! ch)
;; => :a

;; Or blocking on promise
@(sp/take ch)
;; => :b
```

You also can take with timeout:

```clojure
@(sp/take ch 1000 :not-found)
;; => :not-found
```

For convenience and `core.async` familiarity, there are also `<!` and
`>!` functions that have the same api as their counterpart `take!` and
`put!`


#### The go blocks

Now, knowing how channels works, let's start with `go` blocks.

In contrast to `core.async`, the promesa go blocks are just virtual
threads (or standard threads if the vthreads are not available) so
there are no macro limitations nor blocking/parking differences.

The promesa go blocks returns promises (CompletableFuture's) instead
of channels. This is because the code on go block can fail and
channels are bad abstraction for represent a computation result that
can fail.

```clojure
@(sp/go
   (sp/<! ch 1000 :timeout))
;; => :timeout
```

But if you need a channel, there are `go-chan` macro. The `go` +
`loop` macro is also available as `go-loop`.


#### Multiple Operations

If you want to perform multiple operations on the same or multiple
channels. In the same line as `clojure.core.async/alts!!`, this
library exposes the `promesa.exec.csp/alts!` macro that has the same
API:

```clojure
(let [c1 (sp/chan)
      c2 (sp/chan)]
  (sp/go-loop []
    (let [[v ch] (sp/alts! [c1 c2])]
      (when v
        (println "Read" v "from" ch)
        (recur))))

  @(sp/go
     (sp/>! c1 "hi")
     (sp/>! c2 "there")
     (sp/close! c1)
     (sp/close! c2)))

;; Prints (on stdout):
;;   Read hi from #<promesa.exec.csp.channel.Channel ...>
;;   Read there from #<promesa.exec.csp.channel.Channel ...>
```

For completeness, there are also `alts` function, that returns a
`CompletableFuture` instead of blocking the current thread.


#### Channel multiplexing

There are some situations when you want multiple readers on the same data or implement
some kind of pub/sub. For this reason we have the multiplexed channel constructors: `mult`
and `mult*`.

```clojure
(def mx (sp/mult))

(a/go
  (let [ch (sp/chan)]
    (sp/tap! mx ch)
    (println "go 1:" (sp/<! ch))
    (sp/close! ch)))

(a/go
  (let [ch (sp/chan)]
    (sp/tap! mx ch)
    (println "go 2:" (sp/<! ch))
    (sp/close! ch)))

(sp/>! mx :a)

;; Will print to stdout (maybe in different order)
;;   go 1: :a
;;   go 2: :a
```

The `mult` constructor returns a multiplexer, and as it implements the channel API, you can
put values in directly. For the cases when you already have a channel that you want
multiplex, just use the `mult*`.

The `mult*` works in the same way as `clojure.core.async/mult`.  There are also `untap!`
function for removing the channel from the multiplexer.

Closed channels are automatically removed from the multiplexer.


#### Errors

One difference with `core.async`, promesa channels supports the notion of error. The errors
can happen externally (a producer process that fails) or internally (happens on the
provided transducer).

For notification of a possible external exception cause, you should proceed to call close!
function with the cause as second argument:

```clojure
(sp/close! ch (ex-info "error" {}))
```

If the exception is happened on the transducer, the channel will be closed with that
exception. This behavior can be overridden specifying custom exception handler on the
channel constructor:

```clojure
(def ch (sp/chan :buf 1 :xf (map inc) :exh sp/throw-uncaught))
```

The `sp/throw-uncaught` function is a builtin exception handler that just uses the
platform mechanism to throw the exception to the uncaught handler (the default behavior of
core.async). If no `:exh` parameter is provided the `sp/close-with-exception` will be
used. This is only relevant if you provide transducer.

An exception handler is just a function that accepts two arguments: the channel and the
exception instance.


#### Custom Executor

The channels by default will use virtual threads (if available, or the common-pool in
other case) for internal dispatching. But you can overwrite that providing a custom
executor on the channel constructor:

```clojure
(require '[promesa.exec :as px])

(def executor (px/cached-executor))

(def ch (sp/chan :exc executor))
```


[0]: https://github.com/funcool/promesa/blob/master/doc/csp-walkthrough.clj
[1]: https://clojure.org/news/2013/06/28/clojure-clore-async-channels
[3]: https://github.com/clojure/core.async
