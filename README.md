# promesa #

[![Clojars Project](http://clojars.org/funcool/promesa/latest-version.svg)](http://clojars.org/funcool/promesa)

A promise library & concurrency toolkit for Clojure and ClojureScript.

This library exposes a bunch of useful syntactic abstractions that
considerably simplify working with promises (in a very similar
way as you will do it in JS with async/await) and many helpers from
executors to concurrency patterns (bulkhead & CSP). With 0 runtime
external dependencies.

Here you can look a detailed [documentation][1].


## Getting Started

deps.edn:

```clojure
funcool/promesa {:mvn/version "11.0.678"}
```

Leiningen:

```clojure
[funcool/promesa "11.0.678"]
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
there is a clear use case for the proposed enhancement, the PR will be
more than welcome.

## Testing

Run the Clojure (.clj) tests:

``` shell
clojure -M:dev -m promesa.tests.main
```

Run the ClojureScript (.cljs) tests:

``` shell
corepack enable
corepack install
yarn install
yarn run test
```

Run the Babashka tests:

``` shell
bb test:bb
```

[1]: https://funcool.github.io/promesa/latest/
