(ns promesa.monad
  "An optional cats integration."
  (:require [cats.core :as m]
            [cats.context :as mc]
            [cats.protocols :as mp]
            [cats.util :as mutil]
            [promesa.core :as pc]
            [promesa.protocols :as p]))

(declare promise-context)

#?(:cljs
   (extend-type js/Promise
     mp/Contextual
     (-get-context [_] promise-context)

     mp/Printable
     (-repr [it]
       (str "#<Promise ["
            (cond
              (p/-pending? it) "~"
              (p/-rejected? it) (str "error=" (m/extract it))
              :else (str "value=" (m/extract it)))
            "]>"))

     mp/Extract
     (-extract [it]
       (if (.isRejected it)
         (.reason it)
         (.value it)))))

#?(:clj
   (extend-type CompletionStage
     mp/Contextual
     (-get-context [_] promise-context)

     mp/Printable
     (-repr [it]
       (str "#<Promise ["
            (cond
              (p/-pending? it) "~"
              (p/-rejected? it) (str "error=" (m/extract it))
              :else (str "value=" (m/extract it)))
            "]>"))

     mp/Extract
     (-extract [it]
       (try
         (.getNow it nil)
         (catch ExecutionException e
           (.getCause e))
         (catch CompletionException e
           (.getCause e))))))

#?(:cljs (mutil/make-printable js/Promise)
   :clj (mutil/make-printable CompletionStage))

(defn branch
  [p callback errback]
  (m/bimap errback callback p))

(def ^{:no-doc true}
  promise-context
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
      (p/-map (pc/all [pf pv])
              (fn [[f v]]
                (f v))))

    mp/Semigroup
    (-mappend [_ mv mv']
      (p/-map (m/sequence [mv mv'])
              (fn [[mvv mvv']]
                (let [ctx (mp/-get-context mvv)]
                  (mp/-mappend ctx mvv mvv')))))))








