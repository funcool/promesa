(require
  '[cljs.repl :as repl]
  '[cljs.repl.node :as node])

(cljs.repl/repl
 (node/repl-env)
 :output-dir "out"
 :cache-analysis false)
