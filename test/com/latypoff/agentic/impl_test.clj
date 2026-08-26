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

(deftest pretty-print-of-huge-collections-is-bounded
  (let [huge (vec (range 10000))
        nested (vec (repeat 20 (vec (repeat 20 huge))))
        incident (assoc (sample-incident)
                        :defn-args [huge]
                        :locals {'huge huge 'nested nested})
        rendered (impl/bounded-pr-str incident)
        form-meta (impl/bounded-pr-str (meta (:form incident)))]
    (is (str/includes? rendered "..."))
    (is (< (count rendered) 8000)
        (str "pretty-print exploded: " (count rendered) " chars"))
    (is (not (str/includes? rendered "9999"))
        "print-length should have truncated the range before 9999")
    (is (str/includes? form-meta ":line"))
    (is (str/includes? form-meta "17"))))

(deftest library-does-not-write-host-out-or-err
  (let [err (java.io.StringWriter.)
        out (with-out-str
              (binding [*err* err]
                (binding [impl/*agent-runner* (fn [_] true)]
                  (impl/exception-handler (sample-incident)))))]
    (is (str/blank? out))
    (is (str/blank? (str err)))))

(deftest unsuccessful-agent-run-returns-nil
  (binding [impl/*agent-runner* (fn [_] false)]
    (is (nil? (impl/exception-handler (sample-incident)))))
  (is (nil? control/current-incident))
  (is (nil? control/current-result)))

(deftest successful-run-without-result-returns-nil
  (binding [impl/*agent-runner* (fn [_] true)]
    (is (nil? (impl/exception-handler (sample-incident))))))

(deftest successful-run-with-current-result-returns-map
  (binding [impl/*agent-runner*
            (fn [_]
              (alter-var-root #'control/current-result
                              (constantly {:action :return :value :healed}))
              true)]
    (is (= {:action :return :value :healed}
           (impl/exception-handler (sample-incident)))))
  (is (nil? control/current-result)
      "handler must clear current-result in finally"))

(deftest prompt-contains-port-class-message-and-form-meta
  (let [seen (atom nil)]
    (binding [impl/*agent-runner*
              (fn [ctx]
                (reset! seen ctx)
                true)]
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
        (is (re-find #"nc 127\.0\.0\.1" p))
        (is (re-find #"completes unsuccessfully" p))
        (is (not (re-find #"Exit 0" p)))
        (is (not (re-find #"exiting non-zero" p)))))))

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
                true)]
      (let [f1 (future (impl/exception-handler (sample-incident)))]
        (is (.await started 5 java.util.concurrent.TimeUnit/SECONDS)
            "first handler should enter the runner")
        (let [f2 (future (impl/exception-handler (sample-incident)))]
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
              true)]
    (is (= {:action :return :value 42}
           (impl/exception-handler (sample-incident))))))

(deftest socket-repl-roundtrip-healed-keyword
  (binding [impl/*agent-runner*
            (fn [{:keys [host port]}]
              (impl/socket-repl-eval
               host port
               "(do (require 'com.latypoff.agentic.control) (alter-var-root #'com.latypoff.agentic.control/current-result (constantly {:action :return :value :healed})))")
              true)]
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
  (let [script (.getAbsolutePath (io/file "test/fake_agent.sh"))]
    (is (.isFile (io/file script)))
    (binding [impl/*agent-runner*
              (fn [{:keys [host port]}]
                (zero? @(process/exit-ref
                         (process/start "bash" script host (str port)))))]
      (is (= {:action :return :value :healed-offline}
             (impl/exception-handler (sample-incident)))))))
