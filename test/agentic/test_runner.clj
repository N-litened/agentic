(ns agentic.test-runner
  (:require [clojure.test :as t]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (t/run-tests 'agentic.core-test)]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))
