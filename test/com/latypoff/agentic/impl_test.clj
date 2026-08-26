(ns com.latypoff.agentic.impl-test
  (:require [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [com.latypoff.agentic.control :as control]
            [com.latypoff.agentic.impl :as impl]))

(defn- sample-incident
  ([] (sample-incident (ex-info "boom" {:k 1})))
  ([t]
   {:throwable t
    :defn-sym 'demo.core/divide
    :defn-args [10 0]
    :ns 'demo.core
    :source-path "demo/core.clj"
    :form (with-meta '(com.latypoff.agentic/defn divide [n d] (/ n d))
            {:line 17 :column 1})
    :locals {'n 10 'd 0}}))

(deftest apply-handler-result-shapes
  (let [orig (ex-info "orig" {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"orig"
                          (impl/apply-handler-result nil orig)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"orig"
                          (impl/apply-handler-result :nope orig)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"orig"
                          (impl/apply-handler-result {:action :wat} orig)))
    (is (= :ok (impl/apply-handler-result {:action :return :value :ok} orig)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"repl"
                          (impl/apply-handler-result
                           {:action :throw :throwable (ex-info "repl" {})}
                           orig)))))

(deftest pretty-print-of-huge-collections-is-bounded
  (let [huge (vec (range 10000))
        nested (vec (repeat 20 (vec (repeat 20 huge))))
        incident (assoc (sample-incident)
                        :defn-args [huge]
                        :locals {'huge huge 'nested nested})
        formatted (impl/format-incident incident)
        rendered (:incident formatted)]
    (is (str/includes? rendered "..."))
    (is (< (count rendered) 8000)
        (str "pretty-print exploded: " (count rendered) " chars"))
    (is (not (str/includes? rendered "9999"))
        "print-length should have truncated the range before 9999")
    (is (str/includes? (:form-meta formatted) ":line"))
    (is (str/includes? (:form-meta formatted) "17"))))

(deftest unsuccessful-agent-exit-returns-nil
  (binding [impl/*agent-runner* (fn [_] 1)]
    (is (nil? (impl/exception-handler (sample-incident)))))
  (is (nil? control/current-incident))
  (is (nil? control/current-result)))

(deftest successful-exit-without-result-returns-nil
  (binding [impl/*agent-runner* (fn [_] 0)]
    (is (nil? (impl/exception-handler (sample-incident))))))

(deftest successful-exit-with-current-result-returns-map
  (binding [impl/*agent-runner*
            (fn [_]
              (alter-var-root #'control/current-result
                              (constantly {:action :return :value :healed}))
              0)]
    (is (= {:action :return :value :healed}
           (impl/exception-handler (sample-incident)))))
  (is (nil? control/current-result)
      "handler must clear current-result in finally"))

(deftest prompt-contains-port-class-message-and-form-meta
  (let [seen (atom nil)]
    (binding [impl/*agent-runner*
              (fn [ctx]
                (reset! seen ctx)
                0)]
      (impl/exception-handler (sample-incident))
      (let [p (:prompt @seen)]
        (is (string? p))
        (is (re-find #"boom" p))
        (is (re-find #"ExceptionInfo" p))
        (is (re-find #":line" p))
        (is (re-find #"17" p))
        (is (re-find (re-pattern (str (:port @seen))) p))
        (is (re-find #"127\.0\.0\.1" p))
        (is (re-find #"current-incident" p))
        (is (re-find #"current-result" p))
        (is (re-find #"nc 127\.0\.0\.1" p))))))

(deftest source-path-fallback-does-not-throw
  (is (= "captured.clj" (impl/source-path "captured.clj")))
  (is (nil? (impl/source-path nil)))
  (when-let [v (try (find-var 'clojure.core/*source-path*) (catch Throwable _))]
    (push-thread-bindings {v "live.clj"})
    (try
      (is (= "live.clj" (impl/source-path "captured.clj")))
      (finally
        (pop-thread-bindings)))))

(deftest mutex-serializes-concurrent-handlers
  (let [log (atom [])
        gate (java.util.concurrent.CountDownLatch. 1)
        started (java.util.concurrent.CountDownLatch. 1)]
    (binding [impl/*agent-runner*
              (fn [_]
                (swap! log conj :start)
                (.countDown started)
                (when-not (.await gate 5 java.util.concurrent.TimeUnit/SECONDS)
                  (throw (ex-info "mutex test gate timed out" {})))
                (swap! log conj :end)
                0)]
      (let [f1 (future (impl/exception-handler (sample-incident)))]
        (is (.await started 5 java.util.concurrent.TimeUnit/SECONDS)
            "first handler should enter the runner")
        (let [f2 (future (impl/exception-handler (sample-incident)))]
          ;; Give the second caller a chance to race the lock.
          (Thread/sleep 80)
          (is (= [:start] @log) "second handler must wait on the mutex")
          (.countDown gate)
          (is (nil? (deref f1 10000 :timeout)))
          (is (nil? (deref f2 10000 :timeout)))
          (is (= [:start :end :start :end] @log)))))))

(deftest socket-repl-roundtrip-sets-current-result
  (binding [impl/*agent-runner*
            (fn [{:keys [host port]}]
              (impl/socket-repl-eval
               host port
               "(alter-var-root #'com.latypoff.agentic.control/current-result (constantly {:action :return :value 42}))")
              0)]
    (is (= {:action :return :value 42}
           (impl/exception-handler (sample-incident))))))

(deftest socket-repl-roundtrip-healed-keyword
  (binding [impl/*agent-runner*
            (fn [{:keys [host port]}]
              (impl/socket-repl-eval
               host port
               "(do (require 'com.latypoff.agentic.control) (alter-var-root #'com.latypoff.agentic.control/current-result (constantly {:action :return :value :healed})))")
              0)]
    (is (= {:action :return :value :healed}
           (impl/exception-handler (sample-incident))))))

(deftest shipped-cli-invocations-use-real-flags
  (let [src (slurp "src/com/latypoff/agentic/impl.clj")]
    (is (str/includes? src "\"grok\" \"--prompt-file\""))
    (is (str/includes? src "\"claude\" \"-p\""))
    (is (str/includes? src "\"codex\" \"exec\" \"-\""))
    (is (str/includes? src "\"opencode\" \"run\""))
    (is (str/includes? src "\"--file\"")))
  (let [prev control/agent-vendor]
    (try
      (alter-var-root #'control/agent-vendor (constantly :nope))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown agent-vendor"
                            (#'impl/run-agent {:prompt-file (io/file "/tmp/agentic-prompt.txt")})))
      (finally
        (alter-var-root #'control/agent-vendor (constantly prev))))))

(deftest bash-fake-agent-script-roundtrip
  (let [script (.getAbsolutePath (io/file "examples/fake_agent.sh"))]
    (is (.isFile (io/file script)))
    (binding [impl/*agent-runner*
              (fn [{:keys [host port]}]
                @(process/exit-ref (process/start "bash" script host (str port))))]
      (is (= {:action :return :value :healed-offline}
             (impl/exception-handler (sample-incident)))))))

(deftest control-agent-vendor-fn-is-honored
  (let [prev control/agent-vendor]
    (try
      (alter-var-root #'control/agent-vendor
                      (constantly
                       (fn [{:keys [host port]}]
                         (impl/socket-repl-eval
                          host port
                          "(alter-var-root #'com.latypoff.agentic.control/current-result (constantly {:action :return :value :via-control}))")
                         0)))
      (is (= {:action :return :value :via-control}
             (impl/exception-handler (sample-incident))))
      (finally
        (alter-var-root #'control/agent-vendor (constantly prev))))))
