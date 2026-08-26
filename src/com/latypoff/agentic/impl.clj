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
  (:import [java.net InetSocketAddress Socket]))

(set! *warn-on-reflection* true)

(def ^:private handler-lock
  "Global mutex. Only one CLI agent runs at a time."
  (Object.))

(def ^:dynamic *agent-runner*
  "If bound to a fn, it is invoked instead of the CLI selected by
  `control/agent-vendor`. The fn receives the runner context map and must
  return an integer exit code."
  nil)

(def ^:private print-length 16)
(def ^:private print-level 4)

(defn- usable-source-path?
  "The compiler interns `clojure.core/*source-path*` with sentinel
  `NO_SOURCE_FILE` even though core.clj never defines it. Treat sentinels
  and blanks as unset so the expansion-time `*file*` fallback can win."
  [val]
  (and (string? val)
       (not (str/blank? val))
       (not= val "NO_SOURCE_FILE")
       (not= val "NO_SOURCE_PATH")))

(defn source-path
  "Resolve `*source-path*` when that var exists, is bound, and holds a
  real path; otherwise return `fallback` (typically `*file*` captured at
  macro-expansion time). Never throw if the var is absent."
  [fallback]
  (letfn [(read-var [v]
            (when (and (var? v) (bound? v))
              (let [val (var-get v)]
                (when (usable-source-path? val) val))))]
    (or (try
          (read-var (resolve '*source-path*))
          (catch Throwable _))
        (try
          (read-var (ns-resolve 'clojure.core '*source-path*))
          (catch Throwable _))
        (try
          (read-var (find-var 'clojure.core/*source-path*))
          (catch Throwable _))
        fallback)))

(defn apply-handler-result
  "Interpret `exception-handler`'s return in a `catch` body.

  - nil or any unrecognized value → rethrow `original`
  - {:action :throw :throwable t} → throw t
  - {:action :return :value v}    → return v"
  [result original]
  (cond
    (nil? result)
    (throw original)

    (and (map? result) (= :throw (:action result)))
    (throw (:throwable result))

    (and (map? result) (= :return (:action result)))
    (:value result)

    :else
    (throw original)))

(defn bounded-pr-str
  "Pretty-print `x` with `*print-length*` 16 / `*print-level*` 4 so huge
  args or locals cannot explode the prompt."
  [x]
  (binding [*print-length* print-length
            *print-level* print-level
            *print-meta* false
            pprint/*print-right-margin* 100
            pprint/*print-miser-width* 40
            pprint/*print-pprint-dispatch* pprint/simple-dispatch]
    (str/trimr (with-out-str (pprint/pprint x)))))

(defn- stacktrace-str
  [^Throwable t]
  (when t
    (with-out-str (stack/print-cause-trace t))))

(defn format-incident
  "Pretty-printed incident plus the separately called-out exception fields
  the prompt injects. Used by tests to assert print bounds."
  [incident]
  (let [t (:throwable incident)]
    {:incident (bounded-pr-str incident)
     :class (if t (str (class t)) "nil")
     :message (when t (ex-message t))
     :ex-data (bounded-pr-str (when t (ex-data t)))
     :stacktrace (or (stacktrace-str t) "")
     :form-meta (bounded-pr-str (meta (:form incident)))}))

(defn render-prompt
  "Substitute `{{placeholder}}` tokens in `template` from `values`.
  Unknown placeholders are left intact."
  [template values]
  (str/replace (str template)
               #"\{\{([a-z0-9-]+)\}\}"
               (fn [[full k]]
                 (if (contains? values (keyword k))
                   (str (get values (keyword k)))
                   full))))

(defn- connect-example
  [host port]
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
       "    # (io/writer) / (io/reader) on (java.net.Socket. \"" host "\" " port ")"))

(defn build-prompt
  "Render `control/agent-prompt` for this incident and socket."
  [incident host port]
  (let [parts (format-incident incident)
        values (merge parts
                      {:host host
                       :port port
                       :connect-example (connect-example host port)})]
    (render-prompt (var-get #'control/agent-prompt) values)))

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

(defn- start-socket-repl
  [host]
  (let [name (str "agentic-" (random-uuid))
        socket (server/start-server
                {:name name
                 :address host
                 :port 0
                 :accept 'clojure.core.server/repl
                 :server-daemon true
                 :client-daemon true})]
    {:name name
     :socket socket
     :host host
     :port (.getLocalPort ^java.net.ServerSocket socket)}))

(defn- stop-socket-repl
  "Stop the server started by `start-socket-repl`. Accepts that return
  map and extracts `:name`."
  [{:keys [name]}]
  (try
    (server/stop-server name)
    (catch Throwable _)))

(defn- prompt-file
  [prompt]
  (let [f (doto (java.io.File/createTempFile "agentic-prompt-" ".txt")
            (.deleteOnExit))]
    (spit f prompt)
    f))

(defn- await-logged-process
  "Wait for `proc` without touching the host stdin/stdout/stderr.

  Child stdin is a pipe we close (EOF) unless the caller already
  redirected it with `process/from-file`. Child stdout/stderr are
  slurped off-thread so the pipes cannot fill, then logged."
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
      exit)))

(defn- run-agent
  "Run the selected coding agent.

  `*agent-runner*` (tests) or a fn in `control/agent-vendor` is called
  with the context map and must return an exit code.

  Otherwise switch on `control/agent-vendor` and exec the real headless
  CLI inline via `clojure.java.process`. The child never inherits the
  host stdin/stdout/stderr:

    :grok-build    grok --prompt-file <file>
    :claude-code   claude -p           (prompt file as child stdin)
    :codex         codex exec -        (prompt file as child stdin)
    :opencode      opencode run --file <file> …"
  [{:keys [prompt-file] :as ctx}]
  (let [override *agent-runner*
        vendor (or (var-get #'control/agent-vendor) :grok-build)]
    (cond
      (fn? override) (override ctx)
      (fn? vendor) (vendor ctx)
      :else
      (try
        (let [path (.getAbsolutePath ^java.io.File (io/file prompt-file))
              ;; pipes, not :inherit — host stdio stays under the app
              captured {:out :pipe :err :pipe}
              from-prompt (assoc captured :in (process/from-file prompt-file))]
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
          (log/error e "agentic: failed to start" vendor)
          127)))))

(defn- successful-exit?
  [code]
  (or (true? code)
      (and (integer? code) (zero? code))))

(defn exception-handler
  "Serialize on a global mutex, expose the incident on
  `control/current-incident`, start a loopback socket REPL, run a CLI
  coding agent, then return `control/current-result` (or nil).

  Both control vars are reset to nil in `finally`."
  [incident]
  (locking handler-lock
    (let [result (volatile! nil)]
      (try
        (alter-var-root #'control/current-incident (constantly incident))
        (alter-var-root #'control/current-result (constantly nil))
        (let [host (or (var-get #'control/socket-host) "127.0.0.1")
              repl (start-socket-repl host)]
          (try
            (let [prompt (build-prompt incident host (:port repl))
                  file (prompt-file prompt)]
              (try
                (log/info "agentic: socket REPL on" (str host ":" (:port repl)))
                (let [code (run-agent {:host host
                                       :port (:port repl)
                                       :prompt prompt
                                       :prompt-file file
                                       :incident incident})]
                  (vreset! result
                           (when (successful-exit? code)
                             (var-get #'control/current-result))))
                (finally
                  (io/delete-file file :silently))))
            (finally
              (stop-socket-repl repl))))
        (finally
          (alter-var-root #'control/current-incident (constantly nil))
          (alter-var-root #'control/current-result (constantly nil))))
      @result)))
