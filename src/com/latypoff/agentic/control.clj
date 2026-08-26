(ns com.latypoff.agentic.control
  "Host-tunable vars for the runtime heal loop.

  Bind or `alter-var-root` these from the integrating process. Do not put
  API keys here — the already-logged-in CLI coding agent is the credential."
  (:refer-clojure :exclude [agent]))

(def current-exception
  "Incident map for the exception currently being handled, or nil.
  The CLI agent should read this through the socket REPL."
  nil)

(def current-result
  "Result map written by the CLI agent before it exits, or nil.

  Recognized shapes (see `com.latypoff.agentic.impl/apply-handler-result`):
    {:action :return :value RETURN_VALUE}
    {:action :throw :throwable REPLACEMENT}

  Nil (the default) means rethrow the original throwable."
  nil)

(def agent
  "Which coding agent to invoke. One of:

    :grok-build   (default)  `grok --prompt-file <file>`
    :claude-code             `claude -p` with the prompt on stdin
    :codex                   `codex exec -` with the prompt on stdin
    :opencode                `opencode run --file <file> ...`

  May also be a function of one argument (the runner context map) that
  returns a process exit code. Tests bind `com.latypoff.agentic.impl/*agent-runner*`
  instead of replacing this var."
  :grok-build)

(def socket-host
  "Address the socket REPL binds and advertises. Default 127.0.0.1."
  "127.0.0.1")

(def print-length
  "Bound to `*print-length*` when pretty-printing incident data."
  32)

(def print-level
  "Bound to `*print-level*` when pretty-printing incident data."
  6)

(def agent-prompt
  "Default prompt template given to the CLI coding agent.

  Placeholders substituted by `com.latypoff.agentic.impl/render-prompt`:

    {{host}}             socket REPL bind address
    {{port}}             socket REPL port (ephemeral, chosen at runtime)
    {{incident}}         pretty-printed incident map (print-length/level bounded)
    {{class}}            `(class throwable)`
    {{message}}          `(ex-message throwable)`
    {{ex-data}}          pretty-printed `(ex-data throwable)`
    {{stacktrace}}       printed stack trace
    {{form-meta}}        pretty-printed metadata of `:form` (`:line`, `:column`, …)
    {{connect-example}}  concrete `nc` / `clj` connect snippet

  Replace this root value with `alter-var-root` to supply your own template.
  Keep the same placeholders if you still want incident details injected."
  "You are a coding agent attached to a LIVE Clojure JVM that just hit an
exception in a function defined with `com.latypoff.agentic/defn`. Your job
is to inspect the live process, decide how THIS invocation should finish,
and leave a result the host will honor when you exit.

## Socket REPL (do this first)

A Clojure stdlib socket REPL (`clojure.core.server/repl`) is listening on:

    host: {{host}}
    port: {{port}}

Connect and evaluate live code. Example:

{{connect-example}}

The REPL starts in the `user` namespace. Use fully-qualified names, or
`(require '[com.latypoff.agentic.control :as ctl])`.

## What you must do

1. Read `com.latypoff.agentic.control/current-exception` — the incident map
   for this call (`:throwable`, `:defn-sym`, `:defn-args`, `:ns`,
   `:source-path`, `:form`, `:locals`).
2. Inspect process state. Apply runtime patches if they will help this
   error and similar ones later (`alter-var-root`, `intern`, `eval`,
   `require` :reload, etc.).
3. BEFORE you exit, set the result the host will interpret:

       (alter-var-root #'com.latypoff.agentic.control/current-result
         (constantly {:action :return :value RETURN_VALUE}))

   or, to throw a different exception:

       (alter-var-root #'com.latypoff.agentic.control/current-result
         (constantly {:action :throw :throwable REPLACEMENT}))

   Leaving `current-result` nil (or exiting non-zero) rethrows the original.
4. Exit 0 when you have set `current-result` (or have decided to let the
   original exception propagate).

Do not ask the human for API keys. You are already authenticated as this CLI.

## Exception (also printed separately so huge args cannot hide it)

class:      {{class}}
message:    {{message}}
ex-data:
{{ex-data}}

stacktrace:
{{stacktrace}}

defining form metadata (`:line` / `:column` of the `defn`):
{{form-meta}}

## Incident map (pretty-printed, *print-length* / *print-level* bounded)

{{incident}}
")
