(ns heal-demo
  "Heal-loop demo.

      clojure -X:demo                         ; offline fake runner
      clojure -X:demo :vendor :grok-build
      clojure -X:demo :vendor :claude-code
      clojure -X:demo :vendor :codex
      clojure -X:demo :vendor :opencode

  `average` is still buggy: empty input throws in `reduce` before the
  `pos?` guard, and non-numeric elements fail when added.
  Without :vendor, a fake agent connects to the live socket REPL and
  sets `:action :return`, so each throwing call returns `:healed`.
  With :vendor, that CLI is invoked instead."
  (:require [com.latypoff.agentic :as agentic]
            [com.latypoff.agentic.control :as control]
            [com.latypoff.agentic.impl :as impl]))

(agentic/defn average
  "Return average of a collection of numbers"
  [collection]
  (let [n (count collection)
        avg (/ (reduce + collection) n)]
    (if (pos? n) avg nil)))

(defn- fake-runner
  "Same contract as a real runner: connect to the printed port,
  eval live code, return truthy on success."
  [{:keys [host port]}]
  (println "fake-agent: connecting to" (str host ":" port))
  (impl/socket-repl-eval
   host port
   "(alter-var-root #'com.latypoff.agentic.control/current-result (constantly {:action :return :value :healed}))")
  (println "fake-agent: set current-result to {:action :return :value :healed}")
  true)

(defn run
  [{:keys [vendor]}]
  (when vendor
    (alter-var-root #'control/agent-vendor (constantly vendor))
    (println "control/agent-vendor:" control/agent-vendor))
  (let [call (fn []
               (doseq [coll [[1 2 3 4 5]
                             []
                             ["1" "2" "3" "4" "5"]]]
                 (println (str "(average " (pr-str coll) ") =>")
                          (pr-str (average coll)))))]
    (if vendor
      (call)
      (do
        (println "No :vendor — using offline fake runner")
        (binding [impl/*agent-runner* fake-runner]
          (call))))))
