# promesa #

[![Clojars Project](http://clojars.org/funcool/promesa/latest-version.svg)](http://clojars.org/funcool/promesa)

A promise library for ClojureScript.

This library offers [bluebird](https://github.com/petkaantonov/bluebird/), a full featured promise
javascript library with additional steroids.


## Install ##

The simplest way to use _promesa_ library in a Clojure project is by including
it as a dependency in your *_project.clj_*:

```clojure
[funcool/promise "0.1.0-SNAPSHOT"]
```

## Getting Started ##

This library gives you access tu bluebird promise library with full support for
clojurescript advanced compilation.

For start using it, just require it like any other clojurescript ns:

```clojure
(ns yourapp.core
  (:require [org.bluebird]))

(def p (js/Promise. (fn [resolve reject]
                      (resolve 1))))
(.then p (fn [v]
           (.log js/console v)))
;; => 1
```

## Additional steroids ##

Moreover, this library inclues some extra features.

### Ligweight wrapper ###

The promise library comes with lighweight abstraction for make use primises
more idiomatic and more in clojure style.

Let start creating a promise:

```clojure
(require '[promesa.core :as p])

;; Create a fulfilled promise
(p/promise 1)

;; Create a promise from callback
(p/promise (fn [resolve reject]
             (resolve 1)))
```

Also, you can chain computations with `then`:

```clojure
(-> (p/promise 1)
    (p/then (fn [v] (inc v)))
    (p/then (fn [v] (* v 2))))
```


Catch the exceptions with `catch`:

```clojure
(-> (p/promise (fn [_ reject] (reject "test error")))
    (p/carch (fn [error] (.log js/console error))))
;; will print "test error" in the console
```

If you want kwnow all supported methods, please read the [api documentation](api/).


### Promise as a Monad ###

In asynchronous environments, specially in javascript, promises is one of the most used
primitives for make composition of async computations. But using promises _as is_ not solves
you from the callback hell.

The upcoming ES7 standard will introduce a sugar syntax for work with promises in a painless
way: using new `async` y `await` keywords. You can read more about that here:
http://jakearchibald.com/2014/es7-async-functions/ and
http://pouchdb.com/2015/03/05/taming-the-async-beast-with-es7.html

That's ok, but the purposed sugar syntax is bound only to promises. That limits extend that
sugar syntax with other abstractions.

This library uses the [cats](https://github.com/funcool/cats) monad abstractions for create
promise and use it `mlet` macro as sugar syntax for async computations composition:


```clojure
(require '[cats.core :as m])

(defn do-stuff []
  (m/mlet [x (p/promise 1)
           _ (p/delay 1000)
           y (p/promise 2)]
    (m/return (+ x y))))

(p/then (do-stuff) (fn [v] (println v)))
```

And, so, will be look the code using plain javascript and bluebird promises:

```javascript
function doStuff() {
    return Promise.resolve(1)
        .then(function(v) {
            retuen Promise.delay(v, 1000);
        })
        .then(function(v) {
            return Promise.resolve([v, 2]);
        })
        .then(function(v) {
           var x = v[0];
           var y = v[1];
           return x + y;
       });
}

doStuff().then(function(v) { console.log(v); });
```

You may observe that the clojurescript version looks like synchronous code, in same
way as you will use the ES7 `async` and `await` syntax. The difference is that the `mlet`
sugar syntax works on an monadic abstraction, that allows build other implementatations
like this library is doing for other types of compositions
