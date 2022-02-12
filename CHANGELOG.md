# Changelog #

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
