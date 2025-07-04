(ns test-runner
  (:require [clojure.test :as t]
            [promesa.tests.core-test]
            [promesa.tests.exec-csp-test]))

(defn run-tests [_]
  (let [{:keys [fail error]}
        (t/run-tests 'promesa.tests.core-test
                     'promesa.tests.exec-csp-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Tests failed" {:babashka/exit 1})))))
