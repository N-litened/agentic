(ns com.latypoff.agentic.impl
  "Runtime heal loop: socket REPL + CLI coding agent.

  Public entry: `exception-handler`. Tests bind `*agent-runner*` to avoid
  invoking a real `grok`/`claude`/`codex`/`opencode` process."
  (:require [clojure.core.server :as server]
            [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stack]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.latypoff.agentic.control :as control])
  (:import [java.net InetSocketAddress ServerSocket Socket]))

(set! *warn-on-reflection* true)

(def ^:private handler-lock
  "Global mutex. Only one CLI agent runs at a time."
  (Object.))

(def ^:dynamic *agent-runner*
  "If bound to a fn, it is invoked instead of the CLI selected by
  `control/agent-vendor`. The fn receives the runner context map and must
  return whether the run succeeded: falsy = unsuccessful, truthy = successful."
  nil)

(defn bounded-pr-str
  "Pretty-print `x` with `*print-length*` 16 / `*print-level*` 4 so huge
  args or locals cannot explode the prompt."
  [x]
  (binding [*print-length* 16
            *print-level* 4
            *print-meta* false
            pprint/*print-right-margin* 100
            pprint/*print-miser-width* 40
            pprint/*print-pprint-dispatch* pprint/simple-dispatch]
    (str/trimr (with-out-str (pprint/pprint x)))))

(defn build-prompt
  "Render `control/agent-prompt` for this incident and socket."
  [incident host port]
  (let [t (:throwable incident)
        values {:host host
                :port port
                :incident (bounded-pr-str incident)
                :class (if t (str (class t)) "nil")
                :message (when t (ex-message t))
                :ex-data (bounded-pr-str (when t (ex-data t)))
                :stacktrace (if t (with-out-str (stack/print-cause-trace t)) "")
                :form-meta (bounded-pr-str (meta (:form incident)))
                :connect-example
                (str "    # netcat — type forms, then :repl/quit\n"
                     "    nc " host " " port "\n"
                     "\n"
                     "    # one-shot eval\n"
                     "    printf '%s\\n' \\\n"
                     "      '(require '\\''[com.latypoff.agentic.control :as ctl])' \\\n"
                     "      'ctl/current-incident' \\\n"
                     "      '(alter-var-root #'\\''com.latypoff.agentic.control/current-result'\n"
                     "      '   (constantly {:action :return :value :healed}))' \\\n"
                     "      ':repl/quit' | nc " host " " port "\n"
                     "\n"
                     "    # from another Clojure process (socket, not nREPL)\n"
                     "    # (io/writer) / (io/reader) on (java.net.Socket. \"" host "\" " port ")")}]
    (str/replace (str (var-get #'control/agent-prompt))
                 #"\{\{([a-z0-9-]+)\}\}"
                 (fn [[full k]]
                   (if (contains? values (keyword k))
                     (str (get values (keyword k)))
                     full)))))

(defn socket-repl-eval
  "Connect to a `clojure.core.server/repl` at `host`:`port`, send `form-str`
  then `:repl/quit`. Retries while the accept thread is coming up.

  Returns true when the form was written. Throws if the port never accepts
  a connection before `timeout-ms`."
  [host port form-str & {:keys [timeout-ms] :or {timeout-ms 15000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        addr (InetSocketAddress. ^String host (int port))]
    (loop [delay 15]
      (let [sock (try
                   (doto (Socket.)
                     (.connect addr 250)
                     (.setSoTimeout 2000)
                     (.setTcpNoDelay true))
                   (catch Exception _ nil))]
        (if sock
          (with-open [^Socket sock sock
                      w (io/writer sock :encoding "UTF-8")
                      r (io/reader sock :encoding "UTF-8")]
            (binding [*out* w]
              (println form-str)
              (println ":repl/quit")
              (flush))
            (try
              (loop []
                (when-not (neg? (.read ^java.io.Reader r))
                  (recur)))
              (catch java.net.SocketTimeoutException _)
              (catch java.net.SocketException _))
            true)
          (if (>= (System/currentTimeMillis) deadline)
            (throw (ex-info "Could not connect to agentic socket REPL"
                            {:host host :port port}))
            (do (Thread/sleep (long delay))
                (recur (min 200 (* 2 delay))))))))))

(defn- await-logged-process
  "Wait for `proc` without touching the host stdin/stdout/stderr.

  Child stdin is closed (EOF) after start — for file-redirected stdin
  that close is a no-op on the unused parent stream. Child stdout/stderr
  are slurped off-thread so the pipes cannot fill, then logged.

  Returns true if the process exit status was 0, otherwise false."
  [proc]
  (let [out (future (try (slurp (process/stdout proc)) (catch Exception _ "")))
        err (future (try (slurp (process/stderr proc)) (catch Exception _ "")))]
    (try
      (.close (process/stdin proc))
      (catch Exception _))
    (let [exit @(process/exit-ref proc)
          out-s (str @out)
          err-s (str @err)]
      (when-not (str/blank? out-s)
        (log/info out-s))
      (when-not (str/blank? err-s)
        (log/warn err-s))
      (zero? exit))))

(defn- run-agent
  "Run the selected coding agent. Returns whether it succeeded.

  If `*agent-runner*` is bound, that fn is called with the context map
  and its truthy/falsy return is the success flag.

  Otherwise switch on `control/agent-vendor` and exec the real headless
  CLI. The child never inherits the host stdin/stdout/stderr:

    :grok-build    grok --prompt-file <file>
    :claude-code   claude -p           (prompt file as child stdin)
    :codex         codex exec -        (prompt file as child stdin)
    :opencode      opencode run --file <file> …"
  [{:keys [prompt-file] :as ctx}]
  (if-let [runner *agent-runner*]
    (runner ctx)
    (try
      (let [path (.getAbsolutePath ^java.io.File (io/file prompt-file))
            captured {:out :pipe :err :pipe}
            from-prompt (assoc captured :in (process/from-file prompt-file))
            vendor (or (var-get #'control/agent-vendor) :grok-build)]
        (case vendor
          :grok-build
          (do (log/info "agentic: running grok --prompt-file" path)
              (await-logged-process
               (process/start captured "grok" "--prompt-file" path)))

          :claude-code
          (do (log/info "agentic: running claude -p")
              (await-logged-process
               (process/start from-prompt "claude" "-p")))

          :codex
          (do (log/info "agentic: running codex exec -")
              (await-logged-process
               (process/start from-prompt "codex" "exec" "-")))

          :opencode
          (do (log/info "agentic: running opencode run --file" path)
              (await-logged-process
               (process/start captured
                              "opencode" "run"
                              "--file" path
                              "Follow the attached prompt file exactly.")))

          (throw (ex-info (str "Unknown agent-vendor: " vendor)
                          {:agent-vendor vendor}))))
      (catch java.io.IOException e
        (log/error e "agentic: failed to start" (var-get #'control/agent-vendor))
        false))))

(defn exception-handler
  "Serialize on a global mutex, expose the incident on
  `control/current-incident`, start a loopback socket REPL, run a CLI
  coding agent, then return `control/current-result` (or nil).

  Both control vars are reset to nil in `finally`."
  [incident]
  (locking handler-lock
    (alter-var-root #'control/current-incident (constantly incident))
    (alter-var-root #'control/current-result (constantly nil))
    (try
      (let [host (or (var-get #'control/agent-socket-repl-host) "127.0.0.1")
            server-name (str "agentic-" (random-uuid))
            socket (server/start-server
                    {:name server-name
                     :address host
                     :port 0
                     :accept 'clojure.core.server/repl
                     :server-daemon true
                     :client-daemon true})
            port (.getLocalPort ^ServerSocket socket)]
        (try
          (let [prompt (build-prompt incident host port)
                file (doto (java.io.File/createTempFile "agentic-prompt-" ".txt")
                       (.deleteOnExit))]
            (spit file prompt)
            (try
              (log/info "agentic: socket REPL on" (str host ":" port))
              (when (run-agent {:host host
                                :port port
                                :prompt prompt
                                :prompt-file file
                                :incident incident})
                (var-get #'control/current-result))
              (finally
                (io/delete-file file :silently))))
          (finally
            (try (server/stop-server server-name) (catch Throwable _)))))
      (finally
        (alter-var-root #'control/current-incident (constantly nil))
        (alter-var-root #'control/current-result (constantly nil))))))
