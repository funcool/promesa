(ns build
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.tools.build.api :as b]
   [cljs.build.api :as api]))

(def lib 'funcool/promesa)
(def version (format "6.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom
   {:class-dir class-dir
    :lib lib
    :version version
    :basis basis
    :src-dirs ["src"]})

  (b/copy-dir
   {:src-dirs ["src" "resources"]
    :target-dir class-dir})

  (b/jar
   {:class-dir class-dir
    :jar-file jar-file}))

(defn clojars [_]
  (b/process
   {:command-args ["mvn"
                   "deploy:deploy-file"
                   (str "-Dfile=" jar-file)
                   "-DpomFile=target/classes/META-INF/maven/funcool/promesa/pom.xml"
                   "-DrepositoryId=clojars"
                   "-Durl=https://clojars.org/repo/"]}))

(def build-options
  {:main 'promesa.tests.main
   :output-to "target/tests.js"
   :output-dir "target/tests"
   :source-map "target/tests.js.map"
   :target :nodejs
   :optimizations :simple
   :pretty-print true
   :pseudo-names true
   :verbose true})

(defn build-cljs-tests
  [_]
  (api/build (api/inputs "src" "test") build-options))
