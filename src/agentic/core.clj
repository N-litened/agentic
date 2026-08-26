(ns agentic.core
  "Runtime self-healing for Clojure vars.

  Failure becomes data. An injected healer proposes a patch (also data).
  The patch is validated, applied to the live var, and journaled so it
  can be inspected or rolled back — without restarting the JVM.

  The library does not call a model. You pass a healer:
    (fn [incident] patch-or-nil)"
  (:require [clojure.string :as str]))

(def ^:private body-keys #{:fn :form :source})
(def ^:private stack-limit 40)
(def ^:private cause-depth 3)

(defn stub-healer
  "Healer that always declines. Default for `runtime`; useful in tests."
  [_incident]
  nil)

(defn constant-healer
  "Returns a healer that always yields `patch` (or a decline value)."
  [patch]
  (fn [_incident] patch))

(defn runtime
  "Create an isolated healing runtime.

  opts
    :healer  (fn [incident] patch-or-nil). Default `stub-healer`.
    :retry?  default for `invoke` (true)."
  ([]
   (runtime {}))
  ([{:keys [healer retry?]
     :or {healer stub-healer
          retry? true}}]
   {:healer healer
    :retry? retry?
    :history (atom [])}))

(defn- now []
  (str (java.time.Instant/now)))

(defn- new-id []
  (str (random-uuid)))

(defn- var-sym
  [v]
  (symbol (str (ns-name (:ns (meta v))))
          (str (:name (meta v)))))

(defn- as-var
  "Resolve a var or qualified symbol. Throws ex-info on failure."
  [var-or-sym]
  (cond
    (var? var-or-sym) var-or-sym
    (qualified-symbol? var-or-sym)
    (or (try (find-var var-or-sym)
             (catch IllegalArgumentException _ nil))
        (try (requiring-resolve var-or-sym)
             (catch Exception _ nil))
        (throw (ex-info "Unknown var"
                        {:var var-or-sym :reason :unknown-var})))
    :else
    (throw (ex-info "Expected a var or qualified symbol"
                    {:var var-or-sym :reason :bad-var}))))

(defn- var-source
  [v]
  (or (some-> v meta :agentic/source)
      (when (var? v)
        (try
          (when-let [src-fn (requiring-resolve 'clojure.repl/source-fn)]
            (src-fn (var-sym v)))
          (catch Exception _ nil)))))

(defn- snapshot-meta
  [v]
  (select-keys (meta v) [:arglists :doc :file :line :column
                         :agentic/source :agentic/patched?]))

(defn- stack-lines
  [^Throwable e]
  (mapv str (.getStackTrace e)))

(defn- throwable-data
  ([^Throwable e]
   (throwable-data e 0))
  ([^Throwable e depth]
   (when e
     (cond-> {:class (.getName (class e))
              :message (ex-message e)
              :data (ex-data e)
              :stack (vec (take stack-limit (stack-lines e)))}
       (and (.getCause e) (< depth cause-depth))
       (assoc :cause (throwable-data (.getCause e) (inc depth)))))))

(defn incident
  "Build a structured incident map from a throwable.

  context
    :var   var or qualified symbol that failed
    :args  args of the failed invocation, if available"
  ([throwable]
   (incident throwable {}))
  ([^Throwable throwable {:keys [var args]}]
   (let [v (when (var? var) var)
         vs (cond
              (var? var) (var-sym var)
              (qualified-symbol? var) var
              :else nil)]
     {:id (new-id)
      :at (now)
      :var vs
      :ns (some-> vs namespace symbol)
      :args (vec (or args []))
      :source (when v (var-source v))
      :meta (when v (snapshot-meta v))
      :exception (throwable-data throwable)})))

(defn declined?
  "True when a healer returns nil, false, :decline, or {:decline true}."
  [patch]
  (or (nil? patch)
      (false? patch)
      (= :decline patch)
      (and (map? patch) (true? (:decline patch)))))

(defn- readable-source
  [s]
  (try
    (read-string {:eof ::eof} s)
    (catch Exception e
      (throw (ex-info "Patch :source is not readable"
                      {:reason :unreadable-source
                       :source s
                       :error (ex-message e)}
                      e)))))

(defn validate-patch
  "Return `patch` if it is valid, otherwise throw ex-info.

  A valid patch is a map with :var (var or qualified symbol) and
  exactly one of :fn (IFn), :form (list/symbol), or :source (readable
  string). Extra keys such as :note are allowed."
  [patch]
  (when-not (map? patch)
    (throw (ex-info "Patch must be a map"
                    {:reason :not-a-map :patch patch})))
  (when-not (contains? patch :var)
    (throw (ex-info "Patch requires :var"
                    {:reason :missing-var :patch patch})))
  (when-not (or (var? (:var patch)) (qualified-symbol? (:var patch)))
    (throw (ex-info "Patch :var must be a var or qualified symbol"
                    {:reason :bad-var :var (:var patch)})))
  (as-var (:var patch))
  (let [bodies (filterv #(contains? patch %) body-keys)]
    (when-not (= 1 (count bodies))
      (throw (ex-info "Patch requires exactly one of :fn, :form, :source"
                      {:reason :bad-body :bodies bodies :patch patch}))))
  (when (contains? patch :fn)
    (when-not (ifn? (:fn patch))
      (throw (ex-info "Patch :fn must be IFn"
                      {:reason :not-fn :fn (:fn patch)}))))
  (when (contains? patch :form)
    (when-not (or (seq? (:form patch)) (symbol? (:form patch)))
      (throw (ex-info "Patch :form must be a Clojure form (list or symbol)"
                      {:reason :bad-form :form (:form patch)}))))
  (when (contains? patch :source)
    (when-not (string? (:source patch))
      (throw (ex-info "Patch :source must be a string"
                      {:reason :bad-source :source (:source patch)})))
    (when (str/blank? (:source patch))
      (throw (ex-info "Patch :source is blank"
                      {:reason :unreadable-source :source (:source patch)})))
    (readable-source (:source patch)))
  patch)

(defn- patch-source
  [patch]
  (or (:source patch)
      (when (contains? patch :form)
        (pr-str (:form patch)))))

(defn- eval-in-var-ns
  [v form]
  (let [ns-obj (or (some-> v meta :ns) *ns*)]
    (binding [*ns* ns-obj]
      (eval form))))

(defn- replacement-fn
  [patch v]
  (cond
    (contains? patch :fn) (:fn patch)
    (contains? patch :form) (eval-in-var-ns v (:form patch))
    (contains? patch :source) (eval-in-var-ns v (readable-source (:source patch)))))

(defn- as-ifn
  [val]
  (cond
    (fn? val) val
    (var? val) (deref val)
    (ifn? val) val
    :else
    (throw (ex-info "Patch did not produce an IFn"
                    {:reason :not-fn
                     :class (some-> val class .getName)}))))

(defn- datafy-entry
  "Drop live objects so the journal is plain Clojure data."
  [entry]
  (cond-> entry
    (var? (:var entry)) (update :var var-sym)
    (map? (:patch entry)) (update :patch dissoc :fn)
    (map? (:previous entry)) (update :previous dissoc :value)))

(defn- record!
  [rt entry]
  (let [entry (merge {:id (new-id) :at (now)} entry)]
    (swap! (:history rt) conj entry)
    entry))

(defn apply-patch!
  "Validate and apply a patch to a live var. Records the previous
  definition on `rt` so it can be rolled back.

  Optional `incident` is stored on the history entry."
  ([rt patch]
   (apply-patch! rt patch nil))
  ([rt patch incident]
   (let [patch (validate-patch patch)
         v (as-var (:var patch))
         vs (var-sym v)
         previous {:value (deref v)
                   :source (var-source v)
                   :meta (snapshot-meta v)}
         new-fn (as-ifn (replacement-fn patch v))]
     (alter-var-root v (constantly new-fn))
     (alter-meta! v assoc
                  :agentic/patched? true
                  :agentic/source (patch-source patch))
     (record! rt (cond-> {:status :applied
                          :var vs
                          :patch (dissoc patch :fn)
                          :previous previous}
                   incident (assoc :incident incident))))))

(defn heal!
  "Ask the runtime healer for a patch and apply it when valid.

  Returns a history entry with :status
    :applied      patch interned
    :declined     healer returned nil / :decline
    :rejected     patch failed validation or eval
    :healer-error healer threw"
  [rt incident]
  (let [healer (or (:healer rt) stub-healer)]
    (try
      (let [raw (healer incident)]
        (if (declined? raw)
          (record! rt {:status :declined
                       :var (:var incident)
                       :incident incident})
          (try
            (apply-patch! rt raw incident)
            (catch Exception e
              (record! rt {:status :rejected
                           :var (:var incident)
                           :incident incident
                           :patch (when (map? raw) (dissoc raw :fn))
                           :reason (:reason (ex-data e) :invalid-patch)
                           :error (throwable-data e)})))))
      (catch Throwable e
        (record! rt {:status :healer-error
                     :var (:var incident)
                     :incident incident
                     :error (throwable-data e)})))))

(defn invoke
  "Call a var through the healing loop.

  opts
    :var     var or qualified symbol (required)
    :args    sequential arguments (default [])
    :retry?  retry once after a successful apply (default from runtime)

  On success, returns the var's value. On unhealed failure, rethrows
  the original throwable so program semantics stay intact."
  [rt {:keys [var args retry?] :or {args []} :as opts}]
  (let [v (as-var var)
        retry? (if (contains? opts :retry?) retry? (:retry? rt true))]
    (try
      (apply (deref v) args)
      (catch Throwable t
        (let [inc (incident t {:var v :args args})
              result (heal! rt inc)]
          (if (and (= :applied (:status result)) retry?)
            (try
              (let [ret (apply (deref v) args)]
                (record! rt {:status :retried
                             :var (var-sym v)
                             :incident-id (:id inc)
                             :patch-id (:id result)
                             :ok? true})
                ret)
              (catch Throwable t2
                (record! rt {:status :retried
                             :var (var-sym v)
                             :incident-id (:id inc)
                             :patch-id (:id result)
                             :ok? false
                             :exception (throwable-data t2)})
                (throw t2)))
            (throw t)))))))

(defn wrap
  "Return a function that invokes `var` through this runtime."
  [rt var]
  (fn [& args]
    (invoke rt {:var var :args args})))

(defn rollback!
  "Restore the previous definition from the last applied, not-yet-rolled
  patch for `var`. Throws if there is nothing to roll back."
  [rt var]
  (let [v (as-var var)
        vs (var-sym v)
        match (->> @(:history rt)
                   (map-indexed vector)
                   reverse
                   (filter (fn [[_ e]]
                             (and (= :applied (:status e))
                                  (= vs (:var e))
                                  (not (:rolled-back? e)))))
                   first)]
    (when-not match
      (throw (ex-info "Nothing to roll back"
                      {:var vs :reason :nothing-to-rollback})))
    (let [[idx entry] match
          previous (:previous entry)]
      (when-not (contains? previous :value)
        (throw (ex-info "Applied entry is missing a previous value"
                        {:var vs :reason :missing-previous})))
      (alter-var-root v (constantly (:value previous)))
      (alter-meta! v (fn [m]
                       (-> m
                           (dissoc :agentic/source :agentic/patched?)
                           (merge (:meta previous)))))
      (swap! (:history rt)
             (fn [journal]
               (-> journal
                   (assoc-in [idx :rolled-back?] true)
                   (conj {:id (new-id)
                          :at (now)
                          :status :rolled-back
                          :var vs
                          :patch-id (:id entry)}))))
      {:status :rolled-back :var vs :patch-id (:id entry)})))

(defn history
  "Incident and patch journal as plain Clojure data. Oldest first."
  [rt]
  (mapv datafy-entry @(:history rt)))
