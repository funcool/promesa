# Changelog #

## Version 11.0.xx

**Important Notice**

In this version, one of the problems that existed since the birth of this library has been
corrected, related to the fact that the default implementation of js/Promise makes it
impossible to nest promises. This fact has caused the library in the first place to have
had a divergence with its counterpart in CLJ/JVM and, secondly, to the fact that
mapcat-type operators could not be correctly implemented, making them practically useless.

With this version, the problem is fixed and while it cannot technically be considered a
backwards compatibility break, some operators with promises will not function
identically. Basically the magical auto-unwrapping of promises is gone.

Function docstrings have already been explicit about what they are expected to return, so
if you've been relying on the js/Promise implementation detail in CLJS, it's possible that
some pieces of code are broken, because now several operators already work the same way in
CLJ and CLJS.

It should be said that only a set of operators has been really affected by the change. The
Promise library exposes two styles of APIs:

- one that is designed to be used with the `->` (`then`, `catch`, `handle`, `finally`)
  macro and is intended to emulate the behavior of js/Promise and that api hasn't changed,
  keep going working as it worked;
- and the second style of api designed to be used with the `->>` macro (the `fmap`,
  `mcat`, `hmap`, `hcat`, `fnly` functions, where already the contract was more strict);
  This is where this fix may have really affected the most since it makes it even stricter
  regarding the return values of the callbacks. As I have already commented before, the
  docstrings already had all this specified for a few versions.
  

**Relevant changes:**

- Add internal Promise implementation that allows promise inspection. That enables the
  `pending?` `done?` `resolved?` and `rejected?`  predicates to be used from CLJS
- Add `IDeref` implementation to promises (`@promise` or `(deref promise)` now can be used
  on CLJS in a nonblocking way, if promise is not fulfilled, `nil` will be returned)
- Expose almost all CSP API to CLJS (with the exception of go macros, because the JS
  runtime has no vthreads)
- Fix many bugs on current CSP impl
- Add first class support for errors on channels
- Add metadata support for channels


## Version 10.0.594

- Enable creation of virtual threads on `promesa.exec/thread`
  low-level macro.
- Change internal vars naming for checking if the Virtual Threads
  ara available.
- Properly unwrap completion exception on finally.
- Define cljs Scheduler type statically, with deftype instead of reify
  (bacause of https://clojure.atlassian.net/browse/CLJS-3207)
- Update github actions

## Version 10.0.582

- Add `once-buffer` (analogous to core.async `promise-buffer`).
- Fix incorrect handling of terminating transducers on channels.

## Version 10.0.575

- Add `expanding-buffer` as more flexible alternative to `fixed-buffer`.
- Use `expanding-buffer` by default on channels initialized with
  buffer size.
- Fix unexpected exception when channel is used with mapcat
  transducer.

## Version 10.0.571

- Revert deprecation of `then'` and `chain'`
- Add **experimental** `promesa.core/wait-all` helper


## Version 10.0.570

- Add `promesa.exec.csp/mult*` alternative multiplexer constructor
  more similar to the `clojure.core.async/mult` one
- Fix `promesa.exec/run!` and `promesa.exec/submit!`; make them
  respect the `promesa.exec/*default-executor*` dynamic var value when
  no explicit executor is provided
- Improve internal implementation of `promesa.core/loop` macro (making
  it a little bit more efficient
- Add general purpose `promesa.core/await!` and `promesa.core/await`
  helpers that serves for blocking current thread waiting the
  termination/completion of some resource; (**EXPERIMENTAL**, **JVM
  only**, look at ref-docs for details). Implemented by default for
  `Thread`, `CountDownLatch`, `CompletionStage` and
  `CompletableFuture`.
- Reimplement `promesa.exec.csp/pipe` using promise API; removing
  internal go-block and make it more friendly to non-vthread JVM.
- Reimplement `promesa.exec.csp/onto-chan!` using promise API;
  removing internal go-block and make it more friendly to non-vthread
  JVM.

BREAKING CHANGE ON EXPERIMENTAL CSP API:

- Make `put!` as blocking API, and move future returning api to `put`.
- Make `take!` as blocking API, and move future returning api to `take`.


## Version 10.0.544

**This release includes internal protocols changes that breaks
compatibility. If you are using only public API, you should not be
affected.**

- Deprecate undocumented `promesa.core/chain'` function
- Deprecate undocumented `promesa.core/catch'` function
- Deprecate `promesa.core/error` alias to `catch`
- Improve efficiency in the `promesa.core/any` implementation
- Remove undocumented `promesa.core/err` alias.
- Add `promesa.core/hmap` and `promesa.core/hcat` functions (in the
  same family as `promesa.core/handle` but with arguments in inverse order and no
  automatic unwrapping)
- Add `promesa.core/fmap` convenience alias for `promesa.core/map`
- Add `promesa.core/merr` (inverse ordered arguments and no automatic unwrapping
  version of `promesa.core/catch`)
- Fix `promesa.exec.csp/!<` arguments (thanks to @alexandergunnarson)
- Add `promesa.exec.csp/go-chan` convenience macro (thanks to
  @alexandergunnarson for the suggestion)
- Add `promesa.exec/thread?` function
- Update documentation related to all new related functions


## Version 9.2.542

- Add `promesa.core/mcat`, a shorter alias for `mapcat`
- Add `promesa.core/hmap`, a shorter alias for `handle` with inverted
  arguments (for `->>`)
- Add `promesa.core/fnly`, a shorter alias for `finally` with inverted
  arguments (for `->>`)
- Add `promesa.exec.bulkhead/bulkhead?` predicate.
- Add arity 0 for `promesa.exec/interrupt!` function (interrupts the
  current thread).


## Version 9.2.541

BREAKING CHANGES:

They are very recent additions and may be considered experimental, but
still worth mentioning as breaking change:

- Rename `px/thread-interrupted?` to `px/interrupted?`
- Rename `px/interrupt-thread!` to `px/interrupt!`
- Move   `promesa.exec.csp/sleep` to `px/sleep`

Other changes:

- Add `px/shutdown?` predicate
- Make `px/sleep` accept number (in milliseconds) and duration instance
- Make the `px/shutdown!` and `px/shutdown-now!` CLJ only


## Version 9.1.540

- Minor consistency fix on `px/thread` macro parameters
- Fix consistency issues on naming for internal thread factory helpers
  (this may **BREAKING CHANGE** if you are using the internal factory
  helpers).

## Version 9.1.539

- Set the `px/thread` by default the daemon flag to true


## Version 9.1.538

- Add `px/current-thread` helper function
- Add `px/thread-interrupted?` helper function
- Add `px/interrupt-thread!` helper function
- Add `px/join!` helper function
- Add `px/thread-id` helper function
- Add `px/thread` low-level macro for create non-pooled threads


## Version 9.1.536

- Assign default parallelism to scheduled executor (based on CPUs).
- Add channels & csp pattern (experimental).
- Restructurate documentation and improve many docstrings.

## Version 9.0.518

- Forward dynamic bindings for `pmap`.
- Forward dynamic bindings for `with-dispatch`
- Add minor internal adjustments to bulkhead.


## Version 9.0.507

- Replace previously introduced `ConcurrencyLimiter` (java impl) with
  `Bulkhead` (100% clojure impl; cljs not suported but contributions
  welcome to port it to cljs if someone consider it can be useful).
- Fix `promesa.core/wrap`; it now only wraps if the value is not a promise instance
- Add `promesa.exec/pmap`; a simplified `clojure.core/pmap` analogous
  function that allows use a user specified executor (thanks to the
  dynamic vars) (EXPERIMENTAL)
- Add `promesa.exec/with-executor` helper macro for easily bind a new
  value to the `*default-executor*` and optionally close it on lexical
  scope ending (EXPERIMENTAL)


## Version 9.0.494

Date: 2022-10-31

- Add missing classes to repository


## Version 9.0.493

Date: 2022-10-31

- Minor documentation updates.
- Make it work again as git dep.


## Version 9.0.489

Date: 2022-10-18

- Minor fixes on concurrency limiter hook fns.


## Version 9.0.488

Date: 2022-10-17

- Add more usability fixes on concurrencly limiter



## Version 9.0.486

Date: 2022-10-17

- Make usability and observability improvements to concurrency limiter


## Version 9.0.485

Date: 2022-10-17

- Exclude limiter from CLJS.
- Make `-run!` protocol method be implemented in terms of `-submit!`.
- Make ConcurrencyLimiter implement the IExecutor protocol.


## Version 9.0.481

Date: 2022-10-17

- Rewrite ConcurrencyLimiter for make it more clojure frendly.
- Expose the **experimental** API for the ConcurrencyLimiter class.
- Officially drop support for JDK <= 8


## Version 9.0.477

Date: 2022-10-15

- Change the license from BSD-2 to MPL-2.0
- Remove from CLJS: IState protocol and all related public API; that
  functions already does not works on cljs becuase of platform promise
  limitations, so we decided to just exclude them from cljs and saves
  some bytes
- Simplify `pending?` impl in JVM
- Add experimental (JVM only) ConcurrencyLimiter class

## Version 9.0.471

Date: 2022-10-09

- Fix warnings on cljs compilation
- Fix issues with 0 arg on thread factory functions
- Minor fix on `p/thread` macro, now it uses unbounded cached thread
  pool instead of the default one.

## Version 9.0.470

Date: 2022-10-06

Bug fixes:

- Fix reader conditional typo that prevents
  `default-forkjoin-thread-factory` to be defined (thansk to @mainej).

## Version 9.0.466

Date: 20220-10-06

Changes `promesa.core` ns:

- Add `thread-call` helper.
- Add `thread` macro (analogous to the `clojure.core.async/thread`)
- Add `thread-call` function (analogous to the `clojure.core.async/thread-call`)
- Add `vthread` macro (only on JDK19 with Preview enabled).
- Add `vthread-call` function (only on JDK19 with Preview enabled).
- Make the `future` and `thread` macros aware of var bindings.
- Make the `create` promise factory catch all exceptions.

Changes to `promesa.exec` ns:

- Add `thread-per-task-executor` executor factory functon (JDK19 with Preview).
- Add `vthread-per-task-executor` executor factory functon (JDK19 with Preview).

## Version 9.0.462

Date: 2022-10-02

BREAKING CHANGES:

- The `promesa.exec/counted-thread-factory` is renamed to
  `promesa.exec/default-thread-factory` and the call signature is
  changed.
- The `promesa.exec/forkjoin-named-thread-factory` has is renamed to
  `promesa.exec/default-forkjoin-thread-factory` and the call
  signature is changed.
- The `future` macro has changed to does not automatically unwrap
  returned promises. This is change is motivated for make it behave in
  the same way as `clojure.core/future` as `promesa.core/future`
  expects to be a replacement for it.

Enhancements:

- Deprecate all the `*-pool` executors constructors in favour of new
  variants called with the same name and the `-executor` prefix. The
  new constructor functions are all uniform with call signature.
- Add promise aware, simplified version of `doseq` (thanks to @borkdude).
- Add proper docstring for `with-dispatch` macro.

Bug fixes:

- Make the completable-future returned by `p/future` macro
  trully cancellable.


## Version 8.0.450

Date: 2022-02-24

- Add `with-redefs` macro to clj-kondo config (thanks to @eccentric-j)


## Version 8.0.446

Date: 2022-02-23

- Make `promise?` to check for IPromise protocol instead of concrete types. Now it should
  more easy extend promise to other promise like types.
- Rename `promise.core/do!` macro to `promise.core/do` (backward compatible, previous
  macro still in the codebase)
- Add promise aware `with-redefs` macro (thanks to @eccentric-j)


## Version 7.0.444

Date: 2022-02-22

- REVERT: Make `promise?` to check for IPromise protocol instead of concrete
  types. Because the impl was wrong.


## Version 7.0.443

Date: 2022-02-22

- Add better builtin clj-kondo config (thanks to @wilkerlucio)
- Make `promise?` to check for IPromise protocol instead of concrete
  types.
- Add `promesa.exec/with-dispatch` macro.


## Version 7.0.437

Date: 2022-02-21

- Make the `bind` function behave as it should behave (like bind and
  not being `then` alias). **This is technically a breaking change**,
  the `bind` function should have been implemented in terms of `bind`
  operation and not be an alias for `then`.

## Version 6.1.436

Date: 2022-02-16

- Add builtin clj-kondo config (thanks to @wilkerlucio)


## Version 6.1.434

Date: 2022-02-12

- Add `as->` threading macro (thanks to @wilkerlucio)


## Version 6.1.431

Date: 2022-02-03

- Add `->` and `->>` threading macros (thanks to @wilkerlucio)



## Version 6.0.2

Date: 2021-06-01

- Fix `timeout` internal timeout handling.
- Fix nil and Object handling on JVM (make it work in the same way as
  in CLJS)


## Version 6.0.1

Date: 2021-05-13

- Fix wrong params handling on `scheduled-pool` function.


## Version 6.0.0

Date: 2020-10-01

Relevant changes:

- Add missing `-then` impl for `default` object (fixes issues of
  `promesa.core/then` chain function with promises that does not
  inherito from `js/Promise`).

- Remove already deprecated for a while the `alet` alias to `let`
  macro (the migration should be a simple find-and-replace).

- Add forkjoin-pool and factory helpers to `promesa.exec` ns.



## Version 5.1.0

Date: 2020-02-05

Relevant changes:

- Reimplement `loop/recur` (make its scheduling extensible and by
  default it uses the common thread pool for scheduling body execution
  for prevet stack overflow).
- Add `promesa.core/*loop-run-fn*` dynamic var for cases when you need
  customize where the loop/recur body exection is scheduled.
- Fix many reflection warnings.


## Version 5.0.0

Date: 2020-01-10

Relevant changes:

- Internal protocol improvements.
- Enable automatic flatten on `catch` and `handle` (enabling the same
  semantic than `then` function).
- Add `catch'` variant for cases when you sure that funcion always
  return a plain value (and not promise).
- Bug fix on `promesa.core/plet`.
- Wrap the body of `promesa.core/let` with `promesa.core/do!` macro.
- Wrap the body of `promesa.core/plet` with `promesa.core/do!` macro.


## Version 4.0.2 ##

Date: 2019-10-03

- Invalid 4.0.1 release.


## Version 4.0.1 ##

Date: 2019-10-03

- Minor code simplication
- cljdoc integration


## Version 4.0.0 ##

Date: 2019-10-01

Relevant changes (many **breaking changes** that affects functions
and macros that are not heavily used):

- Remove the ability to create a promise using factory function with
  `promise` constructor (now this responsability is delegated to the
  `create` function, see below).

- Remove the old `do*` macro.
- Add `do!` macro (that should have been the `do*` from the
  begining). It treats each individual expression as a expression that
  evaluates to promise and executes serially awaiting each
  expression. Returns a promise resolved to the result of the last
  expression, ignoring all intermediate results.

```clojure
(require '[promesa.core :as p])

(p/do! (expr1)
       (expr2)
       (expr3))

;; That is roughtly equivalent to:

(p/alet [_ (expr1)
         _ (expr2)]
  (expr3))
```

- Refactor execution strategy: before this change all the chained
  callback functions (with `map`, `then`, etc..) they were running in
  a separated (async) microtask (forkJoinPool on the jvm). Now
  **promesa** does not makes any asumption about this and delegate
  this decision to the user of this library.

  **What are the implications for the end user?:** In terms of api
  changes, **nothing**; all the public api is the same. The main
  change consists in the execution semantics. Now all the chained
  functions (by default) will be executed in the calling/resolver
  thread instead of a new task for each step. This will leverage a
  better performance and less latency on all chain execution.

  Also, **promesa** exposes additional arities to `map`, `then`,
  `bind` and `mapcat` for provide a custom executor service if you
  need it.

  The `promise` and `deferred` (read more below) promise constructors
  also accepts a new arity for specify the executor where evaluate the
  factory function or promise resolution (by default is in the calling
  thread).

  **The execution semantic changes are only relevant on the JVM, on
  cljs nothing is changed.**

- Rewrite `finally` function: now receives a promise and a function
  (potentiall side-effectful) that will receive resolved value as
  first argument if the promise is resolved or exception as second
  argument if promise is rejected. The return value is ignored. It
  always returns the same promise (like identity function).

- Remove 0 arity from `promise` function (now is delegated to `deferred`)
- Remove `schedule` function from `promesa.core` (replaced by `promesa.exec/schedule`).
- Remove `extend-promise!` from `promesa.core` (still available in `promesa.impl`).
- Remove `set-default-promise!` helper (the user can do the same without the helper).
- Remove `attempt` function (not useful).
- Remove `branch` function (not useful).

New features and not breaking changes and fixes:

- Add `create` promise constructor, a facility for create a promise
  using a factory function (before this is done passing a function to
  `promise`).
- Add `deferred` promise constructor. The main purpose of this
  constructor is creating an empty promise ready to be resolved or
  rejected externally (using `resolve!` and `reject!`).
- Add `handle` chain function: is some kind of combination of `then'`
  and `catch`. It chains a function to be executed when the promise is
  either normally or rejected (with first argument with resolved value
  (or nil) and second argument with the exception (or nil) if promise
  is rejected). Returns the promise resolved with the return value of
  the chained function. Does not flatten the result.
- Add `then'` chain function. Is a variant of `then` function that
  does not flatten the result (a more performant variant).
- Add `chain'` chain function helper. Is a `chain` variant that does
  not flatten the result (a more performant variant).
- Rename `alet` to `let` (`alet` is stil awailable as alias for
  backward compatibility).
- Add `plet` as syntactic abstraction/sugar for `all` composition
  operator.
- Add `race` composition operator.
- Add `run!` function (a promise aware `run!` variant).
- Add `promesa.exec` namespace with Executors & Schedulers abstractions.
- Add `future` macro (analogous to `clojure.core/future` that returns
  promise instance instead of Future, also works in cljs) that uses
  `promesa.exec` behind the schenes.
- Improve `let` macro making it safe to synchronos exception that can
  be raised from the first evaluated expression. Now all exception
  raised inside `let` returs properly rejected promise.
- Add `loop/recur` syntax abstraction.


## Version 3.0.0 ##

Date: 2019-08-21

This is a **breaking change** release; even though the majority of
public (not experimental) api is not affected. Relevant changes are:

- Remove `promesa.async` and `promesa.async-cljs` namespaces. They was
  experimental and finally they don't demonstrate to be useful in
  comparison to the complexity that they introduce.

Other changes:

- Allow use promise on GraalVM native compilation.
- Make promesa compatible with thenable objects (see issue #66).
- Simplified `alet` macro implementation; it's no longer needs `await`
  for wait promise binding resolution.


## Version 2.0.1 ##

Date: 2019-03-30

Yo now can create an empty promise (without a factory function) and
resolve or reject it using the new functions: `resolve!` `reject!`.

Example:

```clojure
(require '[promesa.core :as p])

(let [pr (p/promise)]
  ;; do something
  (p/resolve! pr 2))
```


## Version 2.0.0 ##

Date: 2019-02-19

This is a **breaking change** release. Finally bluebird is gone in
favour of using the ES6 builtin Promise object. This removes the
overhead (in size) of the additional external library.

The reason of using bluebird initially was because native promises
performed badly, but in new versions javascript engines the
performance and memory usage is improved significantly. In any case
you can still use the bluebird if you want, thanks to the new
functions:

- `set-default-promise!`: enables the user setting up a custom promise
  constructor as default one for *promesa* library.
- `extend-promise!`: enables the user to use a custom promise
  implementation with *promesa* library abstractions.

Other (also probably breaking) changes:

- `timeout` is now implemented in terms of internal scheduler
  facilities (bluebird impl was used previously) and it is now
  available for clojure (jvm).
- `any` is reimplemented in clj/cljs and now accepts an additional
  argument for setting the default return value if promise is
  resolved. If default value is not provided, an ExceptionInfo will be
  throwed.


## Version 1.9.0 ##

Date: 2018-08-03

- Update bluebird bundle to 3.5.0
- Update dependencies.
- Fix some issues on async macro (jvm only).
- Fix issue with interop on alet macros.


## Version 1.8.1 ##

Date: 2017-04-20

- Remove `_` character from internal assets directory. That fixes incompatibilities
  with cordova/android build tools.


## Version 1.8.0 ##

Date: 2017-02-21

- Update bluebird to 3.4.7
- Fix wrong impl of sync introspection (on cljs).
- Fix wrong impl of delay function (on cljs).
- Fix behavior difference of `then` function on clj in respect to cljs.
- Avoid compiler warnings caused by .finally/.catch
- Add safer await detection on `async` macro (on cljs).


## Version 1.7.0 ##

Date: 2016-12-18

- Fix clojure `finally` implementation.
- Minor internal refactor. Public api should be fully backward compatible.


## Version 1.6.0 ##

Date: 2016-11-02

- Add `async` macro that uses `core.async` machinary in order to build `go`
  like macro and allow to have fully async/await syntax.
- Update bluebird to 3.4.6 (cljs underlying promise impl library).
- Add support experimental support for native promises and other thenables.
- Remove usage of `clj->js` and `js->clj` functions.


## Version 1.5.0 ##

Date: 2016-08-18

- Make promise aware of clojure dynamic binding context (clj only).


## Version 1.4.0 ##

Date: 2016-07-10

- Update bluebird to 3.4.1
- Add missing Promise alias on externs (that fixes unexpected
  exceptions on advanced compilation modes).


## Version 1.3.1 ##

Date: 2016-06-08

- Remove reflection warnings.


## Version 1.3.0 ##

Date: 2016-06-08

- Update bluebird to 3.4.0
- Improve internal impl (now splitted in few namespaces).
- Fix bug in `finally` combinator function.
- Add `do*` promise constructor (analogous to `Promise.attempt`).
- Remove `promise.monad` namespace.


## Version 1.2.0 ##

Date: 2016-05-20

- Add more bluebird externs.
- Docstrings improvements.
- Update bluebird to 3.3.5


## Version 1.1.1 ##

Date: 2016-03-19

- Fix wrong call on IPrintWriter impl.
- Add noConflict to externs.
- Update cljs compiler to 1.8.34.


## Version 1.1.0 ##

Date: 2016-03-18

- Add `err` and `error` alias as `catch` analougous function
  that has the parameters inverted in the same way as `map`
  and `mapcat`.


## Version 1.0.0 ##

Date: 2016-03-17

- Add scheduler abstraction.
- Add `map` function.
- Add `mapcat` function.
- Update bluebird to 3.3.4.
- Remove wrapping logic from -bind impl.


## Version 0.8.1 ##

Date: 2016-02-13

- Remove cats imports from core ns that causes
  import exception.


## Version 0.8.0 ##

Date: 2016-02-13

- BREAKING CHANGE: Cats is no longer requred dependency.
  If you want use it you need import the `promesa.monad` ns.
- Update bluebird to 3.3.0 (cljs).
- Add bultin support for `async/await` like syntax.


## Version 0.7.0 ##

Date: 2016-01-08

- Update bluebird to 3.1.1 (cljs).
- Add better externs (with type annotations) (cljs).
- Update cats dependency to 1.2.1.


## Version 0.6.0 ##

Date: 2015-12-03

Important changes:

- Add clojure support (only with JDK8).
  Tha implies major code refactor. The public api should be mostly
  backwad compatible but it is possible regressions.

Other changes:

- Update the cljs compiler version to 1.7.189
- Update cats library to 1.2.0


## Version 0.5.1 ##

Date: 2015-09-27

- Add 'branch' combinator


## Version 0.5.0 ##

Date: 2015-09-18

- Update cats to 1.0.0
- Adapt code to cats 1.0.0 breaking changes.
- Add more tests.
- Remove spread operator beacuse it is no longer needed (you can use clojure
  destructuring with `then` combinator.
- Start using the `-name` protocol naming convention.
- Update bluebird to 2.10.0


## Version 0.4.0 ##

Date: 2015-08-18

- Update cats dependency to 0.6.1


## Version 0.3.0 ##

Date: 2015-08-02

- Update bluebird version to 2.9.34
- Update cljs compiler version to 1.7.28
- Start using cljs compiler own compilation facilities
  instead of lein-cljsbuld.
- Now requires the clojurescript >= 1.7.28


## Version 0.2.0 ##

Date: 2015-07-18

- Remove all method related to cancellable promises.
- Implement everything in terms of protocols.
- Update bluebird version to 2.9.33


## Version 0.1.3 ##

Date: 2015-06-13

- Go back to use leiningen.
- Update bluebird version to 2.9.27


## Version 0.1.2 ##

Date: 2015-05-16

- Update bluebird version to 2.9.25
- Start using boot instead of leiningen


## Version 0.1.1 ##

Date: 2015-04-16

- Update bluebird version to 2.9.23


## Version 0.1.0 ##

Date: 2015-03-28

- First relase.
