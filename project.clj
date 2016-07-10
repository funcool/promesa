(defproject funcool/promesa "1.4.0"
  :description "A promise library for ClojureScript"
  :url "https://github.com/funcool/promesa"
  :license {:name "BSD (2 Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.9.36" :scope "provided"]]
  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}
  :source-paths ["src" "assets"]
  :test-paths ["test"]
  :jar-exclusions [#"\.swp|\.swo|user.clj"]

  :profiles
  {:dev
   {:source-paths ["dev"]
    :codeina {:sources ["src"]
              :reader :clojure
              :target "doc/dist/latest/api"}
    :plugins [[funcool/codeina "0.4.0"]
              [lein-ancient "0.6.10" :exclusions [org.clojure/tools.reader]]]}})
