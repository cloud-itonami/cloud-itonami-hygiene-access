(ns hygaccess.registry-test
  "Pure-function domain-logic tests for `hygaccess.registry` --
  efficacy-window (G21 analog), no-toxic-co-formulation (G22 analog),
  packaging-format, market-entry-approval, affordability-price-
  ceiling, marketing-claim-substantiation, and distribution-channel-
  partner-licensing validation, independent of the governor/store/
  operation wiring."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn])
            [hygaccess.registry :as registry]))

(deftest known-actives-closed-set
  (is (= #{:sodium-hypochlorite :isopropylmethylphenol} registry/known-actives)))

(deftest valid-product-types-closed-set
  (is (= #{:water-purification-drops :surface-disinfectant :antibacterial-soap}
         registry/valid-product-types)))

(deftest efficacy-window-for-known-combinations
  (is (= [0.5 1.5] (registry/efficacy-window-for :sodium-hypochlorite :water-purification-drops)))
  (is (= [0.05 0.5] (registry/efficacy-window-for :sodium-hypochlorite :surface-disinfectant)))
  (is (= [0.1 0.3] (registry/efficacy-window-for :isopropylmethylphenol :antibacterial-soap)))
  (is (nil? (registry/efficacy-window-for :isopropylmethylphenol :water-purification-drops))
      "IPMP is not authorized for water-purification-drops")
  (is (nil? (registry/efficacy-window-for :sodium-hypochlorite :antibacterial-soap))
      "sodium-hypochlorite is not authorized for antibacterial-soap")
  (is (nil? (registry/efficacy-window-for :unobtainium :water-purification-drops))))

(deftest active-valid-for-product-type-matches-closed-table
  (is (true? (registry/active-valid-for-product-type? :sodium-hypochlorite :water-purification-drops)))
  (is (true? (registry/active-valid-for-product-type? :sodium-hypochlorite :surface-disinfectant)))
  (is (true? (registry/active-valid-for-product-type? :isopropylmethylphenol :antibacterial-soap)))
  (is (false? (registry/active-valid-for-product-type? :isopropylmethylphenol :water-purification-drops)))
  (is (false? (registry/active-valid-for-product-type? :sodium-hypochlorite :antibacterial-soap)))
  (is (false? (registry/active-valid-for-product-type? :unobtainium :water-purification-drops))))

(deftest concentration-within-efficacy-window-boundaries-inclusive
  (testing "boundary values are within the window (inclusive)"
    (is (true? (registry/concentration-within-efficacy-window? :sodium-hypochlorite :water-purification-drops 0.5)))
    (is (true? (registry/concentration-within-efficacy-window? :sodium-hypochlorite :water-purification-drops 1.5)))
    (is (true? (registry/concentration-within-efficacy-window? :sodium-hypochlorite :water-purification-drops 1.0))))
  (testing "'濃ければ強い is FALSE' -- too concentrated is rejected exactly like too dilute"
    (is (false? (registry/concentration-within-efficacy-window? :sodium-hypochlorite :water-purification-drops 0.49)))
    (is (false? (registry/concentration-within-efficacy-window? :sodium-hypochlorite :water-purification-drops 5.0)))))

(deftest no-toxic-co-formulation-blocks-sodium-hypochlorite-plus-acid-or-ammonia
  (is (true? (registry/no-toxic-co-formulation? :sodium-hypochlorite #{})))
  (is (true? (registry/no-toxic-co-formulation? :isopropylmethylphenol #{:hydrochloric-acid}))
      "the hazard is specific to sodium-hypochlorite -- IPMP + acid is not this hazard")
  (is (false? (registry/no-toxic-co-formulation? :sodium-hypochlorite #{:hydrochloric-acid})))
  (is (false? (registry/no-toxic-co-formulation? :sodium-hypochlorite #{:ammonia})))
  (is (false? (registry/no-toxic-co-formulation? :sodium-hypochlorite #{:citric-acid :some-benign-thing}))))

(deftest toxic-co-formulation-is-the-negation
  (is (false? (registry/toxic-co-formulation? :sodium-hypochlorite #{})))
  (is (true? (registry/toxic-co-formulation? :sodium-hypochlorite #{:ammonium-chloride}))))

(deftest packaging-format-valid-closed-set
  (doseq [fmt registry/valid-packaging-formats]
    (is (true? (registry/packaging-format-valid? fmt))))
  (is (false? (registry/packaging-format-valid? :bulk-drum-200l)))
  (is (false? (registry/packaging-format-valid? nil))))

(deftest market-country-known-closed-set
  (is (true? (registry/market-country-known? "IN")))
  (is (false? (registry/market-country-known? "US"))))

(deftest market-approved-is-ground-truth-flag
  (is (true? (registry/market-approved? {:country "IN" :approved? true})))
  (is (false? (registry/market-approved? {:country "PK" :approved? false})))
  (is (false? (registry/market-approved? nil))))

(deftest price-above-ceiling-per-product-type
  (is (false? (registry/price-above-ceiling? :water-purification-drops 350000)))
  (is (false? (registry/price-above-ceiling? :water-purification-drops 500000))
      "at the ceiling is not above it")
  (is (true? (registry/price-above-ceiling? :water-purification-drops 500001)))
  (is (true? (registry/price-above-ceiling? :antibacterial-soap 500000))))

(deftest price-near-ceiling-80-percent-threshold
  (is (false? (registry/price-near-ceiling? :water-purification-drops 350000)))
  (is (true? (registry/price-near-ceiling? :water-purification-drops 400000)))
  (is (true? (registry/price-near-ceiling? :water-purification-drops 500000))))

(deftest claim-substantiated-closed-per-product-type-set
  (is (true? (registry/claim-substantiated?
              :water-purification-drops
              "reduces waterborne bacterial and viral pathogens when used per labeled dosing (WHO Safe Water System-style point-of-use treatment)")))
  (is (true? (registry/claim-substantiated? :surface-disinfectant "disinfects hard non-porous surfaces")))
  (is (true? (registry/claim-substantiated?
              :antibacterial-soap
              "reduces transient bacteria on hands with proper handwashing technique")))
  (is (false? (registry/claim-substantiated? :water-purification-drops "cures cholera and typhoid")))
  (is (false? (registry/claim-substantiated? :water-purification-drops "disinfects hard non-porous surfaces"))
      "a claim substantiated for a DIFFERENT product type does not carry over"))

(deftest channel-partner-ready-requires-licensed-and-serving-channel
  (is (true? (registry/channel-partner-ready?
              {:id "p1" :licensed? true :channels #{:ngo-who-program}} :ngo-who-program)))
  (is (false? (registry/channel-partner-ready?
               {:id "p2" :licensed? false :channels #{:ngo-who-program}} :ngo-who-program))
      "unlicensed")
  (is (false? (registry/channel-partner-ready?
               {:id "p3" :licensed? true :channels #{:institutional-bulk}} :ngo-who-program))
      "licensed but does not serve this channel")
  (is (false? (registry/channel-partner-ready? nil :ngo-who-program))))

(deftest shipment-weight-exceeded-recompute
  (is (false? (registry/shipment-weight-exceeded? {:weight-kg 500.0 :shipped-weight-kg 100.0} 50.0)))
  (is (true? (registry/shipment-weight-exceeded? {:weight-kg 800.0 :shipped-weight-kg 750.0} 100.0))))

(deftest off-spec-rate-valid-range
  (is (true? (registry/off-spec-rate-valid? 0.0)))
  (is (true? (registry/off-spec-rate-valid? 100.0)))
  (is (false? (registry/off-spec-rate-valid? -1.0)))
  (is (false? (registry/off-spec-rate-valid? 150.0)))
  (is (false? (registry/off-spec-rate-valid? nil))))

(deftest register-market-entry-requires-entry-id
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (registry/register-market-entry "" 0))))

(deftest register-marketing-claim-produces-zero-padded-number
  (let [{:strs [claim_number]} (registry/register-marketing-claim "clm-1" 0)]
    (is (= "CLM-000000" claim_number))))

;; ----------------------------- GMP raw-material-lot checks -----------------------------

(deftest raw-material-assay-plausibility-window-known-actives
  (is (= [10.0 15.0] (registry/raw-material-assay-plausibility-window-for :sodium-hypochlorite)))
  (is (= [98.0 100.5] (registry/raw-material-assay-plausibility-window-for :isopropylmethylphenol)))
  (is (nil? (registry/raw-material-assay-plausibility-window-for :unobtainium))))

(deftest raw-material-assay-plausible-boundaries-inclusive
  (testing "boundary values are within the plausibility window (inclusive)"
    (is (true? (registry/raw-material-assay-plausible? :sodium-hypochlorite 10.0)))
    (is (true? (registry/raw-material-assay-plausible? :sodium-hypochlorite 15.0)))
    (is (true? (registry/raw-material-assay-plausible? :sodium-hypochlorite 12.5)))
    (is (true? (registry/raw-material-assay-plausible? :isopropylmethylphenol 98.0)))
    (is (true? (registry/raw-material-assay-plausible? :isopropylmethylphenol 100.5))))
  (testing "far outside the window is implausible/fabricated data"
    (is (false? (registry/raw-material-assay-plausible? :sodium-hypochlorite 9.9)))
    (is (false? (registry/raw-material-assay-plausible? :sodium-hypochlorite 40.0)))
    (is (false? (registry/raw-material-assay-plausible? :isopropylmethylphenol 50.0)))
    (is (false? (registry/raw-material-assay-plausible? :sodium-hypochlorite nil)))
    (is (false? (registry/raw-material-assay-plausible? :unobtainium 12.0)))))

(deftest raw-material-lot-ready-requires-verified-and-registered
  (is (true? (registry/raw-material-lot-ready? {:verified? true :registered? true})))
  (is (false? (registry/raw-material-lot-ready? {:verified? true :registered? false})))
  (is (false? (registry/raw-material-lot-ready? {:verified? false :registered? true})))
  (is (false? (registry/raw-material-lot-ready? nil))))

(deftest raw-material-lot-coa-received-is-ground-truth-flag
  (is (true? (registry/raw-material-lot-coa-received? {:coa-received? true})))
  (is (false? (registry/raw-material-lot-coa-received? {:coa-received? false})))
  (is (false? (registry/raw-material-lot-coa-received? nil))))

(deftest raw-material-lot-release-eligible-requires-all-four-conditions
  (let [clean {:active :sodium-hypochlorite :verified? true :registered? true
               :coa-received? true :coa-assay-pct 12.5}]
    (is (true? (registry/raw-material-lot-release-eligible? clean)))
    (is (false? (registry/raw-material-lot-release-eligible? (assoc clean :verified? false)))
        "not verified")
    (is (false? (registry/raw-material-lot-release-eligible? (assoc clean :coa-received? false)))
        "CoA not received")
    (is (false? (registry/raw-material-lot-release-eligible? (assoc clean :coa-assay-pct 40.0)))
        "implausible assay")
    (is (false? (registry/raw-material-lot-release-eligible? nil)))))

;; ----------------------------- in-process QC (IPQC) mixing-homogeneity checks -----------------------------

(deftest homogeneity-within-threshold-5-percent-boundary-inclusive
  (is (true? (registry/homogeneity-within-threshold? 0.0)))
  (is (true? (registry/homogeneity-within-threshold? 2.1)))
  (is (true? (registry/homogeneity-within-threshold? 5.0))
      "exactly at the threshold is within it")
  (is (false? (registry/homogeneity-within-threshold? 5.01)))
  (is (false? (registry/homogeneity-within-threshold? 9.0)))
  (is (false? (registry/homogeneity-within-threshold? -1.0)))
  (is (false? (registry/homogeneity-within-threshold? nil))))

;; ----------------------------- Certificate of Analysis (CoA) / batch-release checks -----------------------------

(deftest coa-pass-is-ground-truth-flag
  (is (true? (registry/coa-pass? {:coa {:coa-pass? true}})))
  (is (false? (registry/coa-pass? {:coa {:coa-pass? false}})))
  (is (false? (registry/coa-pass? {:coa nil})))
  (is (false? (registry/coa-pass? nil))))

(deftest batch-release-qc-complete-requires-both-coa-pass-and-homogeneity
  (let [clean {:coa {:coa-pass? true} :ipqc {:mixing-homogeneity-cov-pct 2.1}}]
    (is (true? (registry/batch-release-qc-complete? clean)))
    (is (false? (registry/batch-release-qc-complete? (assoc clean :coa {:coa-pass? false})))
        "CoA not passing")
    (is (false? (registry/batch-release-qc-complete?
                 (assoc clean :ipqc {:mixing-homogeneity-cov-pct 9.0})))
        "homogeneity CoV above threshold")
    (is (false? (registry/batch-release-qc-complete? {})))))

;; ----------------------------- commercial catalog (SKU price ground truth) -----------------------------

(deftest sku-catalog-known-skus
  (is (true? (registry/sku-known? "int.hygaccess.water-purification-drops")))
  (is (false? (registry/sku-known? "int.hygaccess.does-not-exist"))))

(deftest sku-price-for-matches-catalog
  (is (= 350000 (registry/sku-price-for "int.hygaccess.water-purification-drops")))
  (is (= 700000 (registry/sku-price-for "int.hygaccess.surface-disinfectant")))
  (is (= 150000 (registry/sku-price-for "int.hygaccess.antibacterial-soap-bar")))
  (is (= 250000 (registry/sku-price-for "int.hygaccess.antibacterial-liquid-soap")))
  (is (nil? (registry/sku-price-for "int.hygaccess.does-not-exist"))))

(deftest sku-product-type-for-matches-catalog
  (is (= :water-purification-drops (registry/sku-product-type-for "int.hygaccess.water-purification-drops")))
  (is (= :antibacterial-soap (registry/sku-product-type-for "int.hygaccess.antibacterial-soap-bar"))))

(deftest sku-price-mismatch-ground-truth
  (is (false? (registry/sku-price-mismatch? "int.hygaccess.water-purification-drops" 350000)))
  (is (true? (registry/sku-price-mismatch? "int.hygaccess.water-purification-drops" 1)))
  (is (true? (registry/sku-price-mismatch? "int.hygaccess.does-not-exist" 350000))
      "an unknown SKU has no ground-truth price to confirm against -- treated as a mismatch"))

#?(:clj
   (deftest sku-catalog-stays-in-sync-with-products-edn
     (testing "hygaccess.registry/sku-catalog mirrors products.edn's own four SKUs -- keep both in sync on any catalog change"
       (let [products (edn/read-string (slurp "products.edn"))]
         (is (= 4 (count products)))
         (doseq [p products
                 :let [sku (:product/id p)
                       price (:product/price-minor p)
                       product-type (:hygaccess.product/product-type p)]]
           (is (= price (registry/sku-price-for sku))
               (str sku " price mismatch between products.edn and sku-catalog"))
           (is (= product-type (registry/sku-product-type-for sku))
               (str sku " product-type mismatch between products.edn and sku-catalog")))))))

(deftest order-quantity-valid-range
  (is (true? (registry/order-quantity-valid? 1)))
  (is (true? (registry/order-quantity-valid? 100000)))
  (is (false? (registry/order-quantity-valid? 0)))
  (is (false? (registry/order-quantity-valid? -5)))
  (is (false? (registry/order-quantity-valid? 100001)))
  (is (false? (registry/order-quantity-valid? nil))))

;; ----------------------------- fulfillment-status state machine -----------------------------

(deftest fulfillment-transition-valid-happy-chain
  (is (true? (registry/fulfillment-transition-valid? :pending :packed)))
  (is (true? (registry/fulfillment-transition-valid? :packed :shipped)))
  (is (true? (registry/fulfillment-transition-valid? :shipped :delivered))))

(deftest fulfillment-transition-cancelled-only-from-pre-shipped-states
  (is (true? (registry/fulfillment-transition-valid? :pending :cancelled)))
  (is (true? (registry/fulfillment-transition-valid? :packed :cancelled)))
  (is (false? (registry/fulfillment-transition-valid? :shipped :cancelled))
      "cannot cancel a same-op-cancellation once already shipped")
  (is (false? (registry/fulfillment-transition-valid? :delivered :cancelled))))

(deftest fulfillment-transition-no-skipping-or-terminal-exit
  (is (false? (registry/fulfillment-transition-valid? :pending :shipped)) "no skipping :packed")
  (is (false? (registry/fulfillment-transition-valid? :delivered :pending)))
  (is (false? (registry/fulfillment-transition-valid? :cancelled :pending))))

;; ----------------------------- MES/CFD telemetry-reading + sales-order draft construction -----------------------------

(deftest register-mes-reading-requires-reading-id
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (registry/register-mes-reading "" 0))))

(deftest register-mes-reading-produces-zero-padded-number
  (let [{:strs [reading_number]} (registry/register-mes-reading "mes-1" 0)]
    (is (= "MES-000000" reading_number))))

(deftest register-sales-order-requires-order-id
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (registry/register-sales-order "" 0))))

(deftest register-sales-order-produces-zero-padded-number
  (let [{:strs [order_number]} (registry/register-sales-order "ord-1" 0)]
    (is (= "ORD-000000" order_number))))
