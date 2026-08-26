(ns com.latypoff.agentic
  "Public API: one macro, `defn`.

  Use in place of `clojure.core/defn` for functions that should not
  normally throw. On Throwable the body calls
  `com.latypoff.agentic.impl/exception-handler`, which starts a socket
  REPL and a CLI coding agent."
  (:refer-clojure :exclude [defn])
  (:require [com.latypoff.agentic.impl :as impl]))

(defn- qualified-defn-sym
  [name-sym]
  (if (namespace name-sym)
    name-sym
    (symbol (str (ns-name *ns*)) (name name-sym))))

(defn- parse-defn
  "Same destructuring `clojure.core/defn` uses:

    name doc-string? attr-map? ([params*] prepost-map? body)+ attr-map?
    name doc-string? attr-map? [params*] prepost-map? body"
  [name & fdecl]
  (when-not (symbol? name)
    (throw (IllegalArgumentException. "First argument to defn must be a symbol")))
  (let [doc (when (string? (first fdecl)) (first fdecl))
        fdecl (if (string? (first fdecl)) (next fdecl) fdecl)
        attr (when (map? (first fdecl)) (first fdecl))
        fdecl (if (map? (first fdecl)) (next fdecl) fdecl)
        fdecl (if (vector? (first fdecl)) (list fdecl) fdecl)
        trailing (when (map? (last fdecl)) (last fdecl))
        bodies (if (map? (last fdecl)) (butlast fdecl) fdecl)]
    {:name name
     :doc doc
     :attr attr
     :trailing trailing
     :bodies bodies}))

(defn- split-prepost
  "If the first body form is a pre/post map (a map followed by more
  forms), keep it outside `try` so AssertionError from :pre/:post is
  not swallowed by the heal loop."
  [body]
  (if (and (map? (first body)) (next body))
    [(first body) (next body)]
    [nil body]))

(defn- binding-value
  "Expression that evaluates to the value bound by a param form.
  Injects `:as` only when we would otherwise lose the original arg."
  [p]
  (cond
    (symbol? p) {:param p :value p}
    (map? p) (if (contains? p :as)
               {:param p :value (:as p)}
               (let [g (gensym "arg")]
                 {:param (assoc p :as g) :value g}))
    (vector? p) (let [idx (.indexOf ^clojure.lang.PersistentVector p :as)]
                  (if (>= idx 0)
                    {:param p :value (nth p (inc idx))}
                    (let [g (gensym "arg")]
                      {:param (conj p :as g) :value g})))
    :else {:param p :value p}))

(defn- capture-params
  "Rewrite a params vector so `:defn-args` can recover the values
  actually passed, including `& rest` and destructuring."
  [params]
  (let [amp (.indexOf ^clojure.lang.PersistentVector params '&)]
    (if (neg? amp)
      (let [captured (mapv binding-value params)]
        {:params (mapv :param captured)
         :args-form (mapv :value captured)})
      (let [fixed (mapv binding-value (subvec params 0 amp))
            rest-sym (nth params (inc amp))
            after (subvec params (inc amp))]
        ;; after is [rest-sym] or [rest-sym :as unused] — keep rest as-is
        {:params (into (mapv :param fixed) (cons '& after))
         :args-form `(into ~(mapv :value fixed) ~rest-sym)}))))

(defn- locals-form
  "Emit `'sym → sym` pairs from `&env`. Values are evaluated in the
  `catch`, not at expansion time."
  [env]
  (into {}
        (for [sym (keys env)]
          [(list 'quote sym) sym])))

(defn- wrap-arity
  [defn-sym form expansion-file env arity]
  (let [[params & body] arity
        [prepost body] (split-prepost body)
        {:keys [params args-form]} (capture-params params)
        t (gensym "t")
        result (gensym "result")]
    `(~params
      ~@(when prepost [prepost])
      (try
        ~@body
        (catch Throwable ~t
          (let [~result (impl/exception-handler
                         {:throwable ~t
                          :defn-sym '~defn-sym
                          :defn-args ~args-form
                          :ns (ns-name *ns*)
                          :source-path (impl/source-path ~expansion-file)
                          :form '~form
                          :locals ~(locals-form env)})]
            (impl/apply-handler-result ~result ~t)))))))

(defmacro defn
  "Like `clojure.core/defn`, but each arity body is wrapped in
  `try`/`catch Throwable`. The catch calls
  `com.latypoff.agentic.impl/exception-handler` and interprets:

    nil                                  rethrow original
    {:action :throw :throwable t}        throw t
    {:action :return :value v}           return v
    anything else                        rethrow original

  Delegates the actual var definition to `clojure.core/defn`, so
  docstring, attr-map, multi-arity, and pre/post maps behave as in core."
  {:arglists '([name doc-string? attr-map? [params*] prepost-map? body]
               [name doc-string? attr-map? ([params*] prepost-map? body)+ attr-map?])}
  [& args]
  (let [expansion-file *file*
        parsed (apply parse-defn args)
        defn-sym (qualified-defn-sym (:name parsed))
        bodies (map #(wrap-arity defn-sym &form expansion-file &env %)
                    (:bodies parsed))]
    `(clojure.core/defn ~(:name parsed)
       ~@(when-let [d (:doc parsed)] [d])
       ~@(when-let [a (:attr parsed)] [a])
       ~@bodies
       ~@(when-let [t (:trailing parsed)] [t]))))
