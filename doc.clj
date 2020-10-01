(require '[codox.main :as codox])

(codox/generate-docs
 {:output-path "doc/dist/latest"
  :metadata {:doc/format :markdown}
  ;; :package 'funcool/promesa
  ;; :version "5.1.1"
  :name "Promesa"
  :themes [:rdash]
  :source-paths ["src"]
  :namespaces [#"^promesa\."]
  :source-uri "https://github.com/funcool/promesa/blob/master/{filepath}#L{line}"})
