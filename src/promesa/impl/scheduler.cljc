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

(ns promesa.impl.scheduler
  "Scheduler helpers implementation. This is private api
  and should not used directly."
  (:require [promesa.impl.proto :as pt])
  #?(:clj
     (:import java.util.concurrent.ScheduledExecutorService
              java.util.concurrent.Executors
              java.util.concurrent.Future
              java.util.concurrent.TimeUnit
              java.util.concurrent.TimeoutException)))

#?(:cljs
   (defn- scheduled-task
     [cur done?]
     (let [cancelled (volatile! false)]
       (reify
         cljs.core/IPending
         (-realized? [_] @done?)

         pt/ICancellable
         (-cancelled? [_] @cancelled)
         (-cancel [_]
           (when-not @cancelled
             (vreset! cancelled true)
             (js/clearTimeout cur))))))

   :clj
   (defn- scheduled-task
     [^Future fut]
     (reify
       clojure.lang.IDeref
       (deref [_]
         (.get fut))

       clojure.lang.IBlockingDeref
       (deref [_ ms default]
         (try
           (.get fut ms TimeUnit/MILLISECONDS)
           (catch TimeoutException e
             default)))

       clojure.lang.IPending
       (isRealized [_] (and (.isDone fut)
                            (not (.isCancelled fut))))

       pt/ICancellable
       (-cancelled? [_]
         (.isCancelled fut))
       (-cancel [_]
         (when-not (.isCancelled fut)
           (.cancel fut true))))))

#?(:clj
   (extend-type ScheduledExecutorService
     pt/IScheduler
     (-schedule [this ms func]
       (let [fut (.schedule this ^Runnable func ^long ms TimeUnit/MILLISECONDS)]
         (scheduled-task fut)))))

#?(:cljs
   (defn scheduler
     []
     (reify
       pt/IScheduler
       (-schedule [_ ms func]
         (let [done? (volatile! false)
               task (fn []
                      (try
                        (func)
                        (finally
                          (vreset! done? true))))
               cur (js/setTimeout task ms)]
           (scheduled-task cur done?)))))
   :clj
   (defn- scheduler
     []
     (Executors/newScheduledThreadPool 1)))

(def ^:redef +scheduler+
  "A default lazy scheduler instance."
  (delay (scheduler)))

(defn schedule
  [ms func]
  (pt/-schedule @+scheduler+ ms func))
