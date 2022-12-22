(ns promesa.tests.exec-csp-test
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]
   [promesa.protocols :as pt]))

(t/deftest chan-factory
  (let [c1 (sp/chan)
        c2 (sp/chan 2)
        c3 (sp/chan 2 (map inc))
        c4 (sp/chan (sp/fixed-buffer 2))
        c5 (sp/chan (sp/sliding-buffer 2))
        c6 (sp/chan (sp/dropping-buffer 2))]

    (t/is (sp/chan? c1))
    (t/is (sp/chan? c2))
    (t/is (sp/chan? c3))
    (t/is (sp/chan? c4))
    (t/is (sp/chan? c5))
    (t/is (sp/chan? c6))
    ))

(t/deftest chan-with-mapcat-transducer-1
  (let [ch (sp/chan 2 (mapcat identity))]
    (t/is (true? (sp/offer! ch [1 2 3])))
    (t/is (= 1 (sp/poll! ch)))
    (t/is (= 2 (sp/poll! ch)))
    (t/is (= 3 (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))

(t/deftest chan-with-mapcat-transducer-2
  (let [ch (sp/chan (sp/fixed-buffer 2) (mapcat identity))]
    (t/is (true? (sp/offer! ch [1 2 3])))
    (t/is (= 1 (sp/poll! ch)))
    (t/is (= 2 (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))


(t/deftest chan-with-terminating-transducer
  (let [ch (sp/chan 5 (take 2))]
    (t/is (true? (sp/offer! ch 1)))
    (t/is (true? (sp/offer! ch 2)))
    (t/is (false? (sp/offer! ch 3)))
    (t/is (sp/closed? ch))
    (t/is (= 1 (sp/poll! ch)))
    (t/is (= 2 (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))

(t/deftest non-blocking-ops-buffered-chan
  (let [ch (sp/chan 3)]
    (t/is (true? (sp/offer! ch :a)))
    (t/is (true? (sp/offer! ch :b)))
    (t/is (true? (sp/offer! ch :c)))
    (t/is (false? (sp/offer! ch :d)))

    (t/is (= :a (sp/poll! ch)))
    (t/is (= :b (sp/poll! ch)))
    (t/is (= :c (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))
    ))

(t/deftest non-blocking-ops-buffered-and-closed-chan
  (let [ch (sp/chan 3)]
    (t/is (true? (sp/offer! ch :a)))
    (t/is (true? (sp/offer! ch :b)))

    (sp/close! ch)

    (t/is (false? (sp/offer! ch :c)))
    (t/is (true? (sp/closed? ch)))

    (t/is (= :a (sp/poll! ch)))
    (t/is (= :b (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))
    ))

(t/deftest channel-with-sliding-buffer-and-transducer
  (let [ch (sp/chan (sp/sliding-buffer 2) (map name))]
    (t/is (true? (sp/offer! ch :a)))
    (t/is (true? (sp/offer! ch :b)))
    (t/is (true? (sp/offer! ch :c)))
    (t/is (= "b" (sp/poll! ch)))
    (t/is (= "c" (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))

(t/deftest channel-with-dropping-buffer-and-transducer
  (let [ch (sp/chan (sp/dropping-buffer 2) (map name))]
    (t/is (true? (sp/offer! ch :a)))
    (t/is (true? (sp/offer! ch :b)))
    (t/is (true? (sp/offer! ch :c)))
    (t/is (= "a" (sp/poll! ch)))
    (t/is (= "b" (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))

(t/deftest unbuffered-chan
  (let [ch (sp/chan)
        p1 (sp/go (sp/>! ch :a))
        r1 (sp/take ch)]
    (t/is (= :a @r1))
    (t/is (true? @p1))))

(t/deftest pipe-operation
  (let [ch1 (sp/chan)
        ch2 (sp/chan)]
    (sp/pipe ch1 ch2)

    (p/thread
      (sp/put! ch1 :a)
      (sp/put! ch1 :b)
      (sp/close! ch1))

    (t/is (= :a (sp/take! ch2)))
    (t/is (= :b (sp/take! ch2)))
    (t/is (nil? (sp/take! ch2)))
    (t/is (sp/closed? ch2))
    (t/is (sp/closed? ch1))))

(t/deftest onto-chan-operation
  (let [ch (sp/chan)
        rs (sp/onto-chan! ch [:a :b :c])]

    (t/is (p/promise? rs))
    (t/is (= :a (sp/take! ch 1000)))
    (t/is (= :b (sp/take! ch 1000)))
    (t/is (= :c (sp/take! ch 1000)))
    (t/is (nil? (sp/take! ch 1000)))
    (t/is (sp/closed? ch))
    (t/is (nil? (p/await! rs 1000)))))

(t/deftest operations-with-mult
  (let [mch (sp/mult)]
    (try
      (let [ch2 (sp/chan 1)
            ch3 (sp/chan 1)]
        (sp/offer! mch :a)
        (px/sleep 200)
        (sp/tap! mch ch2)
        (sp/tap! mch ch3)
        (sp/>! mch :b)
        (t/is (= :b (sp/<! ch2)))
        (t/is (= :b (sp/<! ch3)))
        )
      (finally
        (sp/close! mch)))))
