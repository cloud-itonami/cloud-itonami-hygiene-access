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
            [hygaccess.operation :as op]))

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
