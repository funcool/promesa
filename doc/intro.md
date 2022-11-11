# Getting Started

A promise library & concurency toolkit for Clojure and ClojureScript.

## Setting up the dependency

deps.edn:

```clojure
funcool/promesa {:mvn/version "9.0.518"}
```

Leiningen:

```clojure
[funcool/promesa "9.0.518"]
```

## Checking on the REPL

```clojure
(require '[promesa.core :as p])

(->> (p/promise 1)
     (p/map inc)
     (deref)
;; => 2
```
