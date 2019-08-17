(ns circuit-breaker-fn.validation
  (:require [clojure.spec.alpha :as s]
            [circuit-breaker-fn.util :as ut])
  (:import (java.util.concurrent TimeUnit)))

(s/def ::fail-limit    pos-int?)
(s/def ::fail-window   pos-int?)
(s/def ::success-limit pos-int?)
(s/def ::open-timeout  pos-int?)
(s/def ::drop-fn       fn?)
(s/def ::ex-fn         fn?)
(s/def ::locking?      boolean?)
(s/def ::try-locking?  boolean?)
(s/def ::meta          map?)
(s/def ::timeout-unit      (set (keys ut/time-units)))
(s/def ::fail-window-unit  (set (keys ut/time-units)))
(s/def ::success-block
  (s/or :sleep-millis (complement neg?)
        :block-fn     fn?))

(s/def ::cb-opts
  (s/keys :req-un [::fail-limit
                   ::fail-window
                   ::success-limit
                   ::open-timeout
                   ::drop-fn
                   ::ex-fn]
          :opt-un [::success-block
                   ::locking?
                   ::try-locking?
                   ::timeout-unit
                   ::fail-window-unit
                   ::meta]))

(defn validate!
  [opts]
  (when-some [problems (s/explain-data ::cb-opts opts)]
    (throw
      (ex-info "Non-conforming parameters provided..." problems))))
