(require '[clojure.java.shell :as shell]
         '[clojure.main])
(require '[rebel-readline.core]
         '[rebel-readline.clojure.main]
         '[rebel-readline.clojure.line-reader]
         '[rebel-readline.clojure.service.local]
         '[rebel-readline.cljs.service.local]
         '[rebel-readline.cljs.repl])
(require '[cljs.build.api :as api]
         '[cljs.repl :as repl]
         '[cljs.repl.node :as node])
(require '[badigeon.jar]
         '[badigeon.deploy])


(defmulti task first)

(defmethod task :default
  [args]
  (let [all-tasks  (-> task methods (dissoc :default) keys sort)
        interposed (->> all-tasks (interpose ", ") (apply str))]
    (println "Unknown or missing task. Choose one of:" interposed)
    (System/exit 1)))

(defmethod task "repl:jvm"
  [args]
  (rebel-readline.core/with-line-reader
    (rebel-readline.clojure.line-reader/create
     (rebel-readline.clojure.service.local/create))
    (clojure.main/repl
     :prompt (fn []) ;; prompt is handled by line-reader
     :read (rebel-readline.clojure.main/create-repl-read))))

(defmethod task "repl:node"
  [args]
  (rebel-readline.core/with-line-reader
    (rebel-readline.clojure.line-reader/create
     (rebel-readline.cljs.service.local/create))
    (cljs.repl/repl
     (node/repl-env)
     :prompt (fn []) ;; prompt is handled by line-reader
     :read (rebel-readline.cljs.repl/create-repl-read)
     :output-dir "out"
     :cache-analysis false)))

(def build-options
  {:main 'promesa.tests.main
   :output-to "out/tests.js"
   :output-dir "out/tests"
   :source-map "out/tests.js.map"
   :language-in  :ecmascript5
   :language-out :ecmascript5
   :target :nodejs
   :optimizations :advanced
   :pretty-print true
   :pseudo-names true
   :verbose true})

(defmethod task "build:tests"
  [args]
  (api/build (api/inputs "src" "test") build-options))

(defmethod task "jar"
  [args]
  (badigeon.jar/jar 'funcool/promesa
                    {:mvn/version "3.0.0-SNAPSHOT"}
                    {:out-path "target/promesa.jar"
                     :mvn/repos '{"clojars" {:url "https://repo.clojars.org/"}}
                     :allow-all-dependencies? false}))

(defmethod task "deploy"
  [args]
  (let [artifacts [{:file-path "target/promesa.jar" :extension "jar"}
                   {:file-path "pom.xml" :extension "pom"}]]
    (badigeon.deploy/deploy
     'funcool/promesa "3.0.0-SNAPSHOT"
     artifacts
     {:id "clojars" :url "https://repo.clojars.org/"}
     {:allow-unsigned? true})))

(defmethod task "build-and-deploy"
  [args]
  (task ["jar"])
  (task ["deploy"]))


;;; Build script entrypoint. This should be the last expression.

(task *command-line-args*)
