# Working with Promises

## Introduction

A **promise** is an abstraction that represents the result of an
asynchronous operation that has the notion of error. Backed with
[CompletebleFuture][0] on the JVM and [Promise][1] on JS.

This is a list of all possible states for a promise:

- `resolved`: means that the promise contains a value.
- `rejected`: means that the promise contains an error.
- `pending`: means that the promise does not have value.

The promise can be considered *done* when it is resolved or rejected.

**NOTE:** keep in mind that the vast majority of things work identically regardless of the
runtime, but there are cases where the limitations of the platform implementation imply
differences or even the omission of some functions.

## Creating a promise

There are several different ways to create a promise instance. If you just want to create
a promise with a plain value, you can use the polymorphic `promise` function:

```clojure
(require '[promesa.core :as p])

;; creates a promise from value
(p/promise 1)

;; creates a rejected promise
(p/promise (ex-info "error" {}))
```

It automatically coerces the provided value to the appropriate promise instance:
`rejected` when the provided value is an exception and `resolved` in all other cases.

If you already know that the value is either `resolved` or `rejected`, you can skip the
coercion and use the `resolved` and `rejected` functions:

```clojure
;; Create a resolved promise
(p/resolved 1)
;; => #<CompletableFuture[resolved:1964677152]>

;; Create a rejected promise
(p/rejected (ex-info "error" {}))
;; => #<CompletableFuture[rejected:1153075015]>
```

Another option is to create an empty promise using the `deferred` function and provide the
value asynchronously using `p/resolve!` and `p/reject!`:

```clojure
(defn some-fn
  [ms]
  (let [p (p/deferred)]
    (p/resolve! p nil)
    p))
```

Another option is using a factory function. If you are familiar with JavaScript, this is a
similar approach:

```clojure
@(p/create (fn [resolve reject] (resolve 1)))
;; => 1
```

The factory will be executed synchronously (in the current thread) but if you want to
execute it asynchronously, you can provide an executor:


```clojure
(require '[promesa.exec :as exec])

@(p/create (fn [resolve reject] (resolve 1)) exec/default-executor)
;; => 1
```

## Chaining computations

This section explains the helpers and macros that **promesa** provides for chain different
(high-probably asynchonous) operations in a sequence of operations.

It provides mainly two style of API:

1. one designed for use with `->` threading macro and make it easy and familiar to someone
   that already know how JS promises works. The functions that are part of this style are:
   `then`, `chain`, `catch`, `handle` and `finally`.
2. one designed for use with `->>` threading macro and focused on correctness and
   performance. The functions that are part of this style are: `fmap`, `mcat`, `hmap`,
   `hcat`, `merr` and `fnly`.

Lets look on detail on all of them.


### `then`

The most common way to chain a transformation to a promise is using
the general purpose `then` function. Consists on applying the function

```clojure
@(-> (p/resolved 1)
     (p/then inc))
;; => 2

;; flatten result
@(-> (p/resolved 1)
     (p/then (fn [x] (p/resolved (inc x)))))
;; => 2
```

As you can observe in the example, `then` handles functions that return plain values as
well as promise instances (which will automatically be flattened, in the same way as JS
promises).

For performance sensitive code, consider using a more specific functions like `fmap` or
`mcat`.


### `chain`

If you have multiple transformations and you want to apply them in one step, there are the
`chain` and `chain'` functions:

```clojure
(def result
  (-> (p/resolved 1)
      (p/chain inc inc inc)))

@result
;; => 4
```

**NOTE**: `chain` is analogous to `then` and `then'` but accept multiple transformation
functions. The `chain'` variant does not auto-flattens the return value.


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

The threading macros hides all the accidental complexity of using promise chaining.

The `->>` and `as->` are equivalent to the clojure.core macros, but they work with
promises in the same way as `->` example shows.


### `handle`

If you want to handle rejected and resolved callbacks in one unique callback, then you can
use the `handle` chain function:


```clojure
(def result
  (-> (p/promise 1)
      (p/handle (fn [result error]
                  (if error :rejected :resolved)))))

@result
;; => :resolved
```

It works in the same way as `then`, if the function returns a promise instance it will be
automatically unwrapped.


### `finally`

And finally if you want to attach a (potentially side-effectful)
callback to be always executed notwithstanding if the promise is
rejected or resolved:

```clojure
(def result
  (-> (p/promise 1)
      (p/finally (fn [_ _]
                  (println "finally")))))

@result
;; => 1
;; => stdout: "finally"
```

The return value of the function will be ignored and new promise
instance will be returned mirroning the original one.


### `fmap`

Returns a new promise instance which will be completed with the return value of applying a
function to the eventually successfully resolved promise.

```clojure
(def result
  (->> (p/resolved 1)
       (p/fmap inc)))

@result
;; => 2
```

In contrast to `then`, there are no automatic unwrapping of neested promises. Use `mcat`
(or `mapcat`) for for handle one level unwrapping.

Aliases: `map`.


### `mcat`

Returns a new promise instance which will be completed with the same value as the
returning promise instance of applying a function to eventually successfully resolved
promise. The function **must** return a promise instance.

```clojure
(def result
  (->> (p/resolved 1)
       (p/mapcat (fn [v] (p/resolved (inc v))))))

@result
;; => 2
```

Aliases: `mapcat`.


### `hmap`

Applies a function in the same way as `fmap` to both possible results: `value` and
`exception`. It returns a promise that will be completed with the return value of the
function.

```clojure
(def result
  (->> (p/resolved 1)
       (p/hmap (fn [v _] (inc v)))))

@result
;; => 2
```


### `hcat`

Applies a function in the same way as `mcat` to both possible results: `value` and
`exception`. Funciton **must** return a promise. It returns a mirrored promise returned by
the applied function.

```clojure
(def result
  (->> (p/resolved 1)
       (p/hmap (fn [v _] (p/resolved (inc v))))))

@result
;; => 2
```

## Error handling

One of the advantages of using the promise abstraction is that it
natively has a notion of errors, so you don't need to reinvent it. If
some computation inside the composed promise chain/pipeline raises an
exception, the pipeline short-circuits and propagates the exception to
the last promise in the chain.

### `catch`

The `catch` function adds a new handler to the promise chain that will
be called when any of the previous promises in the chain are rejected
or an exception is raised. The `catch` function also returns a promise
that will be resolved or rejected depending on what happens inside the
catch handler.

Let see an example:

```clojure
(-> (p/rejected (ex-info "error" nil))
    (p/catch (fn [error]
               (prn "erorr:" erorr))))
```

You also can filter by predicate or by class the possible exception to
handle:

```clojure
(-> (p/rejected (ex-info "error" nil))
    (p/catch clojure.lang.ExceptionInfo
             (fn [error]
               (prn "erorr:" erorr))))
```

Or

```clojure
(defn ex-info?
  [o]
  (instance? clojure.lang.ExceptionInfo o))

(-> (p/rejected (ex-info "error" nil))
    (p/catch ex-info? (fn [error]
                        (prn "erorr:" erorr))))
```

### `merr`

In the same way as `catch` allow apply a function to the promise rejection. This function
has the parameters in inverse order, intended to be used with `->>` in the same way as
`fmap` and `mcat`.

The function **must** return a promise instance,

```clojure
(def result
  (->> (p/rejected (ex-info "hello" nil))
       (p/merr (fn [error]
                 (p/resolved (ex-message error))))))

@result
;; => "hello"
```


## Composition

Promse exposes a set of helpers and syntactic abstractions (macros) for facilitate working
with compositions of asynchronous computations.


### `let`

The _promesa_ library comes with convenient syntactic-sugar that allows you to create a
composition that looks like synchronous code while using the Clojure's familiar `let`
syntax:

```clojure
(def result
  (p/let [x (p/delay 1000 42)
          y (p/delay 1200 41)
          z 2]
    (+ x y z)))

@result
;; => 85
```

The `let` macro behaves almost identically to Clojure's `let` with the exception that it
always returns a promise. If an error occurs at any step, the entire composition will be
short-circuited, returning exceptionally resolved promise.

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


### `do`

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

In fact, the `let` macro uses `do` internally.


### `all`

In some circumstances you will want wait for completion of several promises at the same
time. To help with that, _promesa_ also provides the `all` helper.

```clojure
(-> (p/all [(do-some-io)
            (do-some-other-io)])
    (p/then (fn [[result1 result2]]
              (do-something-with-results result1 result2))))
```

Is up to the user properly handle concurrency, `p/all` does not lauches additional threads
of execution.


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
of the executed functions. If they does syncronous work, all the code
will be executed serially, almost identical to the standard let. Is
the user responsability of the final execution model.


### `any`

There are also circumstances where you only want the first
successfully resolved promise. For this case, you can use the `any`
combinator:

```clojure
(def result
  (p/any [(p/delay 125 1)
          (p/delay 200 2)
          (p/delay 120 3)]))

@result
;; => 3
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

## Delays and Timeouts.

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


## Promise chaining & execution model

Let's try to understand how promise chained functions are executed and
how they interact with platform threads. **This section is mainly
affects the **JVM**.

Lets consider this example:

```clojure
@(->> (p/delay 100 1)
      (p/map inc)
      (p/map inc))
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

@(->> (p/delay 100 1)
      (p/map :default inc)
      (p/map :default inc))
;; => 3
```

This will schedule a separate task for each chained callback, making
the whole system more responsive because you are no longer executing
big blocking functions; instead you are executing many small tasks.

The `:default` keyword will resolve to `px/*default-executor*`, that
is a `ForkJoinPool` instance that is highly optimized for lots of
small tasks.

On JDK19 with Preview enabled you will also have the
`px/*vthread-executor*` (`:vthread` keyword can be used) that is an
instance of *Virtual Thread per task* executor.


## Performance overhead

_promesa_ is a lightweight abstraction built on top of native
facilities (`CompletableFuture` in the JVM and `js/Promise` in
JavaScript). Internally we make heavy use of protocols in order to
expose a polymorphic and user friendly API, and this has little
overhead on top of raw usage of `CompletableFuture` or `Promise`.

For performance sensitive code, prefer using functions designed to be
used for `->>`; they are more optimized because they don't perform
automatic unwrapping handling (unlike the `then` or `handle`
functions). This applies only to CLJ, on CLJS all they work the same
way because of how the underlying implementation works.


[0]: https://docs.oracle.com/en/java/javase/19/docs/api/java.base/java/util/concurrent/CompletableFuture.html
[1]: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise
