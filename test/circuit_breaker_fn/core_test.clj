(ns circuit-breaker-fn.core-test
  (:require [clojure.test :refer :all]
            [circuit-breaker-fn.core :refer :all]))

(deftest agent-cb-tests
  (testing "Circuit-breaking agent"
    (let [dropped    (atom [])
          exceptions (atom [])
          [a wrap CBS-atom] (cb-agent* {:v 0}
                                       {:fail-limit 2
                                        :fail-interval 1000
                                        :success-limit 3
                                        :open-timeout 2000
                                        :success-block 0
                                        :drop-fn (fn [state & args]
                                                   (swap! dropped conj state))
                                        :ex-fn (fn [ex ex-time nfails]
                                                 (swap! exceptions conj ex))})
          inc-v* (fn [state & args]
                   (update state :v inc))]
      ;; no problems with those 2
      (send-off a (wrap #(update % :v inc))) ;; v = 1
      (send-off a (wrap #(update % :v dec))) ;; v = 0
      ;; first problem (divide-by-zero)
      (send-off a (wrap (fn [astate & args]
                          (update astate :v #(/ 1 %)))))
      ;(Thread/sleep 200)
      ;; still in CLOSED state
      ;; second problem (divide-by-zero)
      (send-off a (wrap (fn [astate & args]
                          (update astate :v #(/ 1 %)))))
      ;; now in OPEN state - we'll stay here for 2 seconds
      ;(Thread/sleep 200)
      ;(is (= :OPEN (:cbs @CBS-atom)))
      (future ;; these three will be dropped
        (send-off a (wrap inc-v*))
        (send-off a (wrap inc-v*))
        (send-off a (wrap inc-v*)))
      (Thread/sleep 2010) ;; this is important
      ;(is (= :HALF-OPEN (:cbs @CBS-atom)))

      ;; 3 consecutive successful calls will turn us back to CLOSED
      (send-off a (wrap inc-v*))
      (send-off a (wrap inc-v*))
      (send-off a (wrap inc-v*))
      ;(Thread/sleep 200)
      ;(is (= :CLOSED (:cbs @CBS-atom)))

      (is (= 3 (:v @a)))
      (is (every? (partial instance? ArithmeticException) @exceptions))
      (is (= 2 (count @exceptions)))
      ;; we'd see [1 2 3] had the calls not been dropped
      (is (= [0 0 0] (map :v @dropped)))

      )


    )
  )
