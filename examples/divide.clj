(ns divide
  "Toy function that throws. Swap `defn` for `agentic/defn` so a CLI
  coding agent can inspect the live process and decide the outcome."
  (:require [com.latypoff.agentic :as agentic]))

(agentic/defn divide
  "Integer division. Throws on divide-by-zero unless an agent heals it."
  [n d]
  (/ n d))
