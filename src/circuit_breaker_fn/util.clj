(ns circuit-breaker-fn.util
  (:import [java.util.concurrent.locks ReentrantLock]))

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
