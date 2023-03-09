# promesa #

[![Clojars Project](http://clojars.org/funcool/promesa/latest-version.svg)](http://clojars.org/funcool/promesa)

A promise library & concurrency toolkit for Clojure and ClojureScript.

This library exposes a bunch of usefull syntactic abstractions that
will considerably simplify to work with promises (in a very similar
way as you will do it in JS with async/await) and many helpers from
executors to concurrency patterns (bulkhead & CSP). With 0 runtime
external dependencies.

Here you can look a detailed [documentation][1].


## Getting Started

deps.edn:

```clojure
funcool/promesa {:mvn/version "10.0.594"}
```

Leiningen:

```clojure
[funcool/promesa "10.0.594"]
```

## On the REPL

```clojure
(require '[promesa.core :as p])

(->> (p/promise 1)
     (p/map inc)
     (deref)
;; => 2
```

NOTE: example only work on JVM because the evident lack of blocking
primitives on JS runtime.

## Contributing

If you miss something, feel free to open an issue for a discussion. If
there is a clear use case for the proposed enhacement, the PR will be
more thank welcome.

## Testing

Run the Clojure (.clj) tests:

``` shell
clojure -X:dev:test
```

Run the ClojureScript (.cljs) tests:

``` shell
npm install
npm test
```

[1]: https://funcool.github.io/promesa/latest/
