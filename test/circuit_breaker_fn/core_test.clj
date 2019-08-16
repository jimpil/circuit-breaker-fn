(ns circuit-breaker-fn.core-test
  (:require [clojure.test :refer :all]
            [circuit-breaker-fn.core :refer :all])
  (:import (java.util.concurrent.atomic AtomicLong)))

(defn- controlled-handler
  [throw! x]
  (case x
    0 (throw! 0)
    1 (do (Thread/sleep 500)
          (throw! 1)) ;; ; exhausting window
    2 (do (Thread/sleep 200)
          (throw! 2)) ;; within fail-window now - start dropping
    6 [6 :success] ;; exhausted timeout first success in half-open
    7 [7 :success]
    8 (throw! 7) ;; trip up again - start dropping
    10 [10 :success]
    11 [11 :success]
    12 [12 :success] ;; 3rd success call - will reset
    13 [13 :success]
    14 [14 :success]
    :done))

(deftest fn-tests
  (testing "Circuit-breaking fn"
    (let [dropped    (atom [])
          exceptions (atom [])
          call (AtomicLong. 0)
          throw! #(throw (ex-info (str %) {}))
          handler (partial controlled-handler throw!)
          real-handler (cb-fn* handler {:fail-limit 2
                                        :fail-window 500
                                        :success-limit 3
                                        :open-timeout 1000
                                        :success-block 0
                                        ;:locking? true
                                        :drop-fn (fn [x]
                                                   ;(println "dropping" x)
                                                   (swap! dropped conj x)
                                                   (case x
                                                     (3,5) (Thread/sleep 505)
                                                     9 (Thread/sleep 1050)
                                                     nil))
                                        :ex-fn (fn [ex ex-time nfails]
                                                 (swap! exceptions conj ex)
                                                 nil)})
          ret (mapv real-handler (range 15))]
      (is (= [[6 :success]
              [7 :success]
              [10 :success]
              [11 :success]
              [12 :success]
              [13 :success]
              [14 :success]] (filter vector? ret)))
      (is (= ["0" "1" "2" "7"] (map #(.getMessage %) @exceptions)))
      (is (= [3 4 5 9]  @dropped)) ;; cases missing entirely from `controlled-handler`

      )
    )
  )

(deftest agent-tests
  (testing "Circuit-breaking agent"
    (let [dropped    (atom [])
          exceptions (atom [])
          [a wrap CBS-atom] (cb-agent* {:v 0}
                                       {:fail-limit 2
                                        :fail-window 1000
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
