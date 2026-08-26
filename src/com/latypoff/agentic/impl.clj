(ns com.latypoff.agentic.impl
  "Runtime heal loop: socket REPL + CLI coding agent.

  Public entry: `exception-handler`. Tests bind `*agent-runner*` to avoid
  invoking a real `grok`/`claude`/`codex`/`opencode` process."
  (:require [clojure.core.server :as server]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [com.latypoff.agentic.control :as control])
  (:import [java.io
            BufferedReader
            File
            InputStreamReader
            OutputStreamWriter
            PrintWriter]
           [java.net InetSocketAddress Socket]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(def ^:private handler-lock
  "Global mutex. Only one CLI agent runs at a time."
  (Object.))

(def ^:dynamic *agent-runner*
  "If bound to a fn, it is invoked instead of the CLI selected by
  `control/agent`. The fn receives the runner context map and must return
  an integer exit code."
  nil)

(def ^:private default-print-length 32)
(def ^:private default-print-level 6)

(defn source-path
  "Resolve `*source-path*` when that var exists and is bound; otherwise
  return `fallback` (typically `*file*` captured at macro-expansion time).

  There is no `*source-path*` in clojure.core. Never throw if it is absent."
  [fallback]
  (letfn [(read-var [v]
            (when (and (var? v) (bound? v))
              (let [val (var-get v)]
                (when (some? val) val))))]
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

(defn- print-bounds []
  {:length (or (var-get #'control/print-length) default-print-length)
   :level (or (var-get #'control/print-level) default-print-level)})

(defn bounded-pr-str
  "Pretty-print `x` with `*print-length*` / `*print-level*` bounds so huge
  args or locals cannot explode the prompt."
  [x]
  (let [{:keys [length level]} (print-bounds)]
    (binding [*print-length* length
              *print-level* level
              *print-meta* false
              pprint/*print-right-margin* 100
              pprint/*print-miser-width* 40
              pprint/*print-pprint-dispatch* pprint/simple-dispatch]
      (str/trimr (with-out-str (pprint/pprint x))))))

(defn- stacktrace-str
  [^Throwable t]
  (when t
    (let [sw (java.io.StringWriter.)]
      (.printStackTrace t (PrintWriter. sw))
      (str sw))))

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
       "      'ctl/current-exception' \\\n"
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
          (try
            (let [w (PrintWriter. (OutputStreamWriter. (.getOutputStream sock) "UTF-8") true)
                  r (BufferedReader. (InputStreamReader. (.getInputStream sock) "UTF-8"))]
              (.println w (str form-str))
              (.println w ":repl/quit")
              (.flush w)
              ;; Drain until the server closes or the read times out — this
              ;; gives the REPL thread time to finish the eval.
              (try
                (loop []
                  (when-not (neg? (.read r))
                    (recur)))
                (catch java.net.SocketTimeoutException _)
                (catch java.net.SocketException _))
              true)
            (finally
              (try (.close sock) (catch Exception _))))
          (if (>= (System/currentTimeMillis) deadline)
            (throw (ex-info "Could not connect to agentic socket REPL"
                            {:host host :port port}))
            (do (Thread/sleep (long delay))
                (recur (min 200 (* 2 delay))))))))))

(defn- start-socket-repl
  [host]
  (let [name (str "agentic-" (UUID/randomUUID))
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
  [name]
  (try
    (server/stop-server name)
    (catch Throwable _)))

(defn- prompt-file
  ^File [prompt]
  (let [f (File/createTempFile "agentic-prompt-" ".txt")]
    (.deleteOnExit f)
    (spit f prompt)
    f))

(defn- agent-command
  "Real headless invocations. Flags verified against current CLIs:

   grok         --prompt-file (also -p / --single)
   claude       -p / --print; prompt on stdin
   codex        exec -   (stdin is the full prompt)
   opencode     run --file <prompt> <instruction>"
  [agent-kw ^File file]
  (let [path (.getAbsolutePath file)]
    (case agent-kw
      :grok-build ["grok" "--prompt-file" path]
      :claude-code ["claude" "-p"]
      :codex ["codex" "exec" "-"]
      :opencode ["opencode" "run"
                 "--file" path
                 "Follow the attached prompt file exactly."]
      (throw (ex-info (str "Unknown agent keyword: " agent-kw)
                      {:agent agent-kw})))))

(defn- stdin-agent?
  [agent-kw]
  (contains? #{:claude-code :codex} agent-kw))

(defn- run-process
  "Start `args`, optionally feeding `in-file` to stdin. Returns exit code.
  Missing executables become exit 127."
  [args in-file]
  (try
    (let [pb (ProcessBuilder. ^java.util.List (mapv str args))]
      (.inheritIO pb)
      (when in-file
        (.redirectInput pb ^File in-file))
      (let [p (.start pb)]
        (try
          (.waitFor p)
          (finally
            (when (.isAlive p)
              (.destroyForcibly p))))))
    (catch java.io.IOException e
      (binding [*out* *err*]
        (println "agentic: failed to start" (first args) "-" (.getMessage e)))
      127)))

(defn- run-cli-agent
  [agent-kw {:keys [prompt-file]}]
  (let [cmd (agent-command agent-kw prompt-file)
        in (when (stdin-agent? agent-kw) prompt-file)]
    (println "agentic: running" (str/join " " cmd))
    (run-process cmd in)))

(defn runner-context
  [incident {:keys [host port]} prompt ^File file]
  {:host host
   :port port
   :prompt prompt
   :prompt-file file
   :incident incident})

(defn- invoke-runner
  [ctx]
  (let [custom *agent-runner*
        selected (var-get #'control/agent)]
    (cond
      (fn? custom) (custom ctx)
      (fn? selected) (selected ctx)
      :else (run-cli-agent (or selected :grok-build) ctx))))

(defn- successful-exit?
  [code]
  (or (true? code)
      (and (integer? code) (zero? code))))

(defn exception-handler
  "Serialize on a global mutex, expose the incident on
  `control/current-exception`, start a loopback socket REPL, run a CLI
  coding agent, then return `control/current-result` (or nil).

  Both control vars are reset to nil in `finally`."
  [incident]
  (locking handler-lock
    (let [result (volatile! nil)]
      (try
        (alter-var-root #'control/current-exception (constantly incident))
        (alter-var-root #'control/current-result (constantly nil))
        (let [host (or (var-get #'control/socket-host) "127.0.0.1")
              {:keys [name port] :as repl} (start-socket-repl host)]
          (try
            (let [prompt (build-prompt incident host port)
                  file (prompt-file prompt)]
              (try
                (println (str "agentic: socket REPL on " host ":" port))
                (let [code (invoke-runner (runner-context incident repl prompt file))]
                  (vreset! result
                           (when (successful-exit? code)
                             (var-get #'control/current-result))))
                (finally
                  (try (.delete file) (catch Exception _)))))
            (finally
              (stop-socket-repl name))))
        (finally
          (alter-var-root #'control/current-exception (constantly nil))
          (alter-var-root #'control/current-result (constantly nil))))
      @result)))
