(defproject funcool/promesa "0.2.0-SNAPSHOT"
  :description "A promise library for ClojureScript"
  :url "https://github.com/funcool/promise"
  :license {:name "BSD (2 Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}

  :dependencies [[funcool/cats "0.5.0"]]
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
                                  [org.clojure/clojurescript "0.0-3308"]]
                   :codeina {:sources ["src"]
                             :reader :clojurescript
                             :target "doc/dist/latest/api"}
                   :plugins [[lein-cljsbuild "1.0.6"]
                             [funcool/codeina "0.2.0"]
                             [lein-externs "0.1.3"]]}})
