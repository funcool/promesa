(ns promesa.tests.exec-async-atom-test
  (:require
   [clojure.test :as t]
   [promesa.tests.util :refer [promise-ok promise-ko normalize-to-value]]
   [promesa.core :as p :include-macros true]
   [promesa.exec :as e]
   [promesa.exec.async-atom :as atm]))

(t/deftest instance-and-submit
  #?(:cljs
     (t/async done
       (let [o (atm/atom 1)]
         (->> (atm/send o inc)
              (p/fnly (fn [v e]
                        (t/is (nil? e))
                        (t/is (= 2 v))
                        (done))))))
     :clj
     (let [o (atm/atom 1)]
       (t/is (= 2 @(atm/send o inc))))))
