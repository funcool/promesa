(require '[clojure.java.shell :as shell])
(require '[clojure.tools.deps.alpha.script.generate-manifest :as manifest]
         '[clojure.data.xml :as xml])
(require '[hf.depstar.uberjar :refer [uber-main]])


(defmulti task first)

(defmethod task "update:pom"
  [args]
  (alter-var-root #'xml/event-seq (fn [f]
                                    (fn [source opts]
                                      (f source (merge {:skip-whitespace true} opts)))))
  (manifest/-main "--gen" "pom" "--config-files" "deps.edn"))

(defmethod task "update:jar"
  [args]
  (uber-main {:dest "target/promesa.jar" :jar :thin} []))

(defmethod task "deploy"
  [args]
  (let [result (shell/sh "mvn" "deploy:deploy-file"
                         "-Dfile=target/promesa.jar"
                         "-DpomFile=pom.xml"
                         "-DrepositoryId=clojars"
                         "-Durl=https://clojars.org/repo/")]
    (println (:out result))
    (binding [*out* *err*]
      (println (:err result)))
    (System/exit (:exit result))))

(defmethod task :default
  [args]
  (let [all-tasks  (-> task methods (dissoc :default) keys sort)
        interposed (->> all-tasks (interpose ", ") (apply str))]
    (println "Unknown or missing task. Choose one of:" interposed)
    (System/exit 1)))

(task *command-line-args*)
