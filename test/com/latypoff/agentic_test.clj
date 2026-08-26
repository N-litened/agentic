(ns com.latypoff.agentic-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [com.latypoff.agentic :as agentic]
            [com.latypoff.agentic.impl :as impl]))

(defn- tree-has?
  [form pred]
  (let [found? (volatile! false)]
    (walk/postwalk
     (fn [x]
       (when (pred x) (vreset! found? true))
       x)
     form)
    @found?))

(deftest expands-to-core-defn-with-try-catch
  (let [exp (macroexpand-1
             '(com.latypoff.agentic/defn sample-fn [x] (+ x 1)))]
    (is (= 'clojure.core/defn (first exp)))
    (is (= 'sample-fn (second exp)))
    (is (tree-has? exp #{'try}))
    (is (tree-has? exp (fn [x] (and (seq? x) (= 'catch (first x))))))
    (is (tree-has? exp #{'Throwable 'java.lang.Throwable}))))

(deftest multi-arity-and-docstring
  (agentic/defn add
    "Adds numbers"
    {:added "test"}
    ([] 0)
    ([x] x)
    ([x y] (+ x y)))
  (is (= 0 (add)))
  (is (= 4 (add 4)))
  (is (= 7 (add 3 4)))
  (is (= "Adds numbers" (:doc (meta #'add))))
  (is (= "test" (:added (meta #'add))))
  (let [exp (macroexpand-1
             '(com.latypoff.agentic/defn multi
                "doc"
                ([] :zero)
                ([x] x)))]
    (is (= 2 (count (filter #{'try} (tree-seq coll? seq exp)))))))

(agentic/defn boom
  ([] (throw (ex-info "orig" {:n 0})))
  ([x] (throw (ex-info "orig" {:n x}))))

(deftest handler-nil-rethrows
  (with-redefs [impl/exception-handler (constantly nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"orig" (boom)))
    (try
      (boom)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= {:n 0} (ex-data e)))))))

(deftest handler-return-returns-value
  (with-redefs [impl/exception-handler (constantly {:action :return :value 7})]
    (is (= 7 (boom)))
    (is (= 7 (boom 99)))))

(deftest handler-throw-throws-replacement
  (with-redefs [impl/exception-handler
                (constantly {:action :throw
                             :throwable (ex-info "replaced" {:ok true})})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"replaced" (boom)))
    (try
      (boom)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= {:ok true} (ex-data e)))))))

(deftest handler-unknown-action-rethrows
  (with-redefs [impl/exception-handler (constantly {:action :nope :value 1})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"orig" (boom)))))

(deftest incident-contains-runtime-args-and-form-meta
  (let [captured (atom nil)]
    (with-redefs [impl/exception-handler
                  (fn [m]
                    (reset! captured m)
                    {:action :return :value :ok})]
      (is (= :ok (boom 42)))
      (is (= [42] (:defn-args @captured)))
      (is (= 'com.latypoff.agentic-test/boom (:defn-sym @captured)))
      (is (instance? Throwable (:throwable @captured)))
      (is (symbol? (:ns @captured)))
      (is (string? (:source-path @captured)))
      (is (pos? (count (:source-path @captured))))
      (is (seq? (:form @captured)))
      (is (pos? (:line (meta (:form @captured))))))))

(deftest locals-from-env-are-runtime-values
  (let [captured (atom nil)]
    (with-redefs [impl/exception-handler
                  (fn [m]
                    (reset! captured m)
                    {:action :return :value :ok})]
      (let [outer 99
            label "closed-over"]
        (agentic/defn sneak
          [x]
          (throw (ex-info "sneak" {:x x})))
        (is (= :ok (sneak 3)))
        (is (= 99 (get (:locals @captured) 'outer)))
        (is (= "closed-over" (get (:locals @captured) 'label)))
        (is (= [3] (:defn-args @captured)))))))

(deftest source-path-is-inlined-at-expansion
  (let [exp (macroexpand-1 '(com.latypoff.agentic/defn f [x] x))
        paths (atom [])]
    (walk/postwalk
     (fn [x]
       (when (and (map? x) (contains? x :source-path))
         (swap! paths conj (:source-path x)))
       x)
     exp)
    (is (seq @paths))
    (doseq [p @paths]
      (is (or (string? p) (nil? p))
          "source-path must be a value captured at expansion, not a runtime call"))))

(deftest prepost-maps-stay-outside-try
  (agentic/defn pos-only
    [x]
    {:pre [(pos? x)] :post [(number? %)]}
    x)
  (is (= 2 (pos-only 2)))
  (is (thrown? AssertionError (pos-only -1)))
  (let [exp (macroexpand-1
             '(com.latypoff.agentic/defn p [x]
                {:pre [(pos? x)]}
                x))
        arity (first (filter vector? (tree-seq coll? seq exp)))]
    ;; The params vector is followed by the prepost map, then try.
    (is (some map? (rest (drop-while (complement vector?)
                                     (tree-seq sequential? seq exp)))))))
