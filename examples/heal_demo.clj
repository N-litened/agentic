(ns heal-demo
  "Offline heal-loop demo. No Grok/Claude/Codex/OpenCode required.

      clojure -X:demo

  Starts `divide`, which throws on `/ 10 0`. A fake agent connects to
  the live socket REPL and sets `:action :return`, so the call returns
  `:healed` instead of throwing."
  (:require [com.latypoff.agentic.control :as control]
            [com.latypoff.agentic.impl :as impl]
            [divide :refer [divide]]))

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
  [_]
  (println "control/agent-vendor default:" control/agent-vendor)
  (println "Calling (divide 10 0) with an offline fake agent...")
  (binding [impl/*agent-runner* fake-runner]
    (let [result (divide 10 0)]
      (println "Result:" result)
      (when-not (= :healed result)
        (throw (ex-info "offline heal demo failed" {:result result})))
      result)))
