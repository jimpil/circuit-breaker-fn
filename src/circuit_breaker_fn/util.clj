(ns circuit-breaker-fn.util
  (:import [java.util.concurrent.locks ReentrantLock]
           (java.util.concurrent TimeUnit)))

(defmacro with-lock
  "Runs <body> after calling `.lock()` on the provided lock."
  [lock-expr & body]
  `(let [lockee# ~(with-meta lock-expr {:tag 'java.util.concurrent.locks.ReentrantLock})]
     (.lock lockee#)
     (try ~@body
          (finally
            (.unlock lockee#)))))


(defmacro with-try-lock
  "Same as `with-lock`, but uses `tryLock()` instead,
   and therefore may end up not running <body> (i.e. doesn't wait)."
  [lock-expr & body]
  `(let [lockee# ~(with-meta lock-expr {:tag 'java.util.concurrent.locks.ReentrantLock})]
     (and (.tryLock lockee#)
          (try ~@body
               (finally
                 (.unlock lockee#))))))

(defonce time-units
  {:micros  TimeUnit/MICROSECONDS
   :millis  TimeUnit/MILLISECONDS
   :seconds TimeUnit/SECONDS
   :minutes TimeUnit/MINUTES
   :hours   TimeUnit/HOURS
   :days    TimeUnit/DAYS})

(defn nanos-from
  "Returns the nanoseconds in <n> time <unit>."
  ^long [unit n]
  (.convert TimeUnit/NANOSECONDS n (time-units unit)))
