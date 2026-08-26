(ns com.latypoff.agentic.test-runner
  (:require [clojure.test :as t]
            [com.latypoff.agentic-test]
            [com.latypoff.agentic.impl-test]))

(defn -main
  [& _]
  (let [report (t/run-all-tests #"^com\.latypoff\.agentic.*-test$")]
    (System/exit (if (and (zero? (:fail report))
                          (zero? (:error report)))
                   0
                   1))))
