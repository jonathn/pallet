(ns pallet.target-ops-test
  (:refer-clojure :exclude [sync])
  (:require
   [clojure.core.async :refer [>! >!! <!! chan go put!]]
   [clojure.stacktrace :refer [root-cause]]
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.actions :refer [exec-script*]]
   [pallet.compute.protocols :as impl]
   [pallet.core.executor.plan :refer [plan-executor]]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-info]]
   [pallet.node :as node]
   [pallet.plan :refer [errors plan-fn]]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.spec :refer [server-spec]]
   [pallet.target-ops :refer :all]
   [pallet.user :as user]
   [pallet.utils.async :refer [go-try sync]]
   [schema.core :as schema :refer [validate]]
   [taoensso.timbre :refer [debugf]]))

(use-fixtures :once (logging-threshold-fixture))

(defn plan-session
  "Return a session with a plan executor."
  []
  (-> (session/create {:executor (plan-executor)
                       :recorder (in-memory-recorder)})
      (set-user user/*admin-user*)))

(deftest lift-phase-test
  (let [phase-x {:phase :x}
        phase-y {:phase :y}]
    (testing "with a target with two phases"
      (let [spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls"))
                                        :y (plan-fn [session]
                                             (exec-script* session "ls")
                                             (exec-script* session "pwd"))}})
            node {:id "id" :os-family :ubuntu :os-version "13.10"
                  :packager :apt}
            ;; target {:target node :phases (:phases spec)}
            session (plan-session)]
        (testing "lifting one phase"
          (let [target-plan (target-plan {:target node :spec spec} phase-x)
                [result :as results] (sync
                                      (lift-phase
                                       session
                                       {:result-id phase-x
                                        :target-plans [target-plan]}))]
            (is (= 1 (count results)) "Runs a single phase on a single node")
            (is (= :x (:phase result)) "labels the phase in the result")
            (is (= node (:target result)) "labels the target in the result")
            (is (= 1 (count (:action-results result))) "Runs the plan action")
            (is (= ["ls"] (:args (first (:action-results result))))
                "invokes the correct phase")
            (is (not (errors results)))))
        (testing "with a second node"
          (testing "lifting the other phase"
            (let [node2 (assoc node :id "id2")
                  target2 (assoc spec :node node2)
                  target-plans [(target-plan
                                 {:target node :spec spec} phase-y)
                                (target-plan
                                 {:target node2 :spec spec} phase-y)]
                  results (sync (lift-phase session
                                            {:result-id phase-y
                                             :target-plans target-plans}))]
              (is (= 2 (count results)) "Runs a single phase on a both nodes")
              (is (every? #(= :y (:phase %)) results)
                  "labels the phase in the results")
              (is (= #{node node2} (set (map :target results)))
                  "labels the target in the results")
              (is (every? #(= 2 (count (:action-results %))) results)
                  "Runs the plan action")
              (is (not (errors results))))))))
    (testing "with a target with a phase that throws"
      (let [e (ex-info "some error" {})
            spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls")
                                             (throw e))}})
            node {:id "id" :os-family :ubuntu :os-version "13.10"
                  :packager :apt}
            session (plan-session)
            target-plan (target-plan {:target node :spec spec} phase-x)]
        (testing "lifting one phase"
          (is (thrown-with-msg?
               Exception #"lift-phase failed"
               (sync
                (lift-phase session {:result-id {:phase :x}
                                     :target-plans [target-plan]}))))
          (let [e (try
                    (sync (lift-phase session {:result-id {:phase :x}
                                               :target-plans [target-plan]}))
                    (catch Exception e
                      e))
                data (ex-data e)
                [result :as results] (:results data)]
            (is (contains? data :results))
            (is (= 1 (count results)) "Runs a single phase on a single node")
            (is (= :x (:phase result)) "labels the phase in the result")
            (is (= node (:target result)) "labels the target in the result")
            (is (= 1 (count (:action-results result))) "Runs the plan action")
            (is (= ["ls"] (:args (first (:action-results result))))
                "invokes the correct phase")))))
    (testing "with a target with a phase that throws a domain error"
      (let [e (domain-info "some error" {})
            spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls")
                                             (throw e))}})
            node {:id "id" :os-family :ubuntu :os-version "13.10"
                  :packager :apt}
            session (plan-session)
            target-plan (target-plan {:target node :spec spec} phase-x)]
        (testing "lifting one phase"
          (let [[result :as results] (sync
                                      (lift-phase
                                       session
                                       {:result-id phase-x
                                        :target-plans [target-plan]}))]
            (is (= 1 (count results)) "Runs a single phase on a single node")
            (is (= :x (:phase result)) "labels the phase in the result")
            (is (= node (:target result)) "labels the target in the result")
            (is (= 1 (count (:action-results result))) "Runs the plan action")
            (is (= ["ls"] (:args (first (:action-results result))))
                "invokes the correct phase")
            (is (errors results))))))))


(deftest synch-phases-test
  (testing "single-step"
    (testing "with no modifiers"
      (let [step {:op (fn [_ c] (go (>! c [[{:ok true}] nil])))}
            c (chan)]
        (synch-phases [step] nil c)
        (let [r (<!! c)]
          (is (= [[{:ok true}] nil] r)))))
    (testing "with no modifiers and an initial state"
      (let [step {:op (fn [state c] (go (>! c [[state] nil])))}
            c (chan)]
        (synch-phases [step] {:init-state 1} c)
        (let [r (<!! c)]
          (is (= [[{:init-state 1}] nil] r))))))
  (testing "two-steps"
    (testing "with no modifiers"
      (let [steps [{:op (fn [_ c] (go (>! c [[{:ok1 true}] nil])))}
                   {:op (fn [_ c] (go (>! c [[{:ok2 true}] nil])))}]
            c (chan)]
        (synch-phases steps nil c)
        (let [r (<!! c)]
          (is (= [[{:ok1 true} {:ok2 true}] nil] r)))))
    (testing "with :state-update"
      (let [steps [{:op (fn [_ c] (go (>! c [[{:ok1 true}] nil])))
                    :state-update (fn [result state]
                                    (update-in state [:i] inc))}
                   {:op (fn [state c] (go (>! c [[state] nil])))}]
            c (chan)]
        (synch-phases steps {:i 0} c)
        (let [r (<!! c)]
          (is (= [[{:ok1 true} {:i 1}] nil] r)))))
    (testing "with :flow aborter"
      (let [steps [{:op (fn [_ c] (go (>! c [[{:ok1 true}] nil])))
                    :flow (fn [_ _ _])}
                   {:op (fn [_ c] (go (>! c [[{:not-ok2 false}] nil])))}]
            c (chan)]
        (synch-phases steps {:i 0} c)
        (let [r (<!! c)]
          (is (= [[{:ok1 true} ] nil] r)))))
    (testing "with :flow step addition"
      (let [steps [{:op (fn [_ c] (go (>! c [[{:ok1 true}] nil])))
                    :flow (fn [_ _ _]
                            [{:op (fn [_ c]
                                    (go (>! c [[{:ok2 true}] nil])))}])}]
            c (chan)]
        (synch-phases steps {:i 0} c)
        (let [r (<!! c)]
          (is (= [[{:ok1 true}{:ok2 true}] nil] r)))))))

(deftest lift-abort-on-error-test
  (let [node {:id "id" :os-family :ubuntu :os-version "13.10" :packager :apt}
        node2 (assoc node :id "id2")]

    (testing "with two targets with two phases"
      (let [spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls"))
                                        :y (plan-fn [session]
                                             (exec-script* session "ls")
                                             (exec-script* session "pwd"))}})
            target-spec {:spec spec :target node}
            spec2 (server-spec {:phases {:x (plan-fn [session]
                                              (exec-script* session "ls"))
                                         :y (plan-fn [session]
                                              (exec-script* session "ls")
                                              (exec-script* session "pwd"))}})
            target-spec2 {:spec spec :target node2}
            target-specs [target-spec target-spec2]
            session (plan-session)]
        (testing "lifting two phases"
          (let [target-phases (mapv #(target-phase target-specs %)
                                    [{:phase :x} {:phase :y}])
                results (sync (lift-abort-on-error session target-phases))]
            (is (= 4 (count results)) "Runs two plans on two nodes")
            (is (every? :phase results) "labels the target phases")
            (is (every? :target results) "labels the target in the result")
            (is (every? #(pos? (count (:action-results %))) results)
                "Runs the plan action")
            (is (not (errors results)) "Has no errors")))))

    (testing "with two targets with two phases with exceptions"
      (let [spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls"))
                                        :y (plan-fn [session]
                                             (exec-script* session "ls")
                                             (throw
                                              (domain-info "some error" {}))
                                             (exec-script* session "pwd"))}})
            target-spec {:spec spec :target node}
            spec2 (server-spec {:phases {:x (plan-fn [session]
                                              (exec-script* session "ls")
                                              (throw (ex-info "some error" {})))
                                         :y (plan-fn [session]
                                              (exec-script* session "ls")
                                              (exec-script* session "pwd"))}})

            target-spec2 {:spec spec2 :target node2}
            target-specs [target-spec target-spec2]
            session (plan-session)]
        (testing "lifting two phases, with non-domain exception"
          (let [target-phases (mapv #(target-phase target-specs %)
                                    [{:phase :x} {:phase :y}])]
            (is (thrown-with-msg?
                 Exception #"lift-phase failed"
                 (sync (lift-abort-on-error session target-phases))))
            (let [e (try
                      (sync (lift-abort-on-error session target-phases))
                      (catch Exception e
                        e))
                  {:keys [exceptions results]} (ex-data e)]
              (is (= 2 (count results)) "Runs one plan on two nodes")
              (is (every? #(= :x (:phase %)) results) "only runs the :x phase")
              (is (every? :target results) "labels the target in the result")
              (is (every? #(pos? (count (:action-results %))) results)
                  "Runs the plan action")
              (is (errors results) "Has errors"))))
        (testing "lifting two phases, with domain exception"
          (let [target-phases (mapv #(target-phase target-specs %)
                                    [{:phase :y} {:phase :x}])
                results (sync (lift-abort-on-error session target-phases))]
            (is (= 2 (count results)) "Runs one plan on two nodes")
            (is (every? #(= :y (:phase %)) results) "only runs the :y phase")
            (is (every? :target results) "labels the target in the result")
            (is (every? #(pos? (count (:action-results %))) results)
                "Runs the plan action")
            (is (errors results) "Has errors")))))))


(defrecord OneShotCreateService []
  impl/ComputeServiceNodeCreateDestroy
  (create-nodes [_ node-spec user node-count options ch]
    (go-try ch
      (debugf "creating new node")
      (>! ch [(take node-count
                    (repeatedly
                     (fn []
                       {:target {:id (name (gensym "id"))
                                 :os-family :ubuntu
                                 :packager :apt}
                        :phase :pallet.compute/create-nodes
                        :return-value :pallet.compute/target-created})))]))))

(deftest create-targets-test
  (testing "Create targets with explicit phase, no plan-state."
    (let [session (plan-session)
          results (sync (create-targets
                         session
                         (OneShotCreateService.)
                         {:image {:image-id "x" :os-family :ubuntu}}
                         (session/user session)
                         3
                         "base"
                         (plan-fn [session]
                           (debugf "settings for new node"))
                         (plan-fn [session]
                           (debugf "bootstrap for new node")
                           {:os-family :ubuntu})))]
      (is (= 9 (count results))
          "creates the correct number of targets and phases")
      (is (= 3 (count
                (filter #(= :pallet.compute/create-nodes (:phase %)) results)))
          "creates the correct number of targets")
      (is (not (errors (:results results)))
          "doesn't report any errors"))))