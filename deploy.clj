(require '[clojure.java.shell :as shell]
         '[clojure.main])

(defmulti task first)

(defmethod task :default
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

(task *command-line-args*)
