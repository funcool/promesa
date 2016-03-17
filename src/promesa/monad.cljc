(ns promesa.monad
  "An optional cats integration."
  (:require [cats.core :as m]
            [cats.context :as mc]
            [cats.protocols :as mp]
            [promesa.core :as p])
  #?(:clj
     (:import java.util.concurrent.CompletionStage)))

(declare promise-context)

#?(:cljs
   (extend-type p/Promise
     mp/Contextual
     (-get-context [_] promise-context)

     mp/Extract
     (-extract [it]
       (p/-extract it)))

   :clj
   (extend-type CompletionStage
     mp/Contextual
     (-get-context [_] promise-context)

     mp/Extract
     (-extract [it]
       (p/-extract it))))

(def ^:no-doc promise-context
  (reify
    mp/Context
    (-get-level [_] mc/+level-default+)

    mp/Functor
    (-fmap [mn f mv]
      (p/-map mv f))

    mp/Bifunctor
    (-bimap [_ err succ mv]
      (-> mv
          (p/-map succ)
          (p/-catch err)))

    mp/Monad
    (-mreturn [_ v]
      (p/-promise v))

    (-mbind [mn mv f]
      (p/-bind mv f))

    mp/Applicative
    (-pure [_ v]
      (p/-promise v))

    (-fapply [_ pf pv]
      (p/-map (p/all [pf pv])
              (fn [[f v]]
                (f v))))

    mp/Semigroup
    (-mappend [_ mv mv']
      (p/-map (m/sequence [mv mv'])
              (fn [[mvv mvv']]
                (let [ctx (mp/-get-context mvv)]
                  (mp/-mappend ctx mvv mvv')))))))








