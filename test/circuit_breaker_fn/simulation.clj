(ns circuit-breaker-fn.simulation
  (:require [clojure.test :refer :all]
            [circuit-breaker-fn.core :refer [cb-fn*]]))

(defn- simulate!
  [open-timeout]
  (let [process-fn (fn [_]
                     (let [r (rand-int 100)]
                       (if (>= r 10) ;; 90% chance of success (crudely)
                         (println :processed)
                         (throw (ex-info "problem" {})))))
        drop-fn   (fn [_] (println :dropped))
        ex-atom (atom [])
        ex-fn (fn [ex t nfails]
                (println :error)
                (swap! ex-atom conj t))
        sleep-ms (vec (range 5 201))
        real-handler (cb-fn* process-fn {:fail-limit 1
                                         :fail-window 1000
                                         :success-limit 2
                                         :open-timeout open-timeout
                                         :success-block 0
                                         :drop-fn drop-fn
                                         :ex-fn ex-fn
                                         ;:locking? true
                                         })
        fut-fn (fn [i]
                 (future
                   (while (not (.isInterrupted (Thread/currentThread)))
                     (real-handler i)
                     (Thread/sleep (rand-nth sleep-ms)))))
        futures (mapv fut-fn (range 4))]
    (println "Running simulation with 4 threads for 30 seconds...")
    (Thread/sleep 30000)
    (run! future-cancel futures)
    (println "Stopping simulation")
    (Thread/sleep 200)
    @ex-atom)
  )

(deftest cb-guarantee
  (testing "interval between fails is NEVER less than <open-timeout> when <fail-limit> = 1"
    (let [open-timeout 1000
          ex-times (simulate! open-timeout)
          deltas   (map (comp (partial apply -)
                              reverse)
                        (partition 2 1 ex-times))]
      ;; exceptions were thrown with a time-diff of at least <open-timeout>
      (is (every? (partial <= open-timeout) deltas))
      )
    )
  )
