(ns hygaccess.operation-test
  "Smoke tests for the compiled HygieneAccessOperationActor graph
  itself (build + one happy path per op). The governor's full rule
  contract (HARD holds, escalation, phase gating) is exercised in
  `hygaccess.governor-contract-test`; the Store contract in
  `hygaccess.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [hygaccess.operation :as op]
            [hygaccess.store :as store]))

(def coordinator {:actor-id "coord-1" :actor-role :hygiene-access-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest test-actor-builds
  (testing "HygieneAccessOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-production-batch-logging-proposal
  (testing "Proposing a formulation-batch log auto-commits when clean (phase 3, no physical/financial/regulatory risk)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          initial-ledger-size (count (store/get-ledger s))
          result (exec-op actor "t1"
                          {:op :log-production-batch :effect :propose :subject "batch-001"
                           :patch {:off-spec-rate-pct 0.3}}
                          coordinator)
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-maintenance-scheduling
  (testing "Maintenance scheduling always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t2"
                          {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                           :value {:equipment-id "line-001" :maintenance-type :nozzle-inspection
                                   :scheduled-date "2026-08-01" :actuate-line? false}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t2") [:state :disposition]))))))

(deftest test-safety-concern-escalation
  (testing "Safety concerns always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t3"
                          {:op :flag-safety-concern :effect :propose :subject "concern-1"
                           :value {:equipment-id "line-001" :severity :moderate :description "次亜塩素酸蒸気曝露リスク上昇"}}
                          coordinator)]
      (is (= :interrupted (:status result))))))

(deftest test-shipment-coordination-proposal
  (testing "Shipment coordination proposal is submitted and (when within weight) escalates for approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t4"
                          {:op :coordinate-shipment :effect :propose :subject "ship-1"
                           :value {:batch-id "batch-001" :weight-kg 50.0
                                   :destination "ngo-distribution-hub-north"}}
                          coordinator)]
      (is (some? result))
      (is (= :interrupted (:status result))))))

(deftest test-packaging-design-proposal
  (testing "Packaging-design proposal always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t5"
                          {:op :propose-packaging-design :effect :propose :subject "pkg-1"
                           :value {:product-type :water-purification-drops
                                   :format :small-bottle-50ml :net-content "50ml"}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t5") [:state :disposition]))))))

(deftest test-market-entry-proposal
  (testing "Market-entry proposal (approved country, price within ceiling, licensed partner) always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t6"
                          {:op :propose-market-entry :effect :propose :subject "mkt-1"
                           :value {:product-type :water-purification-drops :country "IN"
                                   :price-minor 350000 :channel :ngo-who-program
                                   :channel-partner-id "partner-ngo-1"}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t6") [:state :disposition]))))))

(deftest test-marketing-claim-proposal
  (testing "Marketing-claim proposal (substantiated claim) always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t7"
                          {:op :propose-marketing-claim :effect :propose :subject "clm-1"
                           :value {:product-type :surface-disinfectant
                                   :claim "disinfects hard non-porous surfaces"}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t7") [:state :disposition]))))))

(deftest test-mes-reading-proposal
  (testing "A clean MES/CFD telemetry reading auto-commits (phase 3, administrative logging, no physical/financial/regulatory decision)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t8"
                          {:op :record-mes-reading :effect :propose :subject "mes-1"
                           :value {:batch-id "batch-001" :temperature-c 28.0 :ph 11.5
                                   :mixing-rpm 100.0 :mixing-homogeneity-cov-pct 2.0
                                   :source :mock-mes}}
                          coordinator)]
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-regulatory-submission-status-proposal
  (testing "Regulatory-submission-status transition always escalates for human approval (STATUS TRACKING ONLY, never a real filing)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t9"
                          {:op :record-regulatory-submission-status :effect :propose :subject "reg-1"
                           :value {:market "IN" :product-type :water-purification-drops
                                   :to-status :counsel-review}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t9") [:state :disposition]))))))

(deftest test-sales-order-proposal
  (testing "Sales-order proposal (market-approved, price matches registered SKU) always escalates for human approval -- never a real sale/payment"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t10"
                          {:op :propose-sales-order :effect :propose :subject "ord-1"
                           :value {:buyer-ref "ngo-buyer-1"
                                   :sku "int.hygaccess.water-purification-drops"
                                   :quantity 100 :price-minor 350000 :market "IN"}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t10") [:state :disposition]))))))

(deftest test-fulfillment-status-proposal
  (testing "Fulfillment-status transition always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          _ (exec-op actor "t11a"
                     {:op :propose-sales-order :effect :propose :subject "ord-2"
                      :value {:buyer-ref "ngo-buyer-1"
                              :sku "int.hygaccess.water-purification-drops"
                              :quantity 100 :price-minor 350000 :market "IN"}}
                     coordinator)
          _ (approve! actor "t11a")
          result (exec-op actor "t11b"
                          {:op :update-fulfillment-status :effect :propose :subject "ord-2"
                           :value {:to-status :packed}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t11b") [:state :disposition]))))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "The domain-agnostic commit-record! path stores a raw record by :id"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))
