(require '[cljs.build.api :as b])

(b/watch (b/inputs "test" "src")
  {:main 'promesa.core-tests
   :target :nodejs
   :output-to "out/tests.js"
   :output-dir "out"
   :language-in  :ecmascript5
   :language-out :ecmascript5
   :verbose true})
