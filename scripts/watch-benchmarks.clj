(require '[cljs.build.api :as b])

(b/watch
 (b/inputs "assets" "src" "dev/src")
 {:main 'promesa.benchmarks
  ;; :target :nodejs
  :source-map "dev/out/benchmarks.js.map"
  :output-to "dev/out/benchmarks.js"
  :output-dir "dev/out/benchmarks"
  :optimizations :advanced
  :pretty-print false
  :pseudo-names false
  :elide-asserts true
  :language-in  :ecmascript5
  :language-out :ecmascript5
  :compiler-stats true
  :verbose true})
