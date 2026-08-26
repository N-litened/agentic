(ns fake-agent
  "Offline CLI stand-in: connect to the printed socket REPL and set
  `current-result` so the throwing function returns `:healed-offline`.

  Run from the repo root:

      clojure -M:fake-agent -- 127.0.0.1 PORT

  The `--` is optional; the last two args are host and port."
  (:require [com.latypoff.agentic.impl :as impl]))

(defn- parse-args
  [args]
  (let [xs (vec (remove #{"--"} args))]
    (case (count xs)
      1 {:host "127.0.0.1" :port (Integer/parseInt (first xs))}
      2 {:host (first xs) :port (Integer/parseInt (second xs))}
      (throw (ex-info "usage: clojure -M:fake-agent -- HOST PORT" {:args args})))))

(defn -main
  [& args]
  (let [{:keys [host port]} (parse-args args)]
    (impl/socket-repl-eval
     host port
     "(alter-var-root #'com.latypoff.agentic.control/current-result (constantly {:action :return :value :healed-offline}))")
    (System/exit 0)))
