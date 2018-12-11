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

(ns promesa.async
  "core.async like facilities for dealing with asynchronous
  callback hell with promises (experimental)."
  (:require [promesa.core :as p]
            [promesa.impl :as impl]
            [promesa.protocols :as pt]
            [clojure.core.async.impl.ioc-macros :as ioc]))

(defn run-state-machine-wrapped
  [state]
  (try
    (ioc/run-state-machine state)
    (catch Throwable ex
      (let [[resolve reject] (ioc/aget-object state ioc/USER-START-IDX)]
        (reject ex)
        (throw ex)))))


(defn do-take
  [state blk p]
  (pt/-bind p (fn [v]
                (ioc/aset-all! state ioc/VALUE-IDX v ioc/STATE-IDX blk)
                (run-state-machine-wrapped state)
                v))
  (pt/-catch p (fn [e]
                 (if-let [excframes (seq (ioc/aget-object state ioc/EXCEPTION-FRAMES))]
                   (do
                     (ioc/aset-all! state
                                    ioc/VALUE-IDX e
                                    ioc/STATE-IDX (first excframes)
                                    ioc/EXCEPTION-FRAMES (rest excframes))
                     (run-state-machine-wrapped state))
                   (let [[resolve reject] (ioc/aget-object state ioc/USER-START-IDX)]
                     (reject e)))))

  nil)

(defn do-return
  [state value]
  (let [[resolve reject] (ioc/aget-object state ioc/USER-START-IDX)]
    (if-let [exception (ioc/aget-object state ioc/CURRENT-EXCEPTION)]
      (reject exception)
      (resolve value))))

(def async-terminators
  {'promesa.core/await `do-take
   :Return `do-return})

(defn run-async
  {:no-doc true
   :internal true}
  [f]
  (.execute impl/+executor+ ^Runnable f))


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
  (let [crossing-env (zipmap (keys &env) (repeatedly gensym))]
    `(let [bindings# (clojure.lang.Var/getThreadBindingFrame)]
       (p/promise
        (fn [resolve# reject#]
          (run-async
           (^:once fn* []
            (let [~@(mapcat (fn [[l sym]] [sym `(^:once fn* [] ~(vary-meta l dissoc :tag))]) crossing-env)
                  f# ~(ioc/state-machine `(do ~@body) 1
                                         [crossing-env &env]
                                         async-terminators)
                  state# (ioc/aset-all! (f#)
                                        ioc/USER-START-IDX [resolve# reject#]
                                        ioc/BINDINGS-IDX bindings#)]
              (run-state-machine-wrapped state#)))))))))
