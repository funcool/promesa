(ns promesa.tests.exec-bulkhead-test
  (:require
   [promesa.core :as p]
   [promesa.exec.bulkhead :as pbh]
   [promesa.exec :as px]
   [clojure.test :as t]))

(def ^:dynamic *executor* nil)

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
  ([ms] #(do (Thread/sleep (int ms)) (rand-int 100))))

(t/deftest basic-operations-submit
  (let [instance (pbh/create {:concurrency 1})]
    (let [res (px/submit! instance (timing-fn))]
      (t/is (p/promise? res))
      (t/is (< @res 10000000)))))

(t/deftest basic-operations-reject
  (let [instance (pbh/create {:concurrency 1 :queue-size 1})
        res1     (px/submit! instance (waiting-fn 1000))
        ;; _        (Thread/sleep 200)
        res2     (px/submit! instance (waiting-fn 500))
        ;; _        (Thread/sleep 200)
        res3     (px/submit! instance (waiting-fn 500))
        ]
    (t/is (p/promise? res1))
    (t/is (p/promise? res2))
    (t/is (p/promise? res3))

    (t/is (p/pending? res1))
    (t/is (p/pending? res2))
    (t/is (not (p/pending? res3)))
    (t/is (p/rejected? res3))

    (t/is (pos? (deref res1)))
    (t/is (pos? (deref res2)))
    (t/is (thrown? java.util.concurrent.ExecutionException (deref res3)))
    ))

#_(t/deftest basic-operations-hooks
  (let [state    (atom {})
        on-queue (fn [lim]
                   (swap! state update :on-queue (fnil inc 0)))
        on-run   (fn [lim task]
                   (swap! state update :on-run (fnil inc 0)))
        instance (pbh/create {:concurrency 2
                              :on-queue on-queue
                              :on-run on-run})
        res1      (px/submit! instance (waiting-fn))
        res2      (px/submit! instance (waiting-fn))
        res3      (px/submit! instance (waiting-fn))
        res4      (px/submit! instance (waiting-fn))
        res5      (px/submit! instance (waiting-fn))]

    (t/is (< @res1 10000000))
    (t/is (< @res2 10000000))
    (t/is (< @res3 10000000))
    (t/is (< @res4 10000000))
    (t/is (< @res5 10000000))
    (prn @res1 @res2 @res3 @res4 @res5)
    (prn @state)))



