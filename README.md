# Agentic

A small Clojure library for **agentic self-healing of Clojure code at runtime**.

When a var throws, Agentic captures the failure as data, asks a healer you inject for a patch, hot-reloads the var in the running process, and can retry the call — without restarting the JVM. Every applied patch is journaled so you can inspect it and roll it back.

This is a library, not an app or a coding agent.

```clojure
(require '[agentic.core :as agentic])

(def rt
  (agentic/runtime
   {:healer (fn [incident]
              {:var (:var incident)
               :source "(fn [x y] (if (zero? y) :inf (/ x y)))"
               :note "guard zero"})}))

(agentic/invoke rt {:var #'app.math/div :args [10 0]})
;; => :inf

(agentic/history rt)
(agentic/rollback! rt #'app.math/div)
```

## Why runtime self-heal?

Clojure already has the pieces most languages bolt on later: a reloadable runtime, immutable data, and explicit refs. A failure is just a value. A proposed fix can be a value too — a map you can print, store, refuse, or apply.

That combination makes a tight loop possible:

**wrap / try → incident map → healer → validate → intern + history → optional retry**

Treating patches as data is what keeps the agent from destroying work. The previous definition stays on the journal. Rollback is a function, not a reboot.

v1 stays on the JVM. CLJS is out of scope.

## Install

GitHub coord (tools.deps):

```clojure
{:deps {io.github.n-litened/agentic
        {:git/sha "REPLACE-WITH-A-COMMIT-SHA"}}}
```

Clojure 1.11+ / 1.12, Java 11+. No other dependencies.

```
clojure -M:test
```

## API walkthrough

One namespace: `agentic.core`. The healer is a function. There is no vendor SDK and no env-var API key.

### Runtime

```clojure
(def rt
  (agentic/runtime
   {:healer my-healer   ;; (fn [incident] patch-or-nil)
    :retry? true}))     ;; default for invoke
```

Each runtime has its own history atom. `agentic/stub-healer` always declines and is the default. `agentic/constant-healer` is a test helper that always returns the same patch.

### Incident

```clojure
(try
  (app.math/div 10 0)
  (catch Throwable t
    (agentic/incident t {:var #'app.math/div :args [10 0]})))
```

An incident is a map:

```clojure
{:id "…"
 :at "2026-08-26T10:00:00Z"
 :var app.math/div
 :ns app.math
 :args [10 0]
 :source "(defn div [x y] (/ x y))"   ;; when known
 :meta {:arglists ([x y]) :file "…" :line 12}
 :exception {:class "java.lang.ArithmeticException"
             :message "Divide by zero"
             :data nil
             :stack ["…" "…"]}}
```

Source is taken from `clojure.repl/source-fn` when the var was loaded from a file, or from `:agentic/source` metadata left by a previous patch.

### Patch

A healer returns `nil`, `false`, `:decline`, or `{:decline true}` to do nothing. Anything else must be a patch map:

```clojure
{:var app.math/div          ;; var or qualified symbol
 :source "(fn [x y] …)"     ;; exactly one of :source, :form, :fn
 :note "optional rationale"}
```

| key      | meaning                                      |
|----------|----------------------------------------------|
| `:source`| string, `read-string` then `eval`            |
| `:form`  | quoted form, `eval`'d in the var's ns        |
| `:fn`    | an already-built IFn (handy in tests)        |

`validate-patch` checks this shape. Bad patches throw and do not touch the var.

### Apply, heal, invoke

```clojure
;; You already have a patch you trust:
(agentic/apply-patch! rt patch)

;; Capture + healer + validate + apply:
(agentic/heal! rt incident)

;; The whole loop, including optional retry:
(agentic/invoke rt {:var #'app.math/div :args [10 0] :retry? true})

;; Same loop as a function:
((agentic/wrap rt #'app.math/div) 10 0)
```

`invoke` rethrows the original throwable when the healer declines, the patch is rejected, or the healer itself throws. A successful apply plus `:retry? true` retries the same args against the new root.

### History and rollback

```clojure
(agentic/history rt)
;; [{:status :applied, :var app.math/div, :patch {…}, :incident {…}}
;;  {:status :retried, :ok? true, …}]

(agentic/rollback! rt #'app.math/div)
```

The journal is append-only, oldest first, and `pr-str`/`read-string` round-trips. Live objects (`:fn`, the previous IFn) are stripped from `history`. Rollback walks the last applied, not-yet-rolled-back entry for that var and restores its previous root.

Statuses: `:applied`, `:declined`, `:rejected`, `:healer-error`, `:retried`, `:rolled-back`.

## How to inject a model

Agentic does not talk to the network. The host wraps whatever client it already has:

```clojure
(defn llm-healer [complete]
  (fn [incident]
    (read-string
     (complete
      (str "A Clojure var failed. Return EDN only:\n"
           "  {:var fully.qualified/sym :source \"(fn […] …)\"}\n"
           "or {:decline true}.\n\n"
           (pr-str incident))))))

(def rt
  (agentic/runtime
   {:healer (llm-healer my.llm/complete)}))
```

A slightly fuller sketch lives in [`examples/llm_healer.clj`](examples/llm_healer.clj). Keep credentials and HTTP in the host.

## What this will not do

- **Not a coding agent.** No tools, no repo walk, no planner, no chat UI.
- **Not a sandbox.** `eval` / `alter-var-root` run in your process with your privileges. A bad patch can do anything a REPL can do.
- **Not magic.** If the healer declines, the original exception still throws. If the retry still fails, that exception throws. Nothing is swept under the rug.
- **Not a persistence layer.** History lives in an atom on the runtime you created. Restart the JVM and it is gone unless you store it.
- **Not CLJS, not a framework.** One namespace, one loop, JVM only.

Apply patches you have read. Rollback is there because you will need it.

## License

MIT. Copyright © 2026 Timur Latypoff.
