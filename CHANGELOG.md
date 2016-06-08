# Changelog #

## Version 1.3.0 ##

Date: 2016-06-08

- Update bluebird to 3.4.0
- Improve internal impl (now splitted in few namespaces).
- Fix bug in `finally` combinator function.
- Add `do*` promise constructor (analogous to `Promise.attempt`).


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
