(ns agentic.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [agentic.core :as a]))

(create-ns 'agentic.test-subject)

(defn- install!
  [vname f]
  (intern 'agentic.test-subject vname f)
  (ns-resolve 'agentic.test-subject vname))

(defn- subject
  [vname]
  (ns-resolve 'agentic.test-subject vname))

(deftest capture-incident
  (testing "exception, var, args, and stack become a data map"
    (let [v (install! 'explode (fn [n] (throw (ex-info "nope" {:n n}))))]
      (try
        (v 42)
        (is false "expected throw")
        (catch Exception e
          (let [inc (a/incident e {:var v :args [42]})]
            (is (string? (:id inc)))
            (is (string? (:at inc)))
            (is (= 'agentic.test-subject/explode (:var inc)))
            (is (= 'agentic.test-subject (:ns inc)))
            (is (= [42] (:args inc)))
            (is (= "nope" (get-in inc [:exception :message])))
            (is (= {:n 42} (get-in inc [:exception :data])))
            (is (= "clojure.lang.ExceptionInfo" (get-in inc [:exception :class])))
            (is (vector? (get-in inc [:exception :stack])))
            (is (seq (get-in inc [:exception :stack])))
            (is (not (contains? inc :throwable))
                "incident is data; no live throwable key")))))))

(deftest apply-patch-replaces-var
  (let [v (install! 'add (fn [x y] (+ x y)))
        rt (a/runtime)]
    (is (= 3 (v 1 2)))
    (let [entry (a/apply-patch! rt {:var v :fn (fn [x y] (* x y)) :note "multiply"})]
      (is (= :applied (:status entry)))
      (is (= 2 (v 1 2)))
      (is (true? (:agentic/patched? (meta v))))
      (let [h (a/history rt)
            last-entry (last h)]
        (is (= 1 (count h)))
        (is (= :applied (:status last-entry)))
        (is (= 'agentic.test-subject/add (:var last-entry)))
        (is (= "multiply" (get-in last-entry [:patch :note])))
        (is (nil? (get-in last-entry [:patch :fn])))
        (is (nil? (get-in last-entry [:previous :value])))))))

(deftest apply-patch-from-form-and-source
  (testing ":form is eval'd in the var's ns"
    (let [v (install! 'inc-ish (fn [x] x))
          rt (a/runtime)]
      (a/apply-patch! rt {:var 'agentic.test-subject/inc-ish
                          :form '(fn [x] (inc x))})
      (is (= 2 (v 1)))
      (is (= "(fn [x] (inc x))" (:agentic/source (meta v))))))
  (testing ":source is read then eval'd"
    (let [v (install! 'dec-ish (fn [x] x))
          rt (a/runtime)]
      (a/apply-patch! rt {:var v :source "(fn [x] (dec x))"})
      (is (= 0 (v 1))))))

(deftest rollback-restores-previous-definition
  (let [v (install! 'add (fn [x y] (+ x y)))
        rt (a/runtime)]
    (a/apply-patch! rt {:var v :fn (fn [x y] (* x y))})
    (is (= 20 (v 4 5)))
    (let [result (a/rollback! rt v)]
      (is (= :rolled-back (:status result)))
      (is (= 9 (v 4 5)))
      (is (not (:agentic/patched? (meta v))))
      (is (some #{:rolled-back} (map :status (a/history rt))))))
  (testing "second rollback walks further back"
    (let [v (install! 'step (fn [x] x))
          rt (a/runtime)]
      (a/apply-patch! rt {:var v :fn (fn [x] (inc x))})
      (a/apply-patch! rt {:var v :fn (fn [x] (* x 10))})
      (is (= 50 (v 5)))
      (a/rollback! rt v)
      (is (= 6 (v 5)))
      (a/rollback! rt v)
      (is (= 5 (v 5)))
      (is (thrown-with-msg? Exception #"Nothing to roll back"
                            (a/rollback! rt v))))))

(deftest retry-on-success
  (let [v (install! 'div (fn [x y] (/ x y)))
        healer (fn [inc]
                 {:var (:var inc)
                  :form '(fn [x y] (if (zero? y) :inf (/ x y)))
                  :note "guard zero"})
        rt (a/runtime {:healer healer})
        ret (a/invoke rt {:var v :args [1 0] :retry? true})]
    (is (= :inf ret))
    (is (= :inf (v 10 0)))
    (is (= #{:applied :retried} (set (map :status (a/history rt)))))
    (is (some #(and (= :retried (:status %)) (true? (:ok? %)))
              (a/history rt)))))

(deftest reject-bad-patch
  (let [v (install! 'id identity)
        rt (a/runtime)]
    (testing "validate-patch rejects incomplete or illegal patches"
      (is (thrown-with-msg? Exception #"Patch must be a map"
                            (a/validate-patch :nope)))
      (is (thrown-with-msg? Exception #"Patch requires :var"
                            (a/validate-patch {:fn inc})))
      (is (thrown-with-msg? Exception #"exactly one"
                            (a/validate-patch {:var v})))
      (is (thrown-with-msg? Exception #"exactly one"
                            (a/validate-patch {:var v :fn inc :source "(fn [x] x)"})))
      (is (thrown-with-msg? Exception #"not readable"
                            (a/validate-patch {:var v :source ")not-edn"})))
      (is (= {:var v :fn identity}
             (a/validate-patch {:var v :fn identity}))))
    (testing "apply-patch! does not mutate the var on rejection"
      (is (thrown? Exception (a/apply-patch! rt {:var v :source ")bad"})))
      (is (= :ok (v :ok)))
      (is (empty? (a/history rt)))))
  (testing "invoke records :rejected and leaves the var unchanged"
    (let [v (install! 'boom (fn [] (throw (ex-info "x" {}))))
          before (deref v)
          rt (a/runtime {:healer (a/constant-healer {:var v :source "(((("})})]
      (is (thrown-with-msg? Exception #"x"
                            (a/invoke rt {:var v :args []})))
      (is (identical? before (deref v)))
      (is (= :rejected (:status (last (a/history rt)))))
      (is (= :unreadable-source (:reason (last (a/history rt))))))))

(deftest healer-decline-does-not-heal
  (let [boom (fn [_] (throw (ex-info "still boom" {})))
        v (install! 'k boom)
        rt (a/runtime {:healer a/stub-healer})]
    (is (thrown-with-msg? Exception #"still boom"
                          (a/invoke rt {:var v :args [1]})))
    (is (thrown-with-msg? Exception #"still boom" (v 1)))
    (is (= [:declined] (mapv :status (a/history rt)))))
  (testing ":decline keyword and map are also declines"
    (doseq [reply [:decline {:decline true}]]
      (let [v (install! 'k2 (fn [] (throw (ex-info "no" {}))))
            rt (a/runtime {:healer (a/constant-healer reply)})]
        (is (thrown? Exception (a/invoke rt {:var v :args []})))
        (is (= :declined (:status (last (a/history rt)))))))))

(deftest history-is-plain-data
  (let [v (install! 'div (fn [x y] (/ x y)))
        rt (a/runtime {:healer (fn [inc]
                                 {:var (:var inc)
                                  :source "(fn [x y] :healed)"})})
        _ (a/invoke rt {:var v :args [1 0]})
        h (a/history rt)
        round-trip (read-string (pr-str h))]
    (is (seq h))
    (is (= h round-trip) "history prints and reads as EDN")
    (is (every? map? h))))

(deftest wrap-and-heal-helpers
  (testing "wrap routes calls through invoke"
    (let [v (install! 'div (fn [x y] (/ x y)))
          rt (a/runtime {:healer (fn [inc]
                                   {:var (:var inc)
                                    :fn (fn [x y] (if (zero? y) :inf (/ x y)))})})
          wrapped (a/wrap rt v)]
      (is (= :inf (wrapped 3 0)))))
  (testing "heal! applies a constant healer"
    (let [v (install! 'oops (fn [] (throw (ex-info "bad" {}))))
          rt (a/runtime {:healer (a/constant-healer {:var v :fn (fn [] :ok)})})
          inc (try (v) (catch Exception e (a/incident e {:var v :args []})))]
      (is (= :applied (:status (a/heal! rt inc))))
      (is (= :ok (v))))))
