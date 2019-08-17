(ns circuit-breaker-fn.primitives
  (:import (java.util.concurrent TimeUnit)))

;; A circuit-breaker is conceptually made up of 3 pieces:
; - an internal state    (CLOSED, OPEN, HALF-OPEN)
; - an error handler     (see `cb-error-handler`)
; - a processing handler (see `cb-wrap-handler`)


(defonce cb-init ;; static init-state
  {:cbs :CLOSED  ;; always start CLOSED
   :success-count 0
   :fail-count 0
   :last-fail 0}) ;; piece of state to support time-windowed fail count

(defn cb-error-handler
  "Function with which to handle errors in a circuit-breaker context.
   Typical usage would involve partially binding all but the last arg
   (the exception itself).

   <CBS>          - atom holding all the state required (see `cb-init`).
   <fail-limit>   - How many Exceptions (within <window-nanos>) before transitioning from CLOSED => OPEN.
   <window-nanos> - Time window (in nanos) in which <fail-limit> has an effect.
   <open-timeout> - How long (in <timeout-unit>) to wait before transitioning from OPEN => HALF-OPEN
   <timeout-unit> - One of the keys in `util/time-units` (defaults to `:millis`).
   <ex-fn>        - Function of 3 args to be called last. Takes the Exception itself (do NOT rethrow),
                    the time it occurred (per `System/nanoTime`) & the current fail count."
  [CBS [fail-limit window-nanos] [open-timeout ^TimeUnit time-unit] ex-fn ex]
  (let [error-time (System/nanoTime)
        previous-fail (:last-fail @CBS)
        {:keys [fail-count cbs]} (swap! CBS #(-> %
                                                 (assoc :last-fail error-time)
                                                 (update :fail-count inc)))]
    (when (and (not= :OPEN cbs)           ;; someone else has already done this!
               (>= fail-count fail-limit) ;; over the fail-limit
               (or (zero? previous-fail)  ;; check for interval only when it makes sense
                   (>= window-nanos (- error-time previous-fail)))) ;; within window?
      ;; transition to OPEN immediately,
      ;; and to HALF-OPEN after <open-timeout> ms
      (swap! CBS assoc :cbs :OPEN)
      (future
        (.sleep time-unit open-timeout)
        (swap! CBS assoc
               :cbs :HALF-OPEN
               :success-count 0))) ;; don't forget to reset this!
    ;; let the caller decide what to return
    (ex-fn ex error-time fail-count)))

(defn cb-wrap-handler
  "Given a <handler> fn, returns a wrapped version
   of it (with circuit-breaker semantics).

   <CBS>           - atom holding all the state required (see `cb-init`).
   <success-limit> - How many successful calls before transitioning from HALF-OPEN => CLOSED
   <drop-fn>       - Function to handle all requests while in OPEN state (arg-list per <handler>).
                     If a default value makes sense in your domain this is your chance to use it.
   <success-block> - Function (or positive integer) expected to produce an artificial delay
                     (via `Thread/sleep`) after each successful <handler> call (arg-list per <handler>).
   <handler>       - The underlying processing handler function."
  [CBS success-limit drop-fn success-block handler]
  (let [delay-fn (if (some? success-block)
                   (if (fn? success-block)
                     success-block
                     (fn [& _] (Thread/sleep success-block)))
                   (constantly nil))]
    (fn [& args]
      (case (:cbs @CBS)
        ;; circuit is closed - current flows through
        :CLOSED (let [ret (apply handler args)]
                  (apply delay-fn args)
                  ret)
        ;; circuit is open - current does not flow through
        :OPEN   (apply drop-fn args)
        ;; circuit is half-open - try to push some current through
        :HALF-OPEN (try
                     (let [ret (apply handler args) ;; this is the critical call
                           {:keys [success-count]} (swap! CBS update :success-count inc)]
                       (when (>= success-count success-limit) ;; assume the service was fixed
                         ;; we're about to go back to normal operation (CLOSED state)
                         ;; reset all state to how they originally were (except success-count obviously)
                         (swap! CBS assoc
                                :cbs :CLOSED
                                :fail-count 0
                                :last-fail 0))
                       (apply delay-fn args)
                       ret)
                     (catch Throwable t
                       ;; re-throw the exception after making sure that
                       ;; the error-handler will transition the state to OPEN
                       ;; on the very first attempt. That means resetting `last-fail`
                       ;; but NOT `fail-count`!
                       (swap! CBS assoc :last-fail 0)
                       (throw t)))))))
