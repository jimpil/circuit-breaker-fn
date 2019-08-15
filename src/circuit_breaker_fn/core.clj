(ns circuit-breaker-fn.core
  "See https://docs.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker
   for a neat article with diagrams and everything."
  (:require [circuit-breaker-fn.primitives :as prim]))

(defn cb-fn
  "Given a <handler> fn, returns a circuit-breaker version of it
   using the provided <cb-opts> (see `cb-error-handler`/`cb-wrap-handler` for details)."
  [handler {:keys [fail-limit
                   fail-interval
                   success-limit
                   open-timeout
                   drop-fn
                   delay-fn
                   ex-fn]
            :as cb-opts}]
  (let [CBS (atom prim/cb-init)
        time-window-nanos (* fail-interval 1000000)
        error-handler (partial prim/cb-error-handler
                               CBS
                               [fail-limit time-window-nanos]
                               open-timeout
                               ex-fn)
        cb-handler (prim/cb-wrap-handler CBS success-limit drop-fn delay-fn handler)]
    (fn real-handler [& args]
      (try (apply cb-handler args)
           (catch Throwable t
             (error-handler t))))))


(defn cb-agent*
  "Returns a vector of three elements `[agent wrapper cbs-atom]`.
   <agent>    - agent implementing circuit-breaking semantics
   <wrapper>  - function to call with your send-fn as its argument - returns the correct fn to send to <agent>
   <cbs-atom> - an atom holding the internal state of the circuit-breaker (4 keys).
                Super useful for debugging/testing.
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

  Can optionally take a :meta key

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
  (let [CBS (atom prim/cb-init)
        time-window-nanos (* fail-interval 1000000)
        error-handler* (partial prim/cb-error-handler
                                CBS
                                [fail-limit time-window-nanos]
                                open-timeout
                                ex-fn)
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
        ag-f (partial prim/cb-wrap-handler CBS success-limit drop-fn* art-delay)]
    ;; it is advised to ignore the last element
    ;; destructure like `[agnt cb-wrap _]` for example
    [ag ag-f CBS]))

(defn cb-agent
  "Var-arg version of `cb-agent*`, so that it resembles
   `clojure.core/agent` wrt to arg-list."
  [init & opts]
  (cb-agent* init (apply hash-map opts)))
