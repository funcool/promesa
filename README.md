# promesa #

[![Clojars Project](http://clojars.org/funcool/promesa/latest-version.svg)](http://clojars.org/funcool/promesa)

A lightweight promise/future library for Clojure & ClojureScript built
on top of native primitives (`js/Promise` on JS, and
`CompletableFuture` on JVM).

This library exposes a bunch of usefull syntactic abstractions that
will considerably simplify to work with promises (in a very similar
way as you will do it in JS with async/await).

```clojure
(ns some.namespace
  (:require [promesa.core :as p]))

(defn fetch-uuid-v1
  []
  (p/let [response (js/fetch "https://httpbin.org/uuid")]
    (.json response)))

(defn fetch-uuid-v2
  []
  (p/-> (js/fetch "https://httpbin.org/uuid") .json))
```

Here you have the [User
Guide](https://funcool.github.io/promesa/latest/user-guide.html) and
the [API documentation](https://funcool.github.io/promesa/latest/).


# Contributing

If you miss something, feel free to open an issue for a discussion. If
there is a clear use case for the proposed enhacement, the PR will be
more thank welcome.

## Testing

Run the Clojure (.clj) tests:

``` shell
clojure -Mdev -m promesa.tests.main
```

Run the ClojureScript (.cljs) tests:

``` shell
clj -Mdev tools.clj build
node out/tests.js
```
