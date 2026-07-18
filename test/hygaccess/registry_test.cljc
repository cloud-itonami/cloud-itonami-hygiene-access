(ns hygaccess.registry-test
  "Pure-function domain-logic tests for `hygaccess.registry` --
  efficacy-window (G21 analog), no-toxic-co-formulation (G22 analog),
  packaging-format, market-entry-approval, affordability-price-
  ceiling, marketing-claim-substantiation, and distribution-channel-
  partner-licensing validation, independent of the governor/store/
  operation wiring."
  (:require [clojure.test :refer [deftest is testing]]
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
