;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;;
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns promesa.async-cljs
  "core.async like facilities for dealing with asynchronous
  callback hell with promises (experimental).

  This is a clojurescript version of the macros."
  #?(:cljs
     (:require-macros [cljs.core.async.impl.ioc-macros :as ioc]))
  (:require [promesa.core :as p]
            [promesa.impl.promise :as ps]
            [promesa.impl.proto :as pt]
            #?(:clj [cljs.core.async.impl.ioc-macros :as ioc])
            #?@(:cljs [[cljs.core.async.impl.dispatch :as dispatch]
                       [cljs.core.async.impl.ioc-helpers :as ioc-helpers]])))


#?(:cljs
   (def ^:const PROMISE-IDX ioc-helpers/USER-START-IDX))

#?(:cljs
   (defn run-state-machine-wrapped
     [state]
     (try
       (ioc-helpers/run-state-machine state)
       (catch js/Error ex
         (let [[resolve reject] (ioc-helpers/aget-object state PROMISE-IDX)]
           (reject ex)
           (throw ex))))))

#?(:cljs
   (defn do-take
     [state blk p]
     (pt/-bind p (fn [v]
                   (ioc/aset-all! state ioc-helpers/VALUE-IDX v ioc-helpers/STATE-IDX blk)
                   (run-state-machine-wrapped state)
                   v))
     nil))

#?(:cljs
   (defn do-return
     [state value]
     (let [[resolve reject] (ioc-helpers/aget-object state PROMISE-IDX)]
       (resolve value))))

#?(:clj
   (def async-terminators
     {'promesa.core/await 'promesa.async-cljs/do-take
      'await 'promesa.async-cljs/do-take
      :Return 'promesa.async-cljs/do-return}))

#?(:clj
   (defmacro async
     "Asynchronously executes the body, returning immediately to the
     calling thread. Additionally, any visible calls to `await` on
     promise operations within the body will block (if necessary) by
     'parking' the calling thread rather than tying up an OS thread (or
     the only JS thread when in ClojureScript). Upon completion of the
     operation, the body will be resumed.

     Returns a promise which will be resolved with the result of the
     body when completed."
     [& body]
     `(promesa.core/promise
       (fn [resolve# reject#]
         (cljs.core.async.impl.dispatch/run
           (fn []
             (let [f# ~(ioc/state-machine body 1 &env async-terminators)
                   state# (-> (f#)
                              (ioc/aset-all! cljs.core.async.impl.ioc-helpers/USER-START-IDX [resolve# reject#]))]
               (promesa.async-cljs/run-state-machine-wrapped state#))))))))
