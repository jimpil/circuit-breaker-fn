(ns circuit-breaker-fn.core
  "See https://docs.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker
   for a neat article with diagrams and everything."
  )

;; A circuit-breaker is conceptually made up of 3 pieces:
; - some internal state
; - an error handler
; - a processing handler


(defonce cb-init
  ;; static init-state (use it as is or merge it with your own)
  {:cbs :CLOSED ;; always start CLOSED
   :success-count 0
   :fail-count 0
   :last-fail 0}) ;; piece of state to support time-windowed fail count

;; An error-handler
(defn cb-error-handler
  "Function with which to handle errors in a circuit-breaker context.
   Typical usage would involve partially binding all but the last arg
   (the exception itself).

   <CBS> - atom holding all the state required (see `cb-init`).
   <fail-limit> - How many Exceptions (within <fail-interval>) before transitioning from CLOSED => OPEN.
   <fail-window-nanos> - Time window (in nanos) in which <fail-limit> has an effect.
   <open-timeout> - How long (in millis) to wait before transitioning from OPEN => HALF-OPEN
   <ex-fn> - Function of 3 args to be called last. Takes the Exception itself (do NOT rethrow),
             the time it occurred (per `System/nanoTime`) & the current fail count.
             Can be used for logging."
  [CBS [fail-limit fail-window-nanos] open-timeout ex-fn ex]
  (let [error-time (System/nanoTime)
        previous-fail (:last-fail @CBS)
        {:keys [fail-count cbs]} (swap! CBS #(-> %
                                                 (assoc :last-fail error-time)
                                                 (update :fail-count inc)))]
    (when (and (not= :OPEN cbs)           ;; someone else has already done this!
               (>= fail-count fail-limit) ;; over the fail-limit
               (or (zero? previous-fail)  ;; check for interval only when it makes sense
                   (>= fail-window-nanos (- error-time previous-fail)))) ;; within window?
      ;; transition to OPEN immediately,
      ;; and to HALF-OPEN after <open-timeout> ms
      (swap! CBS assoc :cbs :OPEN)
      (future
        (Thread/sleep open-timeout)
        (swap! CBS assoc
               :cbs :HALF-OPEN
               :success-count 0))) ;; don't forget to reset this!
    ;; let the caller decide how to handle exceptions
    (ex-fn ex error-time fail-count)))

(defn cb-wrap-handler
  "Given a <handler> fn, returns a wrapped version
   of it (with circuit-breaker semantics).

   <CBS> - atom holding all the state required (see `cb-init`).
   <success-limit> - How many successful calls before transitioning from HALF-OPEN => CLOSED
   <drop-fn> - Function to handle all requests while in OPEN state (arg-list per <handler>).
               If a default value makes sense in your domain this is your chance to use it.                    Can be used for logging.
   <delay-fn> - Function expected to produce an artificial delay (via `Thread/sleep`) after
                each successful <handler> call (arg-list per <handler>).
   <handler> - The underlying processing handler function."
  [CBS success-limit drop-fn delay-fn handler]
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
                     (throw t))))))


(defn cb-fn
  "Given a <handler> fn, returns a circuit-breaker version of it
   using the provided options (second arg - see `cb-error-handler` & `cb-wrap-handler`)."
  [handler {:keys [fail-limit
                   fail-interval
                   success-limit
                   open-timeout
                   drop-fn
                   delay-fn
                   ex-fn]}]
  (let [CBS (atom cb-init)
        wrapped-handler (cb-wrap-handler CBS success-limit drop-fn delay-fn handler)
        time-window-nanos (* fail-interval 1000000)
        error-handler (partial cb-error-handler
                               CBS
                               [fail-limit time-window-nanos]
                               open-timeout
                               ex-fn)]
    (fn real-handler [& args]
      (try (apply wrapped-handler args)
           (catch Throwable t
             (error-handler t))))))


(defn cb-agent*
  "Returns a vector of three elements `[agent wrapper cbs-atom]`.
   <agent>    - agent implementing circuit-breaking semantics
   <wrapper>  - function to call with your send-fn as its argument - returns the correct fn to send to <agent>
   <cbs-atom> - an atom holding the internal state of the circuit-breaker (4 keys). Super useful for debugging/testing.
                Should only ever be `deref`-ed (NEVER changed)!

   Requires the following keys:

  <init-state>    - The initial state of the agent.
  <fail-limit>    - see `cb-error-handler`
  <fail-interval> - see `cb-error-handler`
  <success-limit> - see `cb-wrap-handler`
  <success-block> - Amount of artificial delay (in millis) to introduce after each successful
                    call of the fn wrapped by <wrapper>.
                  - If provided, must be accounted for in <fail-interval>. Useful as a basic rate-limiter.
  <open-timeout>  - see `cb-error-handler`
  <drop-fn>       - see `cb-wrap-handler`
  <ex-fn>         - see `cb-error-handler`

  Limitations/advice:

  - You can/should NOT change the error-handler, nor the error-mode of the returned agent.
  - You can/should NOT set a validator to the returned agent, as it will interfere with the error-handler.
  - The agent returned already carries some metadata. Make sure they don't get lost. If you want your own metadata
    on the returned agent pass it here as part of cb-params (via the :meta key).
  - De-structure the returned vector as `[agent cb-wrap _]` (i.e. ignore the third element)."
  [init {:keys [fail-limit
                fail-interval
                success-limit
                success-block
                open-timeout
                drop-fn
                ex-fn
                meta]}]
  (let [CBS (atom cb-init)
        time-window-nanos (* fail-interval 1000000)
        error-handler* (partial cb-error-handler CBS [fail-limit time-window-nanos] open-timeout ex-fn)
        ag (agent init
                  :meta (merge meta {:circuit-breaker? true})  ;; useful meta (see `print-method` for `OnAgent` record)
                  ;; providing an error-handler returns an agent with error-mode => :continue (never needs restarting)
                  :error-handler #(error-handler* %2))
        art-delay (if (and (some? success-block)
                           (pos-int? success-block))
                    (fn [& _] (Thread/sleep success-block))
                    (constantly nil))
        drop-fn* (fn [agent-state & args]
                   (apply drop-fn agent-state args)
                   agent-state)
        ag-f (partial cb-wrap-handler CBS success-limit drop-fn* art-delay)]
    ;; it is advised to ignore the last element
    ;; destructure like `[agnt cb-wrap _]` for example
    [ag ag-f CBS]))

(defn cb-agent
  "Var-arg version of `cb-agent*`, so that it resembles
   `clojure.core/agent` wrt to arg-list."
  [init & opts]
  (cb-agent* init (apply hash-map opts)))
