(ns promesa.issue-36
  "Test for https://github.com/funcool/promesa/issues/36."
  (:require [cljs.test :as t]
            [promesa.test-helpers :refer [future-ok]]
            [promesa.core :as p
             :refer [await]
             :rename {await <!}
             :refer-macros [alet]]))

(t/deftest async-let-issue-36
  (t/async done
           (let [result (p/alet [a (<! (future-ok 50 1))
                                 b 2
                                 c 3
                                 d (<! (future-ok 100 4))]
                                (+ a b c d))]
             (p/then result (fn [result]
                              (t/is (= result 10))
                              (done))))))
