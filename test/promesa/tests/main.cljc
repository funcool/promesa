(ns promesa.tests.main
  (:require [clojure.test :as t]
            [promesa.tests.core-test]))

#?(:cljs
   (do
     (enable-console-print!)
     (set! *main-cli-fn* #(t/run-tests
                           'promesa.tests.core-test
                           )))
   :clj
   (defn -main
     [& args]
     (let [{:keys [fail]} (t/run-all-tests #"^promesa.tests.*")]
       (if (pos? fail)
         (System/exit fail)
         (System/exit 0)))))

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests]
     [m]
     (if (t/successful? m)
       (set! (.-exitCode js/process) 0)
       (set! (.-exitCode js/process) 1))))
