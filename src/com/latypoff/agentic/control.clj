(ns com.latypoff.agentic.control
  "Host-tunable vars for the runtime heal loop.

  Bind or `alter-var-root` these from the integrating process. Do not put
  API keys here — the already-logged-in CLI coding agent is the credential.")

(def current-incident
  "Incident map for the exception currently being handled, or nil.
  The CLI agent should read this through the socket REPL."
  nil)

(def current-result
  "Result map written by the CLI agent before it finishes, or nil.

  Recognized shapes (interpreted in the `catch` of `com.latypoff.agentic/defn`):
    {:action :return :value RETURN_VALUE}
    {:action :throw :throwable REPLACEMENT}

  Nil (the default) means rethrow the original throwable."
  nil)

(def agent-vendor
  "Which coding-agent CLI to invoke. One of:

    :grok-build   (default)  `grok --prompt-file <file>`
    :claude-code             `claude -p` with the prompt on the child's stdin
    :codex                   `codex exec -` with the prompt on the child's stdin
    :opencode                `opencode run --file <file> ...`

  Tests bind `com.latypoff.agentic.impl/*agent-runner*` instead of this var."
  :grok-build)

(def agent-socket-repl-host
  "Address the socket REPL binds and advertises. Default 127.0.0.1."
  "127.0.0.1")

(def agent-prompt
  "Default prompt template given to the CLI coding agent.

  Placeholders substituted when the prompt is built:

    {{host}}             socket REPL bind address
    {{port}}             socket REPL port (ephemeral, chosen at runtime)
    {{incident}}         pretty-printed incident map (*print-length* 16 / *print-level* 4)
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
and leave a result the host will honor.

## Socket REPL (do this first)

A Clojure stdlib socket REPL (`clojure.core.server/repl`) is listening on:

    host: {{host}}
    port: {{port}}

Connect and evaluate live code. Example:

{{connect-example}}

The REPL starts in the `user` namespace. Use fully-qualified names, or
`(require '[com.latypoff.agentic.control :as ctl])`.

## What you must do

1. Read `com.latypoff.agentic.control/current-incident` — the incident map
   for this call (`:throwable`, `:defn-sym`, `:defn-args`, `:ns`,
   `:source-path`, `:form`, `:locals`).
2. Inspect process state. Apply runtime patches if they will help this
   error and similar ones later (`alter-var-root`, `intern`, `eval`,
   `require` :reload, etc.).
3. Set the result the host will interpret:

       (alter-var-root #'com.latypoff.agentic.control/current-result
         (constantly {:action :return :value RETURN_VALUE}))

   or, to throw a different exception:

       (alter-var-root #'com.latypoff.agentic.control/current-result
         (constantly {:action :throw :throwable REPLACEMENT}))

   Leaving `current-result` nil rethrows the original throwable.
   You do not control the CLI harness exit status. If that harness
   completes unsuccessfully, the host ignores `current-result` and
   rethrows the original throwable.

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

## Incident map (pretty-printed, *print-length* 16 / *print-level* 4)

{{incident}}
")
