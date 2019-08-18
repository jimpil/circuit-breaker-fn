(ns circuit-breaker-fn.core
  "Main `circuit-breaker-fn` namespace. If not implementing your own constructs, this is all you need (otherwise see primitives.clj)."
  (:require [circuit-breaker-fn
             [primitives :as prim]
             [validation :as v]
             [util :as ut]])
  (:import [java.util.concurrent.locks ReentrantLock]))

(defn cb-fn*
  "Given a <handler> fn, returns a circuit-breaker version of it using the  provided <cb-opts>.

   <cb-opts> is expected to contain:

     :fail-limit    - see `primitives/cb-error-handler`
     :fail-window   - Time-window in which :fail-limit has an effect (per `:fail-window-unit`).
     :success-limit - see `primitives/cb-wrap-handler`
     :open-timeout  - see `primitives/cb-error-handler`
     :drop-fn       - see `primitives/cb-wrap-handler`
     :ex-fn         - see `primitives/cb-error-handler`

   Can optionally contain:

     :fail-window-unit - Time-unit for :fail-window (see keys of `utils/time-units`).
     :timeout-unit     - Time-unit for :open-timeout (see keys of `utils/time-units`).
     :locking?         - boolean indicating whether <handler> should be called after
                         successfully acquiring a lock (will wait).
     :try-locking?     - boolean indicating whether <handler> should be called after
                         trying to acquire a lock (will NOT wait).

   Providing both `locking?` & `try-locking?` doesn't make much sense, so `locking?`
   takes precedence. In a typical circuit-breaker scenario, none of them would be
   needed, simply because <handler> (and its usage) will typically not be racy."
  [handler {:keys [fail-limit
                   fail-window
                   fail-window-unit
                   success-limit
                   open-timeout
                   timeout-unit
                   drop-fn
                   success-block
                   ex-fn
                   locking?
                   try-locking?]
            :as cb-opts}]
  (v/validate! cb-opts)

  (let [CBS (atom prim/cb-init)
        window-nanos (ut/nanos-from (or fail-window-unit :millis) fail-window)
        timeout-unit (ut/time-units (or timeout-unit :millis))
        error-handler (partial prim/cb-error-handler
                               CBS
                               [fail-limit window-nanos]
                               [open-timeout timeout-unit]
                               ex-fn)
        cb-handler (prim/cb-wrap-handler CBS success-limit drop-fn success-block handler)
        lock (when (or locking? try-locking?) (ReentrantLock.))]
    (if (nil? lock)
      (fn real-handler [& args]
        ;; neither locking, nor try-locking
        (try (apply cb-handler args)
             (catch Throwable t
               (error-handler t))))
      (if locking?
        (fn real-handler [& args]
          (try ;; potentially wait for a lock
            (ut/with-lock lock (apply cb-handler args))
            (catch Throwable t
              (error-handler t))))
        (fn real-handler [& args]
          (try ;; try to acquire but don't wait for a lock
            (ut/with-try-lock lock (apply cb-handler args))
            (catch Throwable t
              (error-handler t))))))))

(defn cb-fn
  "Var-arg version of `cb-fn*`."
  [handler & opts]
  (cb-fn* handler (apply hash-map opts)))


(defn cb-agent*
  "Returns a vector of three elements `[agent wrapper cbs-atom]`.
   <agent>    - agent implementing circuit-breaking semantics (initialised with <init>).
   <wrapper>  - function to call with your send-fn as its argument - returns the correct fn to send to <agent>.
   <cbs-atom> - an atom holding the internal state of the circuit-breaker.
                Super useful for debugging/testing. Should only ever be `deref`-ed (NEVER changed)!

   <cb-opts> is expected to contain:

    :fail-limit    - see `primitives/cb-error-handler`
    :fail-window   - Time-window in which :fail-limit has an effect (per `:fail-window-unit`).
    :success-limit - see `primitives/cb-wrap-handler`
    :open-timeout  - see `primitives/cb-error-handler`
    :drop-fn       - see `primitives/cb-wrap-handler`
    :ex-fn         - see `primitives/cb-error-handler`

  Can optionally contain:

  :fail-window-unit - Time-unit for :fail-window (see keys of `utils/time-units`).
  :timeout-unit     - Time-unit for :open-timeout (see keys of `utils/time-units`).
  :success-block    - see `primitives/cb-wrap-handler`
  :meta             - a subset of the agent's final metadata

  Limitations/advice:

  - You can NOT change the error-handler, nor the error-mode of the returned agent.
  - You can/should NOT set a validator to the returned agent, unless you do want errors validation errors to
    participate in the circuit-breaker, otherwise it will interfere with the agent's custom error-handler.
  - The agent returned already carries some metadata. Make sure they don't get lost. If you want your own
    metadata on the returned agent pass it here as part of cb-params (via the :meta key).
  - De-structure the returned vector as `[agent cb-wrap]` (i.e. ignore the third element)."
  [init {:keys [fail-limit
                fail-window
                fail-window-unit
                success-limit
                success-block
                open-timeout
                timeout-unit
                drop-fn
                ex-fn
                meta]
         :as cb-opts}]
  (v/validate! cb-opts)

  (let [CBS (atom prim/cb-init)
        window-nanos (ut/nanos-from (or fail-window-unit :millis) fail-window)
        timeout-unit (ut/time-units (or timeout-unit :millis))
        error-handler* (partial prim/cb-error-handler
                                CBS
                                [fail-limit window-nanos]
                                [open-timeout timeout-unit]
                                ex-fn)
        ag (agent init
                  ;; useful meta for identifying this special agent
                  :meta (merge meta {::cb? true})
                  ;; providing an error-handler returns an agent
                  ;; with error-mode => :continue (never needs restarting)
                  :error-handler #(error-handler* %2))
        drop-fn* (fn [agent-state & args]
                   (apply drop-fn agent-state args)
                   agent-state)
        ag-f (partial prim/cb-wrap-handler CBS success-limit drop-fn* success-block)]
    ;; it is advised to ignore the last element
    ;; destructure like `[agnt cb-wrap _]` for example
    [ag ag-f CBS]))

(defn cb-agent
  "Var-arg version of `cb-agent*`."
  [init & opts]
  (cb-agent* init (apply hash-map opts)))
