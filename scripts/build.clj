(require '[cljs.build.api :as b])

(def options
  {:main 'promesa.core-tests
   :output-to "out/tests.js"
   :output-dir "out/tests"
   :language-in  :ecmascript5
   :language-out :ecmascript5
   :target :nodejs
   :optimizations :advanced
   :pretty-print true
   :verbose true
   :pseudo-names true})

(let [start (System/nanoTime)]
  (println "Building ...")
  (b/build (b/inputs "test" "src") options)
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
