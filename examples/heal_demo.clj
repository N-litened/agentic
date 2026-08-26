(ns heal-demo
  "Heal-loop demo.

      clojure -X:demo                         ; offline fake runner
      clojure -X:demo :vendor :grok-build
      clojure -X:demo :vendor :claude-code
      clojure -X:demo :vendor :codex
      clojure -X:demo :vendor :opencode

  `average` is intentionally broken (`count` used as a divisor).
  Without :vendor, a fake agent connects to the live socket REPL and
  sets `:action :return`, so the call returns `:healed`. With :vendor,
  that CLI is invoked instead."
  (:require [com.latypoff.agentic :as agentic]
            [com.latypoff.agentic.control :as control]
            [com.latypoff.agentic.impl :as impl]))

(agentic/defn average
  "Return average of a collection of numbers"
  [collection]
  (let [n (count collection)
        avg (/ (reduce + collection) count)]
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

(defn- coerce-vendor
  [vendor]
  (cond
    (nil? vendor) nil
    (keyword? vendor) vendor
    (string? vendor) (keyword vendor)
    :else (throw (ex-info ":vendor must be a keyword or string"
                          {:vendor vendor}))))

(defn run
  [{:keys [vendor]}]
  (let [vendor (coerce-vendor vendor)]
    (when (and vendor (not (#{:grok-build :claude-code :codex :opencode} vendor)))
      (throw (ex-info "Unknown :vendor"
                      {:vendor vendor
                       :allowed #{:grok-build :claude-code :codex :opencode}})))
    (println "Calling (average [1 2 3]) ...")
    (if vendor
      (do
        (alter-var-root #'control/agent-vendor (constantly vendor))
        (println "control/agent-vendor:" control/agent-vendor)
        (let [result (average [1 2 3])]
          (println "Result:" result)
          result))
      (do
        (println "No :vendor — using offline fake runner")
        (binding [impl/*agent-runner* fake-runner]
          (let [result (average [1 2 3])]
            (println "Result:" result)
            (when-not (= :healed result)
              (throw (ex-info "offline heal demo failed" {:result result})))
            result))))))
