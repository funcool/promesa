(require '[cljs.build.api :as b])

(b/watch (b/inputs "test" "src" "assets")
  {:main 'promesa.core-tests
   :target :nodejs
   :output-to "out/tests.js"
   :output-dir "out"
   :optimizations :simple
   :pretty-print true
   :language-in  :ecmascript5
   :language-out :ecmascript5
   :verbose true})
