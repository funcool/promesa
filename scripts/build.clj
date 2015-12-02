(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "test" "src" "assets")
   {:main 'promesa.core-tests
    :output-to "out/tests.js"
    :output-dir "out"
    :target :nodejs
    :optimizations :advanced
    :language-in  :ecmascript5
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
