(require '[clojure.java.shell :as shell]
         '[clojure.main])
(require '[badigeon.jar]
         '[badigeon.deploy])

(defmulti task first)

(defmethod task "jar"
  [args]
  (badigeon.jar/jar 'funcool/promesa
                    {:mvn/version "4.0.0-SNAPSHOT"}
                    {:out-path "target/promesa.jar"
                     :mvn/repos '{"clojars" {:url "https://repo.clojars.org/"}}
                     :allow-all-dependencies? false}))

(defmethod task "deploy"
  [args]
  (let [artifacts [{:file-path "target/promesa.jar" :extension "jar"}
                   {:file-path "pom.xml" :extension "pom"}]]
    (badigeon.deploy/deploy
     'funcool/promesa "4.0.0-SNAPSHOT"
     artifacts
     {:id "clojars" :url "https://repo.clojars.org/"}
     {:allow-unsigned? true})))

(defmethod task :default
  [args]
  (task ["jar"])
  (task ["deploy"]))

;;; Build script entrypoint. This should be the last expression.

(task *command-line-args*)
