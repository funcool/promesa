(defproject funcool/promesa "0.1.3"
  :description "A promise library for ClojureScript"
  :url "https://github.com/funcool/promise"
  :license {:name "BSD (2 Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}

  :dependencies [[cats "0.5.0-SNAPSHOT"]]
  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}

  :source-paths ["src" "assets"]
  :test-paths ["test"]

  :cljsbuild {:test-commands {"test" ["node" "output/tests.js"]}
              :builds [{:id "dev"
                        :source-paths ["test" "src" "assets"]
                        :notify-command ["node" "output/tests.js"]
                        :compiler {:output-to "output/tests.js"
                                   :output-dir "output/out"
                                   :source-map true
                                   :static-fns true
                                   :cache-analysis false
                                   :main promesa.core-tests
                                   :optimizations :none
                                   :target :nodejs
                                   :pretty-print true}}]}

  :jar-exclusions [#"\.swp|\.swo|user.clj"]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/clojurescript "0.0-3308"]
                                  [funcool/cljs-testrunners "0.1.0-SNAPSHOT"]]
                   :codeina {:sources ["src"]
                             :language :clojurescript
                             :output-dir "doc/api"}
                   :plugins [[lein-cljsbuild "1.0.4"]
                             [funcool/codeina "0.1.0"
                              :exclusions [org.clojure/clojure]]
                             [lein-externs "0.1.3"]]}})
