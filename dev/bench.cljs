(ns bench
  (:require [promesa.impl.promise :as prm]))

(enable-console-print!)

(defn now
  []
  (.now js/performance))

(defn tpoint
  []
  (let [p1 (now)]
    (fn []
      (- (now) p1))))

(defn- precision
  [v n]
  (when (and (number? v) (integer? n))
    (let [d (js/Math.pow 10 n)]
      (/ (js/Math.round (* v d)) d))))

(defn- scale-time
  "Determine a scale factor and unit for displaying a time."
  [measurement]
  (cond
    (> measurement 60)   [(/ 60) "min"]
    (< measurement 1e-6) [1e9 "ns"]
    (< measurement 1e-3) [1e6 "µs"]
    (< measurement 1)    [1e3 "ms"]
    :else                [1 "sec"]))

(defn- format-time
  [value]
  (let [value        (/ value 1000)
        [scale unit] (scale-time value)]
    ;; (js/console.log "format-time" value scale unit)
    (str (precision (* scale value) 2) unit)))

(def black-hole
  (volatile! nil))

(def noop (constantly nil))

(defn- benchmark
  [& {:keys [f name iterations on-end]
      :or {name "unnamed" iterations 100000 on-end noop}}]

  (letfn [(exec-and-measure [t* i* next-fn]
            (let [t0 (js/performance.now)]
              (f (fn [r]
                   (vreset! black-hole r)
                   (let [t1 (js/performance.now)
                         i  (vswap! i* inc)
                         t  (vswap! t* + (- t1 t0))]
                     ;; (js/console.log "exec-and-measure" i t)
                     (if (< i iterations)
                       (js/queueMicrotask (partial exec-and-measure t* i* next-fn))
                       (js/queueMicrotask next-fn)))))))

          (bench [next-fn]
            (let [t* (volatile! 0)
                  i* (volatile! 0)]
              (println "--> BENCH: " iterations)
              (exec-and-measure t* i*
                                (fn []
                                  (let [mean (/ @t* iterations)]
                                    (next-fn {:mean mean :total @t*}))))))

          (warm [next-fn]
            (let [t* (volatile! 0)
                  i* (volatile! 0)]

              (println "--> WARM:  " iterations)
              (exec-and-measure t* i* next-fn)))]


    (println "=> benchmarking:" name)
    (warm (fn []
            (bench (fn [{:keys [mean total]}]
                     (println "--> TOTAL:" (format-time total))
                     (println "--> MEAN: " (format-time mean))
                     (on-end)))))))

(defn bench-native-promise-then
  [done]
  (-> (js/Promise.resolve nil)
      (.then done)))

(defn bench-builtin-promise-then
  [done]
  (-> (prm/resolved nil)
      (.then done)))

(defn bench-builtin-promise-fmap
  [done]
  (-> (prm/resolved nil)
      (.fmap done)))

(defn exit
  []
  (.exit js/process 0))

(def iterations 1000000)

(defn main
  [& [name]]
  (case name
    "native-then"
    (benchmark :name "native-then"
               :f bench-native-promise-then
               :iterations iterations
               :on-end exit)

    "builtin-then"
    (benchmark :name "builtin-then"
               :f bench-builtin-promise-then
               :iterations iterations
               :on-end exit)

    "builtin-fmap"
    (benchmark :name "builtin-fmap"
               :f bench-builtin-promise-fmap
               :iterations iterations
               :on-end exit)

    (do
      (println "available: native-then builtin-then")
      (exit))))
