# Channels (CSP pattern)

An implementation of channel abstraction and CSP patterns for Clojure;
is a [core.async][3] alternative implementation that laverages JDK19
Virtual Threads. 

There are [Code Walkthrought][0] where you can learn the main API
usage patterns. Also, you can read the [core.async rationale][1] for
better understanding the main ideas of the CSP pattern.

The promesa channel and csp patterns implementation do not intend to
be a replacement for core.async; and there are cases where
[core.async][3] is preferable; the main usage target for promesa
channels and csp patterns implementation is for JVM based backends
with JDK>=19.

**NOTE: Although the main focus is the use in JVM, where is all the
potential; the channel implementation and all internal buffers are
implemented in CLJC. This means that, if there is interest, we can
think about exposing channels API using promises. In any case, _the
usefulness of channel implementation in CLJS remains to be seen._**


## Differences with `core.async`

The main highlights and differences with [core.async][3] are:

- **There are no macro transformations**, the `go` macro is a
  convenient alias for `p/vthread` (or `p/thread` when vthreads are
  not available); there are not limitation on using blocking calls
  inside `go` macro neither many other inconveniences of core.async go
  macro, mainly thanks to the JDK19 with preview enabled Virtual
  Threads.
- **No callbacks**, functions returns promises or blocks; you can use
  the promise composition API or thrad blocking API, whatever you
  wants.
- **No take/put limits**; you can attach more than 1024 pending tasks
  to a channel.
- **Simplier mental model**, there are no differences between parking
  and blocking operations.
- **Analogous performance**; in my own stress tests it has the same
  performance as core.async.

There are also some internal differences that you should know:

- The promesa implementation cancells immediatelly all pending puts
  when channel is closed in contrast to core.async that leaves them
  operative until all puts are succeded.
- The promesa implementation takes a bit less grandular locking than
  core.async, but on the end it should not have any effect on the
  final performance or usability.

## Getting Started

This documentation supposes you have some knowledge of core.async API.


#### Working with channels API

Lets create a channel and put value in-to:

```clojure
(require '[promesa.exec.csp :as sp])

(def ch (sp/chan 2))

;; perform a blocking put operation using a blocking operation
(sp/put! ch :a)
;; => true

;; Or perform a blocking put operation using `put` function
;; that returns a promise/future-like object (CompletableFuture)
@(sp/put ch :b)
;; => true
```

Now, lets try to retrieve data from channel:

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

If you want perform multiple operations on the same or mutliple
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

There are some situations when you want multiple readers on the same
data or implement some kind of pub/sub. For this reason we have the
multiplexed channel constructors: `mult` and `mult*`.

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

The `mult` constructor returns a muliplexer, and as it implements the
channel API, you can put values in directly. For the cases when you
already have a channel that you want multiplext, just use the `mult*`.

The `mult*` works in the same way as `clojure.core.async/mult`.  There
are also `untap!` function for removing the channel from the
multiplexer.

Closed channels are automatically removed from the multiplexer.


[0]: https://github.com/funcool/promesa/blob/master/doc/csp-walkthrought.clj
[1]: https://clojure.org/news/2013/06/28/clojure-clore-async-channels
[3]: https://github.com/clojure/core.async
