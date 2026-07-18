(ns hygaccess.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `hygaccess.store` ns docstring for why a second (Datomic-backed)
  backend is out of scope for this build."
  (:require [clojure.test :refer [deftest is testing]]
            [hygaccess.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/batch s "batch-001"))))
    (is (true? (:registered? (store/batch s "batch-001"))))
    (is (true? (:verified? (store/batch s "batch-002"))))
    (is (true? (:registered? (store/batch s "batch-002"))))
    (is (false? (:verified? (store/batch s "batch-003"))))
    (is (false? (:registered? (store/batch s "batch-003"))))
    (is (= ["batch-001" "batch-002" "batch-003"] (mapv :id (store/all-batches s))))
    (is (true? (:verified? (store/equipment-unit s "line-001"))))
    (is (true? (:registered? (store/equipment-unit s "line-001"))))
    (is (false? (:verified? (store/equipment-unit s "line-002"))))
    (is (false? (:registered? (store/equipment-unit s "line-002"))))
    (is (= ["line-001" "line-002"] (mapv :id (store/all-equipment s))))
    (is (true? (:approved? (store/market-approval s "IN"))))
    (is (false? (:approved? (store/market-approval s "PK"))))
    (is (false? (:approved? (store/market-approval s "EG"))))
    (is (true? (:licensed? (store/channel-partner s "partner-ngo-1"))))
    (is (false? (:licensed? (store/channel-partner s "partner-retail-2"))))
    (is (= "RM-LOT-NAOCL-001" (:raw-material-lot-number (store/batch s "batch-001"))))
    (is (= 2.1 (get-in (store/batch s "batch-001") [:ipqc :mixing-homogeneity-cov-pct])))
    (is (true? (get-in (store/batch s "batch-001") [:coa :coa-pass?])))
    (is (nil? (:raw-material-lot-number (store/batch s "batch-003")))
        "batch-003 is pre-QC -- no raw-material lot/IPQC/CoA recorded yet")
    (is (true? (:verified? (store/raw-material-lot s "RM-LOT-NAOCL-001"))))
    (is (true? (:registered? (store/raw-material-lot s "RM-LOT-NAOCL-001"))))
    (is (true? (:coa-received? (store/raw-material-lot s "RM-LOT-NAOCL-001"))))
    (is (false? (:verified? (store/raw-material-lot s "RM-LOT-NAOCL-003"))))
    (is (false? (:coa-received? (store/raw-material-lot s "RM-LOT-NAOCL-004"))))
    (is (= 40.0 (:coa-assay-pct (store/raw-material-lot s "RM-LOT-NAOCL-005"))))
    (is (= 6 (count (store/all-raw-material-lots s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/maintenance-history s)))
    (is (= [] (store/shipment-history s)))
    (is (= [] (store/packaging-design-history s)))
    (is (= [] (store/market-entry-history s)))
    (is (= [] (store/marketing-claim-history s)))
    (is (= [] (store/safety-concerns s)))
    (is (zero? (store/next-maintenance-sequence s)))
    (is (zero? (store/next-shipment-sequence s)))
    (is (zero? (store/next-packaging-design-sequence s)))
    (is (zero? (store/next-market-entry-sequence s)))
    (is (zero? (store/next-marketing-claim-sequence s)))
    (is (false? (store/maintenance-already-scheduled? s "mnt-1")))
    (is (nil? (store/maintenance s "mnt-1")))))

(deftest fresh-store-has-no-batches-or-equipment
  (let [s (store/mem-store)]
    (is (= [] (store/all-batches s)))
    (is (nil? (store/batch s "batch-001")))
    (is (= [] (store/all-equipment s)))
    (is (nil? (store/equipment-unit s "line-001")))
    (is (nil? (store/market-approval s "IN")))
    (is (nil? (store/channel-partner s "partner-ngo-1")))
    (is (nil? (store/raw-material-lot s "RM-LOT-NAOCL-001")))
    (is (= [] (store/all-raw-material-lots s)))))

(deftest batch-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :batch/upsert :path ["batch-001"]
                             :value {:off-spec-rate-pct 0.4}})
    (is (= 0.4 (:off-spec-rate-pct (store/batch s "batch-001"))))
    (is (true? (:verified? (store/batch s "batch-001"))) "unrelated field preserved")
    (is (= :sodium-hypochlorite (:active (store/batch s "batch-001"))) "unrelated field preserved")))

(deftest maintenance-schedule-commits-and-advances-sequence
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly"
    (let [s (seeded)]
      (store/commit-record! s {:effect :maintenance/schedule :path ["mnt-1"]
                               :value {:equipment-id "line-001" :maintenance-type :nozzle-inspection
                                       :scheduled-date "2026-08-01"}})
      (is (= "MNT-000000" (get (first (store/maintenance-history s)) "record_id")))
      (is (= "maintenance-schedule-draft" (get (first (store/maintenance-history s)) "kind")))
      (is (true? (:scheduled? (store/maintenance s "mnt-1"))))
      (is (= "line-001" (:equipment-id (store/maintenance s "mnt-1"))))
      (is (= 1 (count (store/maintenance-history s))))
      (is (= 1 (store/next-maintenance-sequence s)))
      (is (true? (store/maintenance-already-scheduled? s "mnt-1")))
      (is (= "MNT-000000" (:maintenance-number (store/maintenance s "mnt-1")))))))

(deftest safety-concern-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-1"]
                             :value {:equipment-id "line-001" :severity :moderate}})
    (is (= 1 (count (store/safety-concerns s))))
    (is (= :moderate (:severity (first (store/safety-concerns s)))))
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-2"]
                             :value {:equipment-id "line-002" :severity :high}})
    (is (= 2 (count (store/safety-concerns s))) "append-only")))

(deftest shipment-propose-commits-and-advances-sequence-and-batch-weight
  (let [s (seeded)]
    (store/commit-record! s {:effect :shipment/propose :path ["ship-1"]
                             :value {:batch-id "batch-001" :weight-kg 50.0
                                     :destination "ngo-distribution-hub-north"}})
    (is (= "SHP-000000" (get (first (store/shipment-history s)) "record_id")))
    (is (= "shipment-coordination-draft" (get (first (store/shipment-history s)) "kind")))
    (is (= 1 (count (store/shipment-history s))))
    (is (= 1 (store/next-shipment-sequence s)))
    (is (= "SHP-000000" (:shipment-number (store/shipment s "ship-1"))))
    (is (= 150.0 (:shipped-weight-kg (store/batch s "batch-001")))
        "100.0 seeded + 50.0 committed")))

(deftest packaging-design-propose-commits-and-advances-sequence
  (let [s (seeded)]
    (store/commit-record! s {:effect :packaging-design/propose :path ["pkg-1"]
                             :value {:product-type :water-purification-drops
                                     :format :small-bottle-50ml :net-content "50ml"}})
    (is (= "PKG-000000" (get (first (store/packaging-design-history s)) "record_id")))
    (is (= "packaging-design-draft" (get (first (store/packaging-design-history s)) "kind")))
    (is (= 1 (count (store/packaging-design-history s))))
    (is (= 1 (store/next-packaging-design-sequence s)))
    (is (= :small-bottle-50ml (:format (store/packaging-design s "pkg-1"))))))

(deftest market-entry-propose-commits-and-advances-sequence
  (let [s (seeded)]
    (store/commit-record! s {:effect :market-entry/propose :path ["mkt-1"]
                             :value {:product-type :water-purification-drops :country "IN"
                                     :price-minor 350000 :channel :ngo-who-program
                                     :channel-partner-id "partner-ngo-1"}})
    (is (= "MKT-000000" (get (first (store/market-entry-history s)) "record_id")))
    (is (= "market-entry-draft" (get (first (store/market-entry-history s)) "kind")))
    (is (= 1 (count (store/market-entry-history s))))
    (is (= 1 (store/next-market-entry-sequence s)))
    (is (= "IN" (:country (store/market-entry s "mkt-1"))))))

(deftest marketing-claim-propose-commits-and-advances-sequence
  (let [s (seeded)]
    (store/commit-record! s {:effect :marketing-claim/propose :path ["clm-1"]
                             :value {:product-type :water-purification-drops
                                     :claim "disinfects hard non-porous surfaces"}})
    (is (= "CLM-000000" (get (first (store/marketing-claim-history s)) "record_id")))
    (is (= "marketing-claim-draft" (get (first (store/marketing-claim-history s)) "kind")))
    (is (= 1 (count (store/marketing-claim-history s))))
    (is (= 1 (store/next-marketing-claim-sequence s)))))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
