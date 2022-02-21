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

See the complete [documentation](https://funcool.github.io/promesa/latest/) for
more detailed information.

