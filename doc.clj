(require '[codox.main :as codox])

(codox/generate-docs
 {:output-path "doc/dist/latest"
  :metadata {:doc/format :markdown}
  :name "Promesa"
  :themes [:rdash]
  :source-paths ["src"]
  :namespaces [#"^promesa\."]
  :doc-files
  ["doc/intro.md"
   "doc/promises.md"
   "doc/executors.md"
   "doc/channels.md"
   "doc/bulkhead.md"
   "doc/contributing.md"]
  :source-uri "https://github.com/funcool/promesa/blob/master/{filepath}#L{line}"})
