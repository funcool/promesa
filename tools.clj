(require '[clojure.java.shell :as shell]
         '[clojure.main])
(require '[rebel-readline.core]
         '[rebel-readline.clojure.main]
         '[rebel-readline.clojure.line-reader]
         '[rebel-readline.clojure.service.local]
         '[rebel-readline.cljs.service.local]
         '[rebel-readline.cljs.repl]
         '[eftest.runner :as ef])
(require '[cljs.build.api :as api]
         '[cljs.repl :as repl]
         '[cljs.repl.node :as node])

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

(def options
  {:main 'promesa.core-tests
   :output-to "out/tests.js"
   :output-dir "out/tests"
   :source-map "out/tests.js.map"
   :language-in  :ecmascript5
   :language-out :ecmascript5
   :target :nodejs
   :optimizations :simple
   :pretty-print true
   :pseudo-names true
   :verbose true})

(defmethod task "test"
  [[_ exclude]]
  (let [tests (ef/find-tests "test")
        tests (if (string? exclude)
                (ef/find-tests (symbol exclude))
                tests)]
    (ef/run-tests tests
                  {:fail-fast? true
                   :capture-output? false
                   :multithread? false})
    (System/exit 1)))


(defmethod task "test-cljs"
  [[_ type]]
  (letfn [(build [optimizations]
            (api/build (api/inputs "src" "test")
                       (cond->  (assoc options :optimizations optimizations)
                         (= optimizations :none) (assoc :source-map true))))

          (run-tests []
            (let [{:keys [out err]} (shell/sh "node" "out/tests.js")]
              (println out err)))

          (test-once []
            (build :none)
            (run-tests)
            (shutdown-agents))

          (test-watch []
            (println "Start watch loop...")
            (try
              (api/watch (api/inputs "src", "test")
                         (assoc options
                                :parallel-build false
                                :watch-fn run-tests
                                :cache-analysis false
                                :optimizations :none
                                :source-map true))
              (catch Exception e
                (println "ERROR:" e)
                (Thread/sleep 2000)
                (test-watch))))]

    (case type
      (nil "once") (test-once)
      "watch"      (test-watch)
      "build-none"     (build :none)
      "build-simple"   (build :simple)
      "build-advanced" (build :advanced)
      (do (println "Unknown argument to test task:" type)
          (System/exit 1)))))

;;; Build script entrypoint. This should be the last expression.

(task *command-line-args*)
