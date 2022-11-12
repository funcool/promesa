# Getting Started

A promise library & concurency toolkit for Clojure and ClojureScript.

## Setting up the dependency

deps.edn:

```clojure
funcool/promesa {:mvn/version "9.1.536"}
```

Leiningen:

```clojure
[funcool/promesa "9.1.536"]
```

## Checking on the REPL

```clojure
(require '[promesa.core :as p])

(->> (p/promise 1)
     (p/map inc)
     (deref)
;; => 2
```
