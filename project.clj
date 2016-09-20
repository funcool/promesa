(defproject funcool/promesa "1.6.0-SNAPSHOT"
  :description "A promise library for ClojureScript"
  :url "https://github.com/funcool/promesa"
  :license {:name "BSD (2 Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.9.229" :scope "provided"]
                 [org.clojure/core.async "0.2.391" :scope "provided"]]
  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}
  :source-paths ["src" "assets"]
  :test-paths ["test"]
  :jar-exclusions [#"\.swp|\.swo|user.clj"]

  :profiles
  {:dev
   {:dependencies [[com.cognitect/transit-cljs "0.8.239"]]
    :source-paths ["dev/src"]
    :codeina {:sources ["src"]
              :reader :clojure
              :target "doc/dist/latest/api"}
    :plugins [[funcool/codeina "0.5.0"]
              [lein-ancient "0.6.10" :exclusions [org.clojure/tools.reader]]]}})
