(ns promesa.tests.exec-csp-test
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]
   [promesa.protocols :as pt]
   [promesa.tests.util :as u]))

(t/deftest chan-factory
  (let [c1 (sp/chan)
        c2 (sp/chan :buf 2)
        c3 (sp/chan :buf 2 :xf (map inc))
        c4 (sp/chan :buf (sp/fixed-buffer 2))
        c5 (sp/chan :buf (sp/sliding-buffer 2))
        c6 (sp/chan :buf (sp/dropping-buffer 2))]

    (t/is (sp/chan? c1))
    (t/is (sp/chan? c2))
    (t/is (sp/chan? c3))
    (t/is (sp/chan? c4))
    (t/is (sp/chan? c5))
    (t/is (sp/chan? c6))))

(t/deftest chan-metadata
  (let [c1 (sp/chan :buf 2)
        c2 (with-meta c1 {:foo 1})]
    (t/is (nil? (meta c1)))
    (t/is (= {:foo 1} (meta c2)))
    (t/is (not (identical? c1 c2)))
    (sp/offer! c1 :a)
    (sp/offer! c1 :b)
    (t/is (= :a (sp/poll! c2)))
    (t/is (= :b (sp/poll! c2)))))

(t/deftest chan-with-mapcat-transducer-1
  (let [ch (sp/chan :buf 2 :xf (mapcat identity))]
    (t/is (true? (sp/offer! ch [1 2 3])))
    (t/is (= 1 (sp/poll! ch)))
    (t/is (= 2 (sp/poll! ch)))
    (t/is (= 3 (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))

(t/deftest chan-with-mapcat-transducer-2
  (let [ch (sp/chan :buf (sp/fixed-buffer 2)
                    :xf (mapcat identity))]
    (t/is (true? (sp/offer! ch [1 2 3])))
    (t/is (= 1 (sp/poll! ch)))
    (t/is (= 2 (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))

(t/deftest chan-with-terminating-transducer
  (let [ch (sp/chan :buf 5 :xf (take 2))]
    (t/is (true? (sp/offer! ch 1)))
    (t/is (true? (sp/offer! ch 2)))
    (t/is (false? (sp/offer! ch 3)))
    (t/is (sp/closed? ch))
    (t/is (= 1 (sp/poll! ch)))
    (t/is (= 2 (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))

(t/deftest chan-with-stateful-transducer
  (let [ch (sp/chan :buf 1 :xf (partition-by identity))]
    (sp/put ch 1) ; starts as transducer state, fills buffer when 2 arrives
    (sp/put ch 2) ; closes 1 partition, added to transducer state, flushed by close!
    (sp/put ch 2) ; buffer is full - queued, then dropped by close!
    (sp/close! ch)

    #?(:clj
       (do
         (t/is (= [1] (sp/take! ch)))
         (t/is (= [2] (sp/take! ch))))

       :cljs
       (t/async done
         (->> (p/let [v1 (sp/take ch)
                      v2 (sp/take ch)]
                (t/is (= [1] v1))
                (t/is (= [2] v2)))
              (p/fnly (fn [v c]
                        (t/is (nil? c))
                        (done))))))))

(t/deftest non-blocking-ops-buffered-chan
  (let [ch (sp/chan :buf 3)]
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
  (let [ch (sp/chan :buf 3)]
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
  (let [ch (sp/chan :buf (sp/sliding-buffer 2) :xf (map name))]
    (t/is (true? (sp/offer! ch :a)))
    (t/is (true? (sp/offer! ch :b)))
    (t/is (true? (sp/offer! ch :c)))
    (t/is (= "b" (sp/poll! ch)))
    (t/is (= "c" (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))

(t/deftest channel-with-dropping-buffer-and-transducer
  (let [ch (sp/chan :buf (sp/dropping-buffer 2) :xf (map name))]
    (t/is (true? (sp/offer! ch :a)))
    (t/is (true? (sp/offer! ch :b)))
    (t/is (true? (sp/offer! ch :c)))
    (t/is (= "a" (sp/poll! ch)))
    (t/is (= "b" (sp/poll! ch)))
    (t/is (= nil (sp/poll! ch)))))

(t/deftest unbuffered-chan
  (let [ch (sp/chan)
        p1 (sp/put ch :a)
        r1 (sp/take ch)]
    (t/is (= :a @r1))
    #?(:cljs
       (t/async done
         (p/then p1 (fn [v]
                      (t/is (true? v))
                      (done)))))))

(t/deftest pipe-operation
  (let [ch1 (sp/chan)
        ch2 (sp/chan :buf 2)]
    (sp/pipe ch1 ch2)

    #?(:clj
       (do
         (sp/put! ch1 :a)
         (sp/put! ch1 :b)
         (sp/close! ch1)
         (t/is (= :a (sp/take! ch2)))
         (t/is (= :b (sp/take! ch2)))
         (t/is (nil? (sp/take! ch2)))
         (t/is (sp/closed? ch2))
         (t/is (sp/closed? ch1)))

       :cljs
       (t/async done
         (-> (p/all [(sp/put ch1 :a)
                     (sp/put ch1 :b)])
             (p/then (fn [result]
                       (sp/close! ch1)
                       (p/let [v1 (sp/take ch2)
                               v2 (sp/take ch2)
                               v3 (sp/take ch2)]
                         (t/is (= v1 :a))
                         (t/is (= v2 :b))
                         (t/is (nil? v3))
                         (t/is (sp/closed? ch2))
                         (t/is (sp/closed? ch1)))))
             (p/finally (fn [v c]
                          (t/is (nil? c))
                          (done))))))))

(t/deftest onto-chan-operation
  (let [ch (sp/chan :buf 3)
        rs (sp/onto-chan! ch [:a :b :c])]

    (t/is (p/promise? rs))
    #?(:clj
       (do
         (p/await! rs)
         (t/is (= :a (sp/poll! ch)))
         (t/is (= :b (sp/poll! ch)))
         (t/is (= :c (sp/poll! ch)))
         (t/is (nil? (sp/poll! ch)))
         (t/is (sp/closed? ch)))
       :cljs
       (t/async done
         (->> rs
              (p/fnly (fn [v c]
                        (t/is (nil? c))
                        (t/is (= :a (sp/poll! ch)))
                        (t/is (= :b (sp/poll! ch)))
                        (t/is (= :c (sp/poll! ch)))
                        (t/is (nil? (sp/poll! ch)))
                        (t/is (sp/closed? ch))
                        (done))))))))

(t/deftest operations-with-mult
  (let [mch (sp/mult)]
    #?(:clj
       (try
         (let [ch2 (sp/chan :buf 1)
               ch3 (sp/chan :buf 1)]
           (sp/offer! mch :a)
           (px/sleep 200)
           (sp/tap! mch ch2)
           (sp/tap! mch ch3)
           (sp/>! mch :b)
           (t/is (= :b (sp/<! ch2)))
           (t/is (= :b (sp/<! ch3)))
           )
         (finally
           (sp/close! mch)))
       :cljs
       (t/async done
         (let [ch2 (sp/chan :buf 1)
               ch3 (sp/chan :buf 1)]
           (sp/offer! mch :a)
           (sp/tap! mch ch2)
           (sp/tap! mch ch3)
           (->> (sp/put mch :b)
                (p/mcat #(p/delay 200))
                (p/fnly (fn [v c]
                          (t/is (= :b (sp/poll! ch2)))
                          (t/is (= :b (sp/poll! ch3)))))
                (p/fnly done)))))))


