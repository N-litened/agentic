# agentic

A Clojure library that replaces `clojure.core/defn` for functions that should not normally throw.

On `Throwable`, `com.latypoff.agentic/defn` starts a loopback [socket REPL](https://clojure.org/reference/repl_and_main#_launching_a_socket_server) (`clojure.core.server/start-server` + `clojure.core.server/repl`) in the live process and runs a **non-interactive CLI coding agent**. The agent connects to that REPL, inspects the call, patches state at runtime, and writes a result the host honors for this invocation.

Library / Maven coordinate: **`com.latypoff.agentic`** (group `com.latypoff`, name `agentic`). This GitHub repo is [`N-litened/agentic`](https://github.com/N-litened/agentic). MIT.

Public API: **one macro**, `com.latypoff.agentic/defn`.

## Install

deps.edn only (Clojure 1.12+, for `clojure.java.process`):

```clojure
{:deps {com.latypoff/agentic
        {:git/url "https://github.com/N-litened/agentic.git"
         :git/sha "REPLACE-WITH-A-COMMIT-SHA"}}}
```

When published to Clojars the same coord is `com.latypoff/agentic`.

## Usage

```clojure
(ns my.app
  (:require [com.latypoff.agentic :as agentic]
            [com.latypoff.agentic.control :as control]))

(agentic/defn important
  "This should not normally throw."
  [x]
  (risky-work x))

;; Default agent is Grok Build (`grok`). To use another shipped CLI:
(alter-var-root #'control/agent-vendor (constantly :claude-code))  ;; or :codex, :opencode
```

Write `agentic/defn` instead of `defn` for the functions you care about. Name, docstring, attr-map, multi-arity, and pre/post maps are delegated to `clojure.core/defn`. Each arity **body** is wrapped in `try`/`catch Throwable`; pre/post maps stay outside the `try`.

Do not bake API keys into this library. The host's already-logged-in CLI is the credential (`grok login`, `claude auth`, `codex login`, …).

## What happens on throw

The catch calls `com.latypoff.agentic.impl/exception-handler` with one map:

| key | value |
| --- | --- |
| `:throwable` | the caught exception |
| `:defn-sym` | fully-qualified function name |
| `:defn-args` | parameter values for **this** invocation |
| `:ns` | `(ns-name *ns*)` at runtime |
| `:source-path` | `*source-path*` or `*file*`, both read at macro-expansion time |
| `:form` | the macro's `&form`, quoted so `:line` / `:column` metadata survive |
| `:locals` | `symbol → runtime value` for every local in the macro's `&env` |

The handler is serialized on a global mutex (one agent at a time). It:

1. `alter-var-root`s `control/current-incident` to that map and `control/current-result` to `nil` (both reset to `nil` in `finally`).
2. Starts a Clojure stdlib socket REPL on `127.0.0.1` with an ephemeral port, then stops it in `finally`.
3. Runs the selected CLI agent with a pretty-printed prompt (bounded to `*print-length*` 16 / `*print-level*` 4 so huge args cannot explode). Class, message, `ex-data`, stack trace, and form metadata are included separately.
4. If the runner reports success, returns the root value of `control/current-result`. If the runner reports failure (or `current-result` is still `nil`), returns `nil`.

The catch interprets that return:

| handler return | effect |
| --- | --- |
| `nil` (or anything else) | rethrow the original throwable |
| `{:action :throw :throwable t}` | throw `t`, discard the original |
| `{:action :return :value v}` | the function returns `v` |

## What the agent is supposed to do

The prompt tells the agent to:

1. Connect to the socket REPL (host, port, and an `nc` example are in the prompt).
2. Read `com.latypoff.agentic.control/current-incident`.
3. Inspect live state. Apply runtime patches (`alter-var-root`, `intern`, `eval`, …) that help this error and similar ones later.
4. Before exiting, set the result the host will honor:

```clojure
(alter-var-root #'com.latypoff.agentic.control/current-result
  (constantly {:action :return :value RETURN_VALUE}))
```

Leaving `current-result` nil rethrows the original exception. If the CLI harness itself completes unsuccessfully, the host also rethrows the original — the agent does not control that harness status.

## Agent selection

`com.latypoff.agentic.control/agent-vendor` is `:grok-build` by default. Keywords shipped out of the box (real headless flags, not invented CLIs):

| keyword | invocation |
| --- | --- |
| `:grok-build` | `grok --prompt-file <tempfile>` (`-p` / `--single` is the short form) |
| `:claude-code` | `claude -p` with the prompt file as the **child's** stdin |
| `:codex` | `codex exec -` with the prompt file as the **child's** stdin |
| `:opencode` | `opencode run --file <tempfile> …` |

The prompt is always written to a temp file (large incidents will not blow `ARG_MAX`). The child process does **not** inherit the host stdin, stdout, or stderr: its stdout/stderr are captured and written through `clojure.tools.logging`, and its stdin is either the prompt file or a closed pipe (EOF), never the host `*in*`.

## Logging

This library does not write to the host `*out*` / `*err*`. Operational messages and captured agent output go through [`clojure.tools.logging`](https://github.com/clojure/tools.logging) (`log/info`, `log/warn`, `log/error`). The host application supplies the logging backend (SLF4J, Log4j, `java.util.logging`, …) and therefore keeps full control of stdin/stdout/stderr.

Tests and offline fakes bind `com.latypoff.agentic.impl/*agent-runner*` to a function of the runner context map (`:host`, `:port`, `:prompt`, `:prompt-file`, `:incident`). It must return whether the run succeeded: falsy = unsuccessful, truthy = successful.

## Prompt placeholders

The root value of `com.latypoff.agentic.control/agent-prompt` is a string template. Users may `alter-var-root` it. Substituted placeholders:

| placeholder | meaning |
| --- | --- |
| `{{host}}` | socket REPL bind address (`127.0.0.1`) |
| `{{port}}` | ephemeral socket REPL port |
| `{{incident}}` | pretty-printed incident map (print bounds applied) |
| `{{class}}` | `(class throwable)` |
| `{{message}}` | `(ex-message throwable)` |
| `{{ex-data}}` | pretty-printed `(ex-data throwable)` |
| `{{stacktrace}}` | printed stack trace |
| `{{form-meta}}` | metadata of `:form` (`:line`, `:column`, …) |
| `{{connect-example}}` | concrete `nc` / socket connect snippet |

Print bounds are fixed in `impl`: `*print-length*` 16 and `*print-level*` 4.

## Namespaces

| ns | role |
| --- | --- |
| `com.latypoff.agentic` | public `defn` macro |
| `com.latypoff.agentic.impl` | `exception-handler`, socket REPL, CLI runners |
| `com.latypoff.agentic.control` | `current-incident`, `current-result`, `agent-prompt`, `agent-vendor`, `agent-socket-repl-host` |

## Offline demo

No Grok/Claude/Codex/OpenCode required:

```bash
clojure -X:demo
```

That runs `examples/heal_demo.clj`: `(divide 10 0)` throws, a fake agent connects to the live socket REPL and sets `{:action :return :value :healed}`, so the call returns `:healed`.

## Tests

```bash
clojure -M:test
```

Unit tests are offline: they stub `*agent-runner*` and include a real socket-REPL roundtrip (Java socket → `alter-var-root` of `current-result`).

## License

MIT. See [LICENSE](LICENSE).
