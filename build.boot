(set-env!
 :source-paths #{"src"}
 :resource-paths #{"assets"}
 :dependencies '[[cats "0.4.0"]
                 [adzerk/boot-cljs "0.0-2814-3" :scope "test"]
                 [boot-cljs-test/node-runner "0.1.0" :scope "test"]
                 [org.clojure/clojurescript "0.0-3123"  :scope "test"]
                 [org.clojure/clojure "1.7.0-beta3" :scope "test"]
                 [org.clojure/clojurescript "0.0-3269" :scope "test"]])

(require
 '[adzerk.boot-cljs :refer [cljs]]
 '[boot-cljs-test.node-runner :refer :all])

(def +version+ "0.1.2")

(deftask clojars-credentials
  []
  (fn [next-handler]
    (fn [fileset]
      (let [clojars-creds (atom {})]
        (print "Username: ")
        (swap! clojars-creds assoc :username (read-line))
        (print "Password: ")
        (swap! clojars-creds assoc :password
               (apply str (.readPassword (System/console))))
        (merge-env!
         :repositories [["deploy-clojars" (merge @clojars-creds {:url "https://clojars.org/repo"})]])
        (next-handler fileset)))))

(deftask push-snapshot
  "Deploy snapshot version to Clojars."
  [f file PATH str "The jar file to deploy."]
  (comp (clojars-credentials)
        (push :file file
              :ensure-snapshot true
              :repo "deploy-clojars"
              :ensure-version +version+
              :ensure-clean false
              :ensure-branch "master")))

(deftask push-release
  "Deploy snapshot version to Clojars."
  [f file PATH str "The jar file to deploy."]
  (comp (clojars-credentials)
        (push :file file
              :ensure-release true
              :repo "deploy-clojars"
              :ensure-version +version+
              :ensure-clean true
              :ensure-branch "master")))

(deftask build []
  (comp (pom :project     'funcool/promesa
             :version     +version+
             :description "A promise library for ClojureScript"
             :url         "https://github.com/funcool/promesa"
             :scm         {:url "https://github.com/funcool/promesa"}
             :license     {"BSD (2 Clause)" "http://opensource.org/licenses/BSD-2-Clause"})
        (jar)))

(deftask deploy-snapshot []
  (comp (build)
        (push-snapshot)))

(deftask deploy-release []
  (comp (build)
        (push-release)))

(deftask dev []
  (set-env! :source-paths #{"src" "test" "assets"})
  (comp (watch)
        (cljs-test-node-runner :namespaces '[promesa.core-tests])
        (cljs :source-map true :optimizations :none)
        (run-cljs-test)))
