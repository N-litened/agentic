(ns heal-demo
  "Heal-loop demo.

      clojure -X:demo                         ; offline fake runner
      clojure -X:demo :vendor :grok-build
      clojure -X:demo :vendor :claude-code
      clojure -X:demo :vendor :codex
      clojure -X:demo :vendor :opencode

  `average` is intentionally broken (`count` used as a divisor).
  Without :vendor, a fake agent connects to the live socket REPL,
  re-evaluates a corrected function body, and returns the computed
  average for the throwing call. With :vendor, that CLI is invoked
  instead."
  (:require [com.latypoff.agentic :as agentic]
            [com.latypoff.agentic.control :as control]
            [com.latypoff.agentic.impl :as impl]))

(agentic/defn average
  "Return average of a collection of numbers"
  [collection]
  (let [n (count collection)
        avg (/ (reduce + collection) count)]
    (if (pos? n) avg nil)))

(def ^:private demo-inputs
  [[1 2 3 4 5]
   []
   ["1" "2" "3" "4" "5"]])

(def ^:private expected-offline
  [3 nil 3])

(defn- fake-runner
  "Same contract as a real runner: connect to the printed port,
  eval live code, return truthy on success.

  Re-evaluates a corrected `average` (numbers, empty, numeric strings)
  and sets `:action :return` to that value for this invocation."
  [{:keys [host port]}]
  (println "fake-agent: connecting to" (str host ":" port))
  (impl/socket-repl-eval
   host port
   (str
    "(do"
    "  (alter-var-root #'heal-demo/average"
    "    (constantly"
    "      (fn [collection]"
    "        (let [nums (map (fn [x]"
    "                          (cond"
    "                            (number? x) x"
    "                            (string? x) (let [n (read-string x)]"
    "                                          (if (number? n)"
    "                                            n"
    "                                            (throw (ex-info \"not a number\" {:x x}))))"
    "                            :else (throw (ex-info \"not a number\" {:x x}))))"
    "                        collection)"
    "              n (count nums)]"
    "          (when (pos? n)"
    "            (/ (reduce + nums) n))))))"
    "  (let [args (:defn-args com.latypoff.agentic.control/current-incident)]"
    "    (alter-var-root #'com.latypoff.agentic.control/current-result"
    "      (constantly {:action :return :value (apply heal-demo/average args)}))))"))
  (println "fake-agent: re-evaluated average and set current-result")
  true)

(defn- coerce-vendor
  [vendor]
  (cond
    (nil? vendor) nil
    (keyword? vendor) vendor
    (string? vendor) (keyword vendor)
    :else (throw (ex-info ":vendor must be a keyword or string"
                          {:vendor vendor}))))

(defn- print-average
  [coll]
  (let [result (average coll)]
    (println (str "(average " (pr-str coll) ") =>") (pr-str result))
    result))

(defn run
  [{:keys [vendor]}]
  (let [vendor (coerce-vendor vendor)]
    (when (and vendor (not (#{:grok-build :claude-code :codex :opencode} vendor)))
      (throw (ex-info "Unknown :vendor"
                      {:vendor vendor
                       :allowed #{:grok-build :claude-code :codex :opencode}})))
    (if vendor
      (do
        (alter-var-root #'control/agent-vendor (constantly vendor))
        (println "control/agent-vendor:" control/agent-vendor)
        (mapv print-average demo-inputs))
      (do
        (println "No :vendor — using offline fake runner")
        (binding [impl/*agent-runner* fake-runner]
          (let [results (mapv print-average demo-inputs)]
            (when-not (= expected-offline results)
              (throw (ex-info "offline heal demo failed"
                              {:results results
                               :expected expected-offline})))
            results))))))
