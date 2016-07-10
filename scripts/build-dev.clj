(require '[cljs.build.api :as b])

(def config
  {:source-map "dev/dist/main.js.map"
   :output-to "dev/dist/main.js"
   :output-dir "dev/dist/main"
   :optimizations :advanced
   :language-in  :ecmascript5
    :language-out :ecmascript5
   :verbose true})

(def inputs
  (b/inputs "dev/src" "src" "assets"))

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build inputs config)
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))

(b/watch inputs config)
