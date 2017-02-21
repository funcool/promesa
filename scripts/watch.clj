(require '[cljs.build.api :as b])

(def options
  {:main 'promesa.core-tests
   :output-to "out/tests.js"
   :output-dir "out/tests"
   :language-in  :ecmascript5
   :language-out :ecmascript5
   :target :nodejs
   :optimizations :none
   :pretty-print true
   :verbose true})

(b/watch (b/inputs "test" "src") options)
