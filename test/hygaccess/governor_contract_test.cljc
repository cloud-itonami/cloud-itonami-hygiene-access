(ns hygaccess.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT control the formulation/filling line
  directly... does NOT decide or grant a chemical-safety/medical/
  regulatory certification... is NOT literal real-world sales
  infrastructure') implemented faithfully. The single invariant under
  test:

    HygieneAccessAdvisor never schedules maintenance, flags a safety
    concern, coordinates a shipment, proposes a packaging design,
    proposes a market entry, or proposes a marketing claim the Hygiene
    Access Operations Governor would reject; `:schedule-maintenance`/
    `:flag-safety-concern`/`:coordinate-shipment`/`:propose-market-
    entry`/`:propose-marketing-claim` NEVER auto-commit at any phase;
    `:log-production-batch` (no physical/financial/regulatory risk)
    MAY auto-commit when clean; and every decision (commit OR hold)
    leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [hygaccess.store :as store]
            [hygaccess.operation :as op]
            [hygaccess.governor :as governor]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :hygiene-access-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

;; ----------------------------- baseline -----------------------------

(deftest clean-log-production-batch-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-production-batch :effect :propose :subject "batch-001"
                   :patch {:off-spec-rate-pct 0.3}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 0.3 (:off-spec-rate-pct (store/batch db "batch-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-maintenance-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                     :value {:equipment-id "line-001" :maintenance-type :nozzle-inspection
                             :scheduled-date "2026-08-01" :actuate-line? false}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/maintenance db "mnt-1"))))
        (is (= 1 (count (store/maintenance-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-production-batch :effect :direct-write :subject "batch-001"
                     :patch {:off-spec-rate-pct 0.3}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :actuate-mixer :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

;; ----------------------------- ground-truth gates -----------------------------

(deftest equipment-not-verified-is-held-and-unoverridable
  (testing "scheduling against an unverified/unregistered equipment unit -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                     :value {:equipment-id "line-002" :maintenance-type :seal-inspection
                             :scheduled-date "2026-08-01" :actuate-line? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:equipment-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/maintenance-history db))))))

(deftest batch-not-verified-is-held-and-unoverridable
  (testing "coordinating a shipment against an unverified/unregistered batch -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :coordinate-shipment :effect :propose :subject "ship-2"
                     :value {:batch-id "batch-003" :weight-kg 50.0
                             :destination "informal-retail-south"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:batch-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest shipment-weight-exceeded-is-held-and-unoverridable
  (testing "a shipment proposal whose weight would exceed the batch's own logged weight -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :coordinate-shipment :effect :propose :subject "ship-3"
                     :value {:batch-id "batch-002" :weight-kg 100.0
                             :destination "institutional-buyer-east"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:shipment-weight-exceeded} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

;; ----------------------------- permanent blocks -----------------------------

(deftest line-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-line? true -> HOLD, PERMANENT, never reaches request-approval even though the equipment is verified and registered"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                     :value {:equipment-id "line-001" :maintenance-type :force-run
                             :scheduled-date "2026-09-01" :actuate-line? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:line-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/maintenance-history db))))))

(deftest certification-decision-is-held-and-permanently-blocked
  (testing "a proposal that sets :decide-certification? true -> HOLD, PERMANENT, never reaches request-approval -- deciding a certification is exclusively the applicable authority's call"
    (let [[db actor] (fresh)
          res (exec-op actor "t8b"
                    {:op :log-production-batch :effect :propose :subject "batch-001"
                     :patch {:decide-certification? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:certification-decision-blocked} (-> (store/ledger db) last :basis)))
      (is (not (:decide-certification? (store/batch db "batch-001")))
          "the certification-decision attempt never lands in the SSoT"))))

(deftest no-toxic-co-formulation-is-held-and-permanently-blocked
  (testing "a batch patch declaring an acid co-ingredient alongside sodium-hypochlorite (already the batch's own recorded active) -> HOLD, PERMANENT, never reaches request-approval -- toxic chlorine-gas hazard, mirrors etzhayyim/com-etzhayyim-yakushi's own G22"
    (let [[db actor] (fresh)
          res (exec-op actor "t8c"
                    {:op :log-production-batch :effect :propose :subject "batch-001"
                     :patch {:co-ingredients #{:hydrochloric-acid}}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:no-toxic-co-formulation-blocked} (-> (store/ledger db) last :basis)))
      (is (not= #{:hydrochloric-acid} (:co-ingredients (store/batch db "batch-001")))
          "the toxic co-formulation attempt never lands in the SSoT"))))

(deftest no-toxic-co-formulation-blocks-ammonia-too
  (let [[db actor] (fresh)
        res (exec-op actor "t8d"
                  {:op :log-production-batch :effect :propose :subject "batch-002"
                   :patch {:co-ingredients #{:ammonia}}}
                  coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:no-toxic-co-formulation-blocked} (-> (store/ledger db) last :basis)))))

(deftest no-toxic-co-formulation-does-not-block-ipmp-plus-acid
  (testing "the toxic-gas hazard is specific to sodium-hypochlorite -- IPMP (batch-003's own recorded active) + an acid-named co-ingredient is not this hazard and auto-commits when otherwise clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t8e"
                    {:op :log-production-batch :effect :propose :subject "batch-003"
                     :patch {:co-ingredients #{:citric-acid}}}
                    coordinator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not (some #{:no-toxic-co-formulation-blocked} (-> (store/ledger db) last :basis)))))))

;; ----------------------------- double-schedule / product-type / active / efficacy window / off-spec -----------------------------

(deftest schedule-maintenance-double-schedule-is-held
  (testing "scheduling the SAME maintenance record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9a" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                  :value {:equipment-id "line-001" :maintenance-type :nozzle-inspection
                                          :scheduled-date "2026-08-01" :actuate-line? false}} coordinator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                   :value {:equipment-id "line-001" :maintenance-type :nozzle-inspection
                                           :scheduled-date "2026-08-01" :actuate-line? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/maintenance-history db))) "still only the one earlier schedule"))))

(deftest invalid-product-type-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :log-production-batch :effect :propose :subject "batch-001"
                                  :patch {:product-type :unobtainium-disinfectant}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-product-type} (-> (store/ledger db) last :basis)))
    (is (not= :unobtainium-disinfectant (:product-type (store/batch db "batch-001"))) "fabricated product-type never lands in the SSoT")))

(deftest invalid-active-for-product-type-is-held
  (testing "an active not authorized for the (effective) product-type -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :log-production-batch :effect :propose :subject "batch-001"
                                    :patch {:active :isopropylmethylphenol}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:invalid-active-for-product-type} (-> (store/ledger db) last :basis)))
      (is (not= :isopropylmethylphenol (:active (store/batch db "batch-001")))))))

(deftest concentration-outside-efficacy-window-is-held
  (testing "a concentration far outside the [active product-type]'s own efficacy window -> HOLD -- '濃ければ強い is FALSE'"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :log-production-batch :effect :propose :subject "batch-001"
                                    :patch {:concentration-pct 5.0}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:concentration-outside-efficacy-window} (-> (store/ledger db) last :basis)))
      (is (= 1.0 (:concentration-pct (store/batch db "batch-001")))
          "the out-of-window concentration patch never lands in the SSoT -- batch-001 keeps its seeded concentration"))))

(deftest concentration-within-efficacy-window-auto-commits
  (testing "a concentration patch that stays within the [active product-type]'s own efficacy window -> auto-commits cleanly"
    (let [[db actor] (fresh)
          res (exec-op actor "t13" {:op :log-production-batch :effect :propose :subject "batch-001"
                                    :patch {:concentration-pct 1.2}} coordinator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 1.2 (:concentration-pct (store/batch db "batch-001")))))))

(deftest invalid-off-spec-rate-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t14" {:op :log-production-batch :effect :propose :subject "batch-001"
                                  :patch {:off-spec-rate-pct 150.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-off-spec-rate} (-> (store/ledger db) last :basis)))
    (is (not= 150.0 (:off-spec-rate-pct (store/batch db "batch-001"))) "fabricated off-spec-rate reading never lands in the SSoT")))

;; ----------------------------- packaging -----------------------------

(deftest invalid-packaging-format-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t15" {:op :propose-packaging-design :effect :propose :subject "pkg-2"
                                  :value {:product-type :surface-disinfectant
                                          :format :bulk-drum-200l :net-content "200L"}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-packaging-format} (-> (store/ledger db) last :basis)))
    (is (empty? (store/packaging-design-history db)))))

(deftest packaging-design-with-valid-format-always-needs-approval
  (let [[db actor] (fresh)
        res (exec-op actor "t16" {:op :propose-packaging-design :effect :propose :subject "pkg-1"
                                  :value {:product-type :water-purification-drops
                                          :format :small-bottle-50ml :net-content "50ml"}} coordinator)]
    (is (= :interrupted (:status res)))
    (let [r2 (approve! actor "t16")]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= 1 (count (store/packaging-design-history db)))))))

;; ----------------------------- market entry -----------------------------

(deftest market-not-approved-is-held
  (testing "a market-entry proposal targeting a not-yet-approved country -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t17" {:op :propose-market-entry :effect :propose :subject "mkt-2"
                                    :value {:product-type :surface-disinfectant :country "PK"
                                            :price-minor 700000 :channel :licensed-informal-retail-aggregator
                                            :channel-partner-id "partner-retail-1"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:market-not-approved} (-> (store/ledger db) last :basis)))
      (is (empty? (store/market-entry-history db))))))

(deftest price-above-ceiling-is-held
  (testing "a market-entry proposal priced above its product type's own affordability ceiling -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t18" {:op :propose-market-entry :effect :propose :subject "mkt-3"
                                    :value {:product-type :antibacterial-soap :country "IN"
                                            :price-minor 500000 :channel :microfinance-social-enterprise
                                            :channel-partner-id "partner-mfi-1"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:price-above-ceiling} (-> (store/ledger db) last :basis)))
      (is (empty? (store/market-entry-history db))))))

(deftest channel-partner-not-licensed-is-held
  (testing "a market-entry proposal citing an unlicensed channel partner -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t19" {:op :propose-market-entry :effect :propose :subject "mkt-4"
                                    :value {:product-type :antibacterial-soap :country "IN"
                                            :price-minor 200000 :channel :licensed-informal-retail-aggregator
                                            :channel-partner-id "partner-retail-2"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:channel-partner-not-licensed} (-> (store/ledger db) last :basis)))
      (is (empty? (store/market-entry-history db))))))

(deftest market-entry-always-needs-approval-even-when-clean
  (testing "market-approved + within-ceiling + licensed-partner is never auto-eligible -- always escalates (:coordination/new-market-entry high-stakes AND never in any phase's :auto set)"
    (let [[db actor] (fresh)
          res (exec-op actor "t20" {:op :propose-market-entry :effect :propose :subject "mkt-1"
                                    :value {:product-type :water-purification-drops :country "IN"
                                            :price-minor 350000 :channel :ngo-who-program
                                            :channel-partner-id "partner-ngo-1"}} coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t20")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/market-entry-history db))))))))

;; ----------------------------- marketing claim -----------------------------

(deftest claim-not-substantiated-is-held
  (testing "a marketing-claim proposal citing an unsubstantiated health claim -> HOLD -- first-class ethical guardrail"
    (let [[db actor] (fresh)
          res (exec-op actor "t21" {:op :propose-marketing-claim :effect :propose :subject "clm-2"
                                    :value {:product-type :water-purification-drops
                                            :claim "cures cholera and typhoid"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:claim-not-substantiated} (-> (store/ledger db) last :basis)))
      (is (empty? (store/marketing-claim-history db))))))

(deftest marketing-claim-always-needs-approval-even-when-clean
  (testing "a substantiated claim is never auto-eligible -- always escalates (:coordination/marketing-claim-change high-stakes AND never in any phase's :auto set)"
    (let [[db actor] (fresh)
          res (exec-op actor "t22" {:op :propose-marketing-claim :effect :propose :subject "clm-1"
                                    :value {:product-type :surface-disinfectant
                                            :claim "disinfects hard non-porous surfaces"}} coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t22")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/marketing-claim-history db))))))))

;; ----------------------------- safety concerns -----------------------------

(deftest safety-concern-always-escalates-even-high-confidence
  (testing "flag-safety-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t23" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                                    :value {:equipment-id "line-001" :severity :moderate
                                            :description "next-hypochlorite vapor exposure risk rising near formulation equipment"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t23")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/safety-concerns db))))))))

(deftest safety-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t24" {:op :flag-safety-concern :effect :propose :subject "concern-2"
                                :value {:equipment-id "line-001" :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t24")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/safety-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

;; ----------------------------- shipment -----------------------------

(deftest coordinate-shipment-always-needs-approval
  (testing "a CLEAN shipment coordination is never auto-eligible -- always escalates, even below any weight threshold"
    (let [[db actor] (fresh)
          res (exec-op actor "t25" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                                    :value {:batch-id "batch-001" :weight-kg 50.0
                                            :destination "ngo-distribution-hub-north"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t25")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/shipment-history db))))))))

;; ----------------------------- GMP raw-material-lot release (log-production-batch) -----------------------------

(deftest raw-material-lot-not-verified-is-held
  (testing "a production-batch patch citing a NOT verified/registered raw-material lot -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t26" {:op :log-production-batch :effect :propose :subject "batch-005"
                                    :patch {:product-type :water-purification-drops
                                            :active :sodium-hypochlorite
                                            :concentration-pct 1.0
                                            :raw-material-lot-number "RM-LOT-NAOCL-003"}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:raw-material-lot-not-verified} (-> (store/ledger db) last :basis)))
      (is (nil? (store/batch db "batch-005")) "never lands a new batch in the SSoT"))))

(deftest raw-material-lot-coa-not-received-is-held
  (testing "a production-batch patch citing a verified/registered lot whose own CoA has NOT been received -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t27" {:op :log-production-batch :effect :propose :subject "batch-006"
                                    :patch {:product-type :water-purification-drops
                                            :active :sodium-hypochlorite
                                            :concentration-pct 1.0
                                            :raw-material-lot-number "RM-LOT-NAOCL-004"}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:raw-material-lot-coa-not-received} (-> (store/ledger db) last :basis)))
      (is (nil? (store/batch db "batch-006"))))))

(deftest raw-material-lot-assay-implausible-is-held
  (testing "a production-batch patch citing a lot whose own CoA assay is implausible for its active -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t28" {:op :log-production-batch :effect :propose :subject "batch-007"
                                    :patch {:product-type :water-purification-drops
                                            :active :sodium-hypochlorite
                                            :concentration-pct 1.0
                                            :raw-material-lot-number "RM-LOT-NAOCL-005"}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:raw-material-lot-assay-implausible} (-> (store/ledger db) last :basis)))
      (is (nil? (store/batch db "batch-007"))))))

(deftest raw-material-lot-clean-auto-commits
  (testing "a production-batch patch citing a verified/registered/CoA-received/plausible-assay lot -> auto-commits cleanly"
    (let [[db actor] (fresh)
          res (exec-op actor "t29" {:op :log-production-batch :effect :propose :subject "batch-008"
                                    :patch {:product-type :water-purification-drops
                                            :active :sodium-hypochlorite
                                            :concentration-pct 1.0
                                            :raw-material-lot-number "RM-LOT-NAOCL-001"}}
                       coordinator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= "RM-LOT-NAOCL-001" (:raw-material-lot-number (store/batch db "batch-008")))))))

;; ----------------------------- in-process QC (IPQC) mixing-homogeneity (log-production-batch) -----------------------------

(deftest mixing-homogeneity-cov-exceeds-threshold-is-held
  (testing "an IPQC mixing-homogeneity CoV above the 5.0% threshold -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t30" {:op :log-production-batch :effect :propose :subject "batch-001"
                                    :patch {:ipqc {:ph-check-pass? true :assay-mid-batch-pct 1.0
                                                    :mixing-homogeneity-cov-pct 7.5}}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:mixing-homogeneity-cov-exceeds-threshold} (-> (store/ledger db) last :basis)))
      (is (not= 7.5 (get-in (store/batch db "batch-001") [:ipqc :mixing-homogeneity-cov-pct]))
          "the out-of-threshold IPQC patch never lands in the SSoT -- batch-001 keeps its seeded 2.1% reading"))))

(deftest mixing-homogeneity-cov-within-threshold-auto-commits
  (testing "an IPQC mixing-homogeneity CoV within the 5.0% threshold -> auto-commits cleanly"
    (let [[db actor] (fresh)
          res (exec-op actor "t31" {:op :log-production-batch :effect :propose :subject "batch-001"
                                    :patch {:ipqc {:ph-check-pass? true :assay-mid-batch-pct 1.0
                                                    :mixing-homogeneity-cov-pct 3.0}}}
                       coordinator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 3.0 (get-in (store/batch db "batch-001") [:ipqc :mixing-homogeneity-cov-pct]))))))

;; ----------------------------- Certificate of Analysis (CoA) / batch-release sign-off (coordinate-shipment) -----------------------------

(deftest coa-not-pass-blocks-shipment-is-held
  (testing "a verified/registered/within-weight batch whose own CoA has NOT passed -> HOLD (batch-release sign-off incomplete)"
    (let [[db actor] (fresh)
          _ (store/commit-record! db {:effect :batch/upsert :path ["batch-100"]
                                      :value {:product-type :water-purification-drops
                                              :active :sodium-hypochlorite :concentration-pct 1.0
                                              :weight-kg 200.0 :shipped-weight-kg 0.0
                                              :verified? true :registered? true
                                              :coa {:coa-pass? false}
                                              :ipqc {:mixing-homogeneity-cov-pct 2.0}}})
          res (exec-op actor "t32" {:op :coordinate-shipment :effect :propose :subject "ship-100"
                                    :value {:batch-id "batch-100" :weight-kg 10.0
                                            :destination "informal-retail-north"}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:batch-release-qc-incomplete} (-> (store/ledger db) last :basis)))
      (is (not (some #{:batch-not-verified :shipment-weight-exceeded}
                      (-> (store/ledger db) last :basis)))
          "isolated to the CoA/IPQC batch-release gate -- the batch IS otherwise verified/registered/within-weight")
      (is (empty? (store/shipment-history db))))))

(deftest batch-release-qc-incomplete-homogeneity-exceeded-blocks-shipment-is-held
  (testing "a verified/registered/CoA-passing batch whose own stored IPQC homogeneity CoV exceeds threshold -> HOLD -- a second, independent re-derivation from the store even though `:log-production-batch` already gates this at intake"
    (let [[db actor] (fresh)
          _ (store/commit-record! db {:effect :batch/upsert :path ["batch-101"]
                                      :value {:product-type :water-purification-drops
                                              :active :sodium-hypochlorite :concentration-pct 1.0
                                              :weight-kg 200.0 :shipped-weight-kg 0.0
                                              :verified? true :registered? true
                                              :coa {:coa-pass? true}
                                              :ipqc {:mixing-homogeneity-cov-pct 9.0}}})
          res (exec-op actor "t33" {:op :coordinate-shipment :effect :propose :subject "ship-101"
                                    :value {:batch-id "batch-101" :weight-kg 10.0
                                            :destination "informal-retail-north"}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:batch-release-qc-incomplete} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest batch-release-qc-complete-permits-shipment-escalation
  (testing "a verified/registered/within-weight batch with a passing CoA AND in-threshold homogeneity -> clears the batch-release gate, still escalates for human approval (never auto-committed, same as every other shipment)"
    (let [[db actor] (fresh)
          _ (store/commit-record! db {:effect :batch/upsert :path ["batch-102"]
                                      :value {:product-type :water-purification-drops
                                              :active :sodium-hypochlorite :concentration-pct 1.0
                                              :weight-kg 200.0 :shipped-weight-kg 0.0
                                              :verified? true :registered? true
                                              :coa {:coa-pass? true}
                                              :ipqc {:mixing-homogeneity-cov-pct 2.0}}})
          res (exec-op actor "t34" {:op :coordinate-shipment :effect :propose :subject "ship-102"
                                    :value {:batch-id "batch-102" :weight-kg 10.0
                                            :destination "informal-retail-north"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t34")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/shipment-history db))))))))

;; ----------------------------- ledger discipline -----------------------------

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-production-batch :effect :propose :subject "batch-001"
                          :patch {:off-spec-rate-pct 0.3}} coordinator)
      (exec-op actor "b" {:op :log-production-batch :effect :propose :subject "batch-001"
                          :patch {:product-type :fabricated-product}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ============================================================
;; docs/adr/0003-mes-regulatory-sales-extensions.md
;; ============================================================

;; ----------------------------- MES (Manufacturing Execution System) reading -----------------------------

(deftest mes-reading-batch-not-verified-is-held
  (testing "an MES/CFD reading tied to an UNVERIFIED/unregistered batch -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "m1" {:op :record-mes-reading :effect :propose :subject "mes-h1"
                                   :value {:batch-id "batch-003" :mixing-homogeneity-cov-pct 2.0}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:mes-reading-batch-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/mes-reading-history db))))))

(deftest mes-reading-ph-implausible-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "m2" {:op :record-mes-reading :effect :propose :subject "mes-h2"
                                 :value {:batch-id "batch-001" :ph 15.0}}
                     coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:mes-reading-ph-implausible} (-> (store/ledger db) last :basis)))
    (is (empty? (store/mes-reading-history db)))))

(deftest mes-reading-temperature-implausible-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "m3" {:op :record-mes-reading :effect :propose :subject "mes-h3"
                                 :value {:batch-id "batch-001" :temperature-c 90.0}}
                     coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:mes-reading-temperature-implausible} (-> (store/ledger db) last :basis)))
    (is (empty? (store/mes-reading-history db)))))

(deftest mes-reading-rpm-implausible-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "m4" {:op :record-mes-reading :effect :propose :subject "mes-h4"
                                 :value {:batch-id "batch-001" :mixing-rpm 5000.0}}
                     coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:mes-reading-rpm-implausible} (-> (store/ledger db) last :basis)))
    (is (empty? (store/mes-reading-history db)))))

(deftest mes-reading-homogeneity-implausible-is-held
  (testing "physically impossible CoV (>100%) -- distinct from the 5.0% GMP acceptance threshold checked elsewhere"
    (let [[db actor] (fresh)
          res (exec-op actor "m5" {:op :record-mes-reading :effect :propose :subject "mes-h5"
                                   :value {:batch-id "batch-001" :mixing-homogeneity-cov-pct 150.0}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:mes-reading-homogeneity-implausible} (-> (store/ledger db) last :basis)))
      (is (empty? (store/mes-reading-history db))))))

(deftest mes-reading-clean-auto-commits
  (testing "a clean MES/CFD reading against a verified/registered batch, no prior reading on file -> phase-3 auto-commit, mirrors :log-production-batch's own administrative-logging posture"
    (let [[db actor] (fresh)
          res (exec-op actor "m6" {:op :record-mes-reading :effect :propose :subject "mes-h6"
                                   :value {:batch-id "batch-002" :temperature-c 25.0 :ph 12.0
                                           :mixing-rpm 100.0 :mixing-homogeneity-cov-pct 1.0
                                           :source :mock-mes}}
                       coordinator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (= "batch-002" (:batch-id (store/mes-reading db "mes-h6"))))
      (is (= 1 (count (store/mes-reading-history db)))))))

(deftest mes-reading-homogeneity-mismatch-is-held
  (testing "a SECOND MES/CFD reading for a batch whose own self-reported IPQC homogeneity no longer matches the FIRST already-committed MES reading -> HOLD -- closes the ground-truth loop, never self-report alone once independent MES ground truth exists"
    (let [[db actor] (fresh)
          r1 (exec-op actor "mm1" {:op :record-mes-reading :effect :propose :subject "mes-mm-1"
                                   :value {:batch-id "batch-001" :temperature-c 28.0 :ph 11.5
                                           :mixing-rpm 100.0 :mixing-homogeneity-cov-pct 2.1
                                           :source :mock-mes}}
                      coordinator)]
      (is (= :commit (get-in r1 [:state :disposition]))
          "2.1% matches batch-001's own seeded IPQC self-report -- clean, auto-commits, becomes ground truth on file")
      (exec-op actor "mm2" {:op :log-production-batch :effect :propose :subject "batch-001"
                            :patch {:ipqc {:ph-check-pass? true :assay-mid-batch-pct 1.0
                                           :mixing-homogeneity-cov-pct 4.0}}}
               coordinator)
      (is (= 4.0 (get-in (store/batch db "batch-001") [:ipqc :mixing-homogeneity-cov-pct]))
          "4.0% is still within the 5.0% GMP threshold ON ITS OWN -- auto-commits -- but now diverges from the prior MES ground-truth reading (2.1%) by more than the reconciliation tolerance (0.5)")
      (let [r3 (exec-op actor "mm3" {:op :record-mes-reading :effect :propose :subject "mes-mm-2"
                                     :value {:batch-id "batch-001" :temperature-c 28.0
                                             :mixing-homogeneity-cov-pct 4.0 :source :mock-mes}}
                        coordinator)]
        (is (= :hold (get-in r3 [:state :disposition])))
        (is (some #{:mes-reading-homogeneity-mismatch} (-> (store/ledger db) last :basis)))
        (is (= 1 (count (store/mes-reading-history db)))
            "the mismatched second reading never lands in the SSoT -- only mes-mm-1 is on file")))))

;; ----------------------------- regulatory-submission-status tracking -----------------------------

(deftest regulatory-transition-invalid-is-held
  (testing "skipping :counsel-review (draft straight to :submitted) -> HOLD -- no skipping states"
    (let [[db actor] (fresh)
          res (exec-op actor "r1" {:op :record-regulatory-submission-status :effect :propose :subject "reg-h1"
                                   :value {:market "SA" :product-type :surface-disinfectant
                                           :to-status :submitted}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:regulatory-transition-invalid} (-> (store/ledger db) last :basis)))
      (is (nil? (store/regulatory-submission db "reg-h1"))))))

(deftest regulatory-evidence-missing-is-held
  (testing "a VALID transition into a consequential status (:submitted) without the three human-evidence fields -> HOLD -- never defaulted or auto-generated"
    (let [[db actor] (fresh)
          _ (exec-op actor "r2a" {:op :record-regulatory-submission-status :effect :propose :subject "reg-h2"
                                  :value {:market "PK" :product-type :surface-disinfectant
                                          :to-status :counsel-review}}
                     coordinator)
          _ (approve! actor "r2a")
          res (exec-op actor "r2b" {:op :record-regulatory-submission-status :effect :propose :subject "reg-h2"
                                    :value {:to-status :submitted}}
                       coordinator)]
      (is (= :counsel-review (:status (store/regulatory-submission db "reg-h2"))) "the earlier valid transition DID land")
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:regulatory-evidence-missing} (-> (store/ledger db) last :basis)))
      (is (not (some #{:regulatory-transition-invalid} (-> (store/ledger db) last :basis)))
          "isolated to the evidence gate -- counsel-review -> submitted IS a valid transition")
      (is (= :counsel-review (:status (store/regulatory-submission db "reg-h2")))
          "the evidence-less transition never lands in the SSoT"))))

(deftest regulatory-submission-draft-to-counsel-review-always-needs-approval
  (testing "a non-consequential, clean transition still always escalates -- never in any phase's :auto set"
    (let [[db actor] (fresh)
          res (exec-op actor "r3" {:op :record-regulatory-submission-status :effect :propose :subject "reg-h3"
                                   :value {:market "EG" :product-type :antibacterial-soap
                                           :to-status :counsel-review}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "r3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :counsel-review (:status (store/regulatory-submission db "reg-h3"))))))))

(deftest regulatory-submission-submitted-with-full-evidence-still-needs-approval
  (testing "a consequential transition WITH complete human evidence clears the HARD gates but still always escalates (never auto-committed)"
    (let [[db actor] (fresh)
          _ (exec-op actor "r4a" {:op :record-regulatory-submission-status :effect :propose :subject "reg-h4"
                                  :value {:market "AE" :product-type :surface-disinfectant
                                          :to-status :counsel-review}}
                     coordinator)
          _ (approve! actor "r4a")
          res (exec-op actor "r4b" {:op :record-regulatory-submission-status :effect :propose :subject "reg-h4"
                                    :value {:to-status :submitted :filed-by "local regulatory counsel"
                                            :filing-date "2026-07-18" :agency-reference "GSO-REF-2026-0099"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "r4b")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :submitted (:status (store/regulatory-submission db "reg-h4"))))
        (is (= "local regulatory counsel" (:filed-by (store/regulatory-submission db "reg-h4"))))))))

(deftest market-approval-without-submission-warnings-flags-unbacked-approved-markets
  (testing "the non-breaking WARN-only consistency check: sample-data! backs only IN with an :approved regulatory-submission -- the other five :approved? markets are flagged, never HARD-blocked"
    (let [db (-> (store/mem-store) (store/sample-data!))
          verdict (governor/check {:op :log-production-batch :effect :propose :subject "batch-001"}
                                  {} {:effect :batch/upsert :value {} :confidence 1.0} db)]
      (is (false? (:hard? verdict)) "a warning never HARD-blocks an unrelated proposal")
      (is (= #{"AE" "BD" "ID" "PH" "SA"} (set (map :market (:warnings verdict)))))
      (is (not (contains? (set (map :market (:warnings verdict))) "IN"))
          "IN is backed by the seeded REG-IN-water-purification-drops :approved record"))))

(deftest market-approval-without-submission-warnings-empty-when-fully-backed
  (testing "once every :approved? market also has an :approved regulatory-submission on file, the warning list is empty"
    (let [db (-> (store/mem-store) (store/sample-data!))]
      (doseq [country ["IN" "BD" "ID" "PH" "SA" "AE"]]
        (store/commit-record! db {:effect :regulatory-submission/transition :path [(str "REG-" country "-extra")]
                                  :value {:market country :product-type :water-purification-drops
                                          :to-status :approved}}))
      (let [verdict (governor/check {:op :log-production-batch :effect :propose :subject "batch-001"}
                                    {} {:effect :batch/upsert :value {} :confidence 1.0} db)]
        (is (= [] (:warnings verdict)))))))

;; ----------------------------- sales quote/order (NO payment, NO fund movement) -----------------------------

(deftest sales-order-market-not-approved-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "s1" {:op :propose-sales-order :effect :propose :subject "ord-h1"
                                 :value {:buyer-ref "ngo-buyer-1"
                                         :sku "int.hygaccess.water-purification-drops"
                                         :quantity 10 :price-minor 350000 :market "PK"}}
                     coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:sales-order-market-not-approved} (-> (store/ledger db) last :basis)))
    (is (empty? (store/sales-order-history db)))))

(deftest sales-order-price-mismatch-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "s2" {:op :propose-sales-order :effect :propose :subject "ord-h2"
                                 :value {:buyer-ref "ngo-buyer-1"
                                         :sku "int.hygaccess.water-purification-drops"
                                         :quantity 10 :price-minor 1 :market "IN"}}
                     coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:sales-order-price-mismatch} (-> (store/ledger db) last :basis)))
    (is (empty? (store/sales-order-history db)))))

(deftest sales-order-quantity-invalid-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "s3" {:op :propose-sales-order :effect :propose :subject "ord-h3"
                                 :value {:buyer-ref "ngo-buyer-1"
                                         :sku "int.hygaccess.water-purification-drops"
                                         :quantity -5 :price-minor 350000 :market "IN"}}
                     coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:sales-order-quantity-invalid} (-> (store/ledger db) last :basis)))
    (is (empty? (store/sales-order-history db)))))

(deftest sales-order-clean-always-needs-approval
  (testing "market-approved + price-matches-registered-SKU + plausible quantity is never auto-eligible -- always escalates"
    (let [[db actor] (fresh)
          res (exec-op actor "s4" {:op :propose-sales-order :effect :propose :subject "ord-h4"
                                   :value {:buyer-ref "ngo-buyer-9"
                                           :sku "int.hygaccess.water-purification-drops"
                                           :quantity 500 :price-minor 350000 :market "IN"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "s4")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/sales-order-history db))))
        (is (= :pending (:fulfillment-status (store/sales-order db "ord-h4"))))))))

;; ----------------------------- fulfillment-status -----------------------------

(defn- clean-order! [actor tid subject]
  (exec-op actor tid {:op :propose-sales-order :effect :propose :subject subject
                      :value {:buyer-ref "ngo-buyer-9"
                              :sku "int.hygaccess.water-purification-drops"
                              :quantity 500 :price-minor 350000 :market "IN"}}
           coordinator)
  (approve! actor tid))

(deftest fulfillment-order-not-found-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "f1" {:op :update-fulfillment-status :effect :propose :subject "ord-does-not-exist"
                                 :value {:to-status :packed}}
                     coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:fulfillment-order-not-found} (-> (store/ledger db) last :basis)))))

(deftest fulfillment-transition-invalid-is-held
  (testing "skipping :packed/:shipped (pending straight to :delivered) -> HOLD -- order DOES exist, isolates the transition-table gate"
    (let [[db actor] (fresh)
          _ (clean-order! actor "f2a" "ord-h5")
          res (exec-op actor "f2b" {:op :update-fulfillment-status :effect :propose :subject "ord-h5"
                                    :value {:to-status :delivered}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:fulfillment-transition-invalid} (-> (store/ledger db) last :basis)))
      (is (not (some #{:fulfillment-order-not-found} (-> (store/ledger db) last :basis))))
      (is (= :pending (:fulfillment-status (store/sales-order db "ord-h5")))))))

(deftest fulfillment-shipment-not-on-file-is-held
  (testing "a valid :packed -> :shipped transition citing a shipment-id with no corresponding :coordinate-shipment record -> HOLD"
    (let [[db actor] (fresh)
          _ (clean-order! actor "f3a" "ord-h6")
          _ (exec-op actor "f3b" {:op :update-fulfillment-status :effect :propose :subject "ord-h6"
                                  :value {:to-status :packed}}
                     coordinator)
          _ (approve! actor "f3b")
          res (exec-op actor "f3c" {:op :update-fulfillment-status :effect :propose :subject "ord-h6"
                                    :value {:to-status :shipped :shipment-id "ship-does-not-exist"}}
                       coordinator)]
      (is (= :packed (:fulfillment-status (store/sales-order db "ord-h6"))) "the earlier valid transition DID land")
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:fulfillment-shipment-not-on-file} (-> (store/ledger db) last :basis)))
      (is (not (some #{:fulfillment-transition-invalid} (-> (store/ledger db) last :basis)))
          "isolated to the shipment-on-file gate -- packed -> shipped IS a valid transition")
      (is (= :packed (:fulfillment-status (store/sales-order db "ord-h6")))))))

(deftest fulfillment-clean-transition-to-packed-always-needs-approval
  (let [[db actor] (fresh)
        _ (clean-order! actor "f4a" "ord-h7")
        res (exec-op actor "f4b" {:op :update-fulfillment-status :effect :propose :subject "ord-h7"
                                  :value {:to-status :packed}}
                     coordinator)]
    (is (= :interrupted (:status res)))
    (let [r2 (approve! actor "f4b")]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= :packed (:fulfillment-status (store/sales-order db "ord-h7")))))))

(deftest fulfillment-shipped-with-existing-shipment-record-always-needs-approval
  (testing "ground truth, not self-report: :shipped only clears the gate once a REAL :coordinate-shipment record exists on file for the referenced shipment-id"
    (let [[db actor] (fresh)
          _ (exec-op actor "f5-ship" {:op :coordinate-shipment :effect :propose :subject "ship-fh1"
                                      :value {:batch-id "batch-001" :weight-kg 5.0
                                              :destination "ngo-distribution-hub-north"}}
                     coordinator)
          _ (approve! actor "f5-ship")
          _ (clean-order! actor "f5a" "ord-h8")
          _ (exec-op actor "f5b" {:op :update-fulfillment-status :effect :propose :subject "ord-h8"
                                  :value {:to-status :packed}}
                     coordinator)
          _ (approve! actor "f5b")
          res (exec-op actor "f5c" {:op :update-fulfillment-status :effect :propose :subject "ord-h8"
                                    :value {:to-status :shipped :shipment-id "ship-fh1"}}
                       coordinator)]
      (is (some? (store/shipment db "ship-fh1")) "the referenced shipment DOES exist on file")
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "f5c")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :shipped (:fulfillment-status (store/sales-order db "ord-h8"))))))))
