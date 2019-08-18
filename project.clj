(defproject circuit-breaker-fn "0.1.5-SNAPSHOT"
  :description "Reusable circuit-breaker primitives in Clojure"
  :url "https://github.com/jimpil/circuit-breaker-fn"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies []
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]]}}
  :repl-options {:init-ns circuit-breaker-fn.core})
