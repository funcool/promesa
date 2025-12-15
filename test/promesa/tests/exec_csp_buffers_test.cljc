(ns promesa.tests.exec-csp-buffers-test
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp.buffers :as buffers]
   [promesa.exec.csp.mutable-list :as mlist]
   [promesa.protocols :as pt]))

(t/deftest fixed-buffer
  (let [buf (buffers/fixed 2)]
    (t/is (nil? (pt/-poll buf)))
    (t/is (false? (pt/-full? buf)))
    (t/is (= 0 (pt/-size buf)))

    (t/is (true?  (pt/-offer buf :a)))
    (t/is (true?  (pt/-offer buf :b)))
    (t/is (false? (pt/-offer buf :c)))

    (t/is (true? (pt/-full? buf)))
    (t/is (= :a (pt/-poll buf)))
    (t/is (= :b (pt/-poll buf)))

    (t/is (false? (pt/-full? buf)))
    (t/is (= 0 (pt/-size buf)))
    ))

(t/deftest expanding-buffer
  (let [buf (buffers/expanding 2)]
    (t/is (nil? (pt/-poll buf)))
    (t/is (false? (pt/-full? buf)))
    (t/is (= 0 (pt/-size buf)))

    (t/is (true?  (pt/-offer buf :a)))
    (t/is (true?  (pt/-offer buf :b)))
    (t/is (true? (pt/-offer buf :c)))

    (t/is (true? (pt/-full? buf)))
    (t/is (= :a (pt/-poll buf)))
    (t/is (= :b (pt/-poll buf)))
    (t/is (= :c (pt/-poll buf)))

    (t/is (false? (pt/-full? buf)))
    (t/is (= 0 (pt/-size buf)))
    ))

(t/deftest dropping-buffer
  (let [buf (buffers/dropping 2)]
    (t/is (nil? (pt/-poll buf)))
    (t/is (false? (pt/-full? buf)))
    (t/is (= 0 (pt/-size buf)))

    (t/is (true? (pt/-offer buf :a)))
    (t/is (true? (pt/-offer buf :b)))
    (t/is (true? (pt/-offer buf :c)))
    (t/is (= 2 (pt/-size buf)))

    (t/is (false? (pt/-full? buf)))
    (t/is (= :a (pt/-poll buf)))
    (t/is (= :b (pt/-poll buf)))
    (t/is (= nil (pt/-poll buf)))


    (t/is (false? (pt/-full? buf)))
    (t/is (= 0 (pt/-size buf)))
    ))

(t/deftest sliding-buffer
  (let [buf (buffers/sliding 2)]
    (t/is (nil? (pt/-poll buf)))
    (t/is (false? (pt/-full? buf)))
    (t/is (= 0 (pt/-size buf)))

    (t/is (true? (pt/-offer buf :a)))
    (t/is (true? (pt/-offer buf :b)))
    (t/is (true? (pt/-offer buf :c)))
    (t/is (= 2 (pt/-size buf)))

    (t/is (false? (pt/-full? buf)))
    (t/is (= :b (pt/-poll buf)))
    (t/is (= :c (pt/-poll buf)))
    (t/is (= nil (pt/-poll buf)))

    (t/is (false? (pt/-full? buf)))
    (t/is (= 0 (pt/-size buf)))
    ))

(t/deftest list-ops
  (let [o (mlist/create)]
    (t/is (some? (mlist/add-first o "a")))
    (t/is (some? (mlist/add-first o "b")))
    (t/is (some? (mlist/add-first o "c")))
    (t/is (= 3 (mlist/size o)))
    (t/is (= "a" (mlist/remove-last o)))
    (t/is (some? (mlist/add-last o "d")))
    (t/is (= "d" (mlist/remove-last o)))
    (t/is (= 2 (mlist/size o)))
    ))

