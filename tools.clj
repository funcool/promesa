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

(require '[clojure.tools.deps.alpha.script.generate-manifest :as manifest]
         '[clojure.data.xml :as xml])

(defmulti task first)

(defmethod task :default
  [args]
  (let [all-tasks  (-> task methods (dissoc :default) keys sort)
        interposed (->> all-tasks (interpose ", ") (apply str))]
    (println "Unknown or missing task. Choose one of:" interposed)
    (System/exit 1)))

(defmethod task "update:pom"
  [args]
  (alter-var-root #'xml/event-seq (fn [f]
                                    (fn [source opts]
                                      (f source (merge {:skip-whitespace true} opts)))))
  (manifest/-main "--gen" "pom" "--config-files" "deps.edn"))

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

(defmethod task "watch:tests"
  [args]
  (println "Start watch loop...")
  (letfn [(run-tests []
            (let [{:keys [out err]} (shell/sh "node" "out/tests.js")]
              (println out err)))
          (start-watch []
            (try
              (api/watch (api/inputs "src" "test")
                         (assoc build-options
                                :watch-fn run-tests
                                :source-map true
                                :optimizations :none))
              (catch Exception e
                (println "ERROR:" e)
                (Thread/sleep 2000)
                start-watch)))]
    (trampoline start-watch)))


;;; Build script entrypoint. This should be the last expression.

(task *command-line-args*)
