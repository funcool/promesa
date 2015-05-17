(set-env!
 :source-paths #{"src"}
 :resource-paths #{"assets"}
 :dependencies '[[cats "0.4.0"]
                 [funcool/bootutils "0.1.0" :scope "test"]
                 [adzerk/boot-cljs "0.0-2814-3" :scope "test"]
                 [boot-cljs-test/node-runner "0.1.0" :scope "test"]
                 [org.clojure/clojurescript "0.0-3123"  :scope "test"]
                 [org.clojure/clojure "1.7.0-beta3" :scope "test"]
                 [org.clojure/clojurescript "0.0-3269" :scope "test"]])

(require
 '[adzerk.boot-cljs :refer [cljs]]
 '[funcool.bootutils :refer :all]
 '[boot-cljs-test.node-runner :refer :all])

(def +version+ "0.1.2")

(task-options!
 pom {:project     'funcool/promesa
      :version     +version+
      :description "A promise library for ClojureScript"
      :url         "https://github.com/funcool/promesa"
      :scm         {:url "https://github.com/funcool/promesa"}
      :license     {"BSD (2 Clause)" "http://opensource.org/licenses/BSD-2-Clause"}})

(deftask dev []
  (set-env! :source-paths #{"src" "test" "assets"})
  (comp (watch)
        (cljs-test-node-runner :namespaces '[promesa.core-tests])
        (cljs :source-map true :optimizations :none)
        (run-cljs-test)))
