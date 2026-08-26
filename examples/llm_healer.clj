(ns agentic.example
  "Thin sketch of how a host wraps an LLM. Not on the library classpath.

  Agentic never opens a socket and never reads API keys. You supply a
  `complete` function — (fn [prompt] string) — that talks to whatever
  model you already use. The model must return an EDN patch map."
  (:require [agentic.core :as agentic]))

(def prompt-prefix
  (str
   "A Clojure var failed at runtime. Reply with EDN only, no markdown.\n"
   "Return either {:decline true} or a patch:\n"
   "  {:var fully.qualified/sym\n"
   "   :source \"(fn [args...] body)\"\n"
   "   :note \"one-line rationale\"}\n"
   "The :source form will be eval'd and interned over the live var.\n"
   "Incident:\n"))

(defn llm-healer
  "Turn a host-provided completion fn into an Agentic healer.

  `complete` is (fn [prompt-string] edn-string). Keep credentials in
  the host; do not close over env-var names inside Agentic."
  [complete]
  (fn [incident]
    (let [raw (complete (str prompt-prefix (pr-str incident)))]
      (read-string raw))))

(comment
  ;; Host wiring, not part of the library:
  (defn complete-with-your-client [prompt]
    ;; (your.llm/chat {:messages [{:role :user :content prompt}]})
    "{:decline true}")

  (def rt
    (agentic/runtime {:healer (llm-healer complete-with-your-client)}))

  (agentic/invoke rt {:var #'some.ns/handler :args [{:id 1}]}))
