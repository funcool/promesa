(ns promesa.tests.exec-bulkhead-test
  (:require
   [promesa.core :as p]
   [promesa.exec.bulkhead :as pbh]
   [promesa.exec :as px]
   [promesa.util :as pu]
   [clojure.test :as t]))

(def ^:dynamic *context* 1)

(t/use-fixtures :each (fn [next]
                        (binding [px/*default-executor* (px/forkjoin-executor)]
                          ;; (prn "PRE" px/*default-executor*)
                          (next)
                          ;; (prn "POST" px/*default-executor*)
                          (.shutdown ^java.util.concurrent.ExecutorService px/*default-executor*))))

(defn timing-fn
  "Create a measurement checkpoint for time measurement of potentially
  asynchronous flow."
  []
  (let [p1 (System/nanoTime)]
    #(- (System/nanoTime) p1)))

(defn waiting-fn
  ([] (waiting-fn 200))
  ([ms] #(do
           (px/sleep ms)
           (rand-int 100))))

;; (t/deftest basic-operations-submit
;;   (let [instance (pbh/create {:permits 1 :type :executor})]
;;     (let [res (px/submit instance (timing-fn))]
;;       (t/is (p/promise? res))
;;       (t/is (< @res 10000000)))))

(t/deftest operations-with-executor-bulkhead
  (let [instance (pbh/create {:permits 1 :queue 2 :type :executor})
        res1     (px/submit instance (waiting-fn 1000))
        res2     (px/submit instance (waiting-fn 200))
        res3     (px/submit instance (waiting-fn 200))
        ]
    (t/is (p/promise? res1))
    (t/is (p/promise? res2))
    (t/is (p/promise? res3))

    (t/is (p/pending? res1))
    (t/is (p/pending? res2))
    (t/is (p/rejected? res3))

    (t/is (pos? (deref res1 2000 -1)))
    (t/is (pos? (deref res2 2000 -1)))

    (let [cause (pu/try! @res3)
          cause (pu/unwrap-exception cause)
          data  (ex-data cause)]
      (t/is (= :bulkhead-error (:type data)))
      (t/is (= :capacity-limit-reached (:code data))))))

(t/deftest operations-with-semaphore-bulkhead
  (let [instance (pbh/create {:permits 1 :queue 1 :type :semaphore})
        res1     (px/with-dispatch :thread
                   (p/await (px/submit instance (waiting-fn 2000))))
        _        (px/sleep 200)
        res2     (px/with-dispatch :thread
                   (p/await (px/submit instance (waiting-fn 2000))))
        _        (px/sleep 200)
        res3     (px/with-dispatch :thread
                   (p/await (px/submit instance (-> (waiting-fn 200)
                                                    (with-meta {:name "res3"})))))
        ]

    (t/is (p/promise? res1))
    (t/is (p/promise? res2))
    (t/is (p/promise? res3))

    (t/is (p/pending? res1))
    (t/is (p/pending? res2))

    (p/await res3)

    (t/is (p/rejected? res3))
    (t/is (pos? (deref res1 2200 -1)))
    (t/is (pos? (deref res2 2200 -1)))

    (t/is (thrown? java.util.concurrent.ExecutionException (deref res3)))

    (let [cause (p/extract res3)
          cause (pu/unwrap-exception cause)
          data  (ex-data cause)]
      (t/is (= :bulkhead-error (:type data)))
      (t/is (= :capacity-limit-reached (:code data))))))

(t/deftest bulkhead-with-invoke-1
  (let [instance (pbh/create {:permits 1 :queue 1 :type :semaphore})
        handler  (fn []
                   (binding [*context* (inc *context*)]
                     *context*))
        result1  (px/invoke instance handler)
        result2  (binding [*context* 11]
                   (px/invoke instance handler))]

    (t/is (= 2 result1))
    (t/is (= 12 result2))))

(t/deftest bulkhead-with-invoke-2
  (let [executor (px/single-executor)
        instance (pbh/create {:permits 1 :queue 1 :type :executor :executor executor})
        handler  (fn []
                   (binding [*context* (inc *context*)]
                     *context*))
        result1  (px/invoke instance handler)
        result2  (binding [*context* 11]
                   (px/invoke instance handler))]
    (t/is (= 2 result1))
    (t/is (= 12 result2))))
