(defproject funcool/promesa "0.4.0"
  :description "A promise library for ClojureScript"
  :url "https://github.com/funcool/promesa"
  :license {:name "BSD (2 Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [org.clojure/clojurescript "1.7.48" :scope "provided"]
                 [funcool/cats "0.6.1"]]
  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}

  :source-paths ["src" "assets"]
  :test-paths ["test"]
  :jar-exclusions [#"\.swp|\.swo|user.clj"]
  :codeina {:sources ["src"]
            :reader :clojurescript
            :target "doc/dist/latest/api"}
  :plugins [[funcool/codeina "0.3.0-SNAPSHOT"]
            [lein-externs "0.1.3"]])
