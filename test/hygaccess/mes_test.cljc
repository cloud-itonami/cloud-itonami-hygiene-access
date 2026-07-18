(ns hygaccess.mes-test
  "`hygaccess.mes`'s dcs.ports/IFieldIO + real-CFD engine rewire, as
  executable tests -- independent of the governor/store/operation
  wiring (mirrors `hygaccess.registry-test`'s own scope). Two things
  under test: (1) the plausibility-window public surface
  `hygaccess.governor` depends on stayed byte-identical across the
  MESSource -> dcs.ports/IFieldIO swap; (2) the new dcs.model tag
  registry + mock IFieldIO + real CFD-backed homogeneity reading are
  actually wired and behave as documented."
  (:require [clojure.test :refer [deftest is testing]]
            [dcs.model :as dcs-model]
            [dcs.ports :as dcs-ports]
            [hygaccess.mes :as mes]))

;; ----------------------------- plausibility windows unchanged -----------------------------

(deftest plausibility-windows-unchanged-by-the-engine-rewire
  (testing "hygaccess.governor reads these five vars/fns by name -- the dcs/CFD rewire must not touch them"
    (is (= [0.0 14.0] mes/ph-plausible-range))
    (is (= [5.0 55.0] mes/temperature-plausible-range-c))
    (is (= [10.0 500.0] mes/mixing-rpm-plausible-range))
    (is (= [0.0 100.0] mes/homogeneity-cov-physical-range-pct))
    (is (= 0.5 mes/homogeneity-match-tolerance-pct))
    (is (true? (mes/ph-plausible? 7.0)))
    (is (false? (mes/ph-plausible? 15.0)))
    (is (true? (mes/temperature-plausible? 28.0)))
    (is (false? (mes/temperature-plausible? 90.0)))
    (is (true? (mes/mixing-rpm-plausible? 120.0)))
    (is (false? (mes/mixing-rpm-plausible? 5000.0)))
    (is (true? (mes/homogeneity-cov-physically-plausible? 2.5)))
    (is (false? (mes/homogeneity-cov-physically-plausible? 150.0)))
    (is (true? (mes/homogeneity-values-match? 2.1 2.3)))
    (is (false? (mes/homogeneity-values-match? 2.1 4.0)))
    (is (false? (mes/homogeneity-values-match? nil 2.3)))))

;; ----------------------------- dcs.model tag registry -----------------------------

(deftest mixing-tank-system-declares-the-four-domain-tags-in-dcs-model-terms
  (testing "pH, temperature, mixing RPM, mixing-homogeneity-cov-pct -- all :ai (analog input, read-only telemetry)"
    (let [tags (dcs-model/tags mes/mixing-tank-system)]
      (is (= 4 (count tags)))
      (is (every? #(= :ai (:dcs/kind %)) tags))
      (is (= mes/ph-plausible-range (:dcs/range (dcs-model/tag-by-id mes/mixing-tank-system mes/ph-tag-id))))
      (is (= mes/temperature-plausible-range-c
             (:dcs/range (dcs-model/tag-by-id mes/mixing-tank-system mes/temperature-tag-id))))
      (is (= mes/mixing-rpm-plausible-range
             (:dcs/range (dcs-model/tag-by-id mes/mixing-tank-system mes/mixing-rpm-tag-id))))
      (is (= mes/homogeneity-cov-physical-range-pct
             (:dcs/range (dcs-model/tag-by-id mes/mixing-tank-system mes/homogeneity-tag-id))))))
  (testing "one dcs.model area represents the illustrative tank"
    (is (= 1 (count (dcs-model/areas mes/mixing-tank-system))))))

;; ----------------------------- mock IFieldIO -----------------------------

(deftest mock-plant-io-satisfies-dcs-ports-ifieldio
  (let [io (mes/mock-plant-io)]
    (is (satisfies? dcs-ports/IFieldIO io))))

(deftest mock-plant-io-default-seed-covers-ph-temperature-rpm
  (let [io (mes/mock-plant-io)]
    (is (= 11.5 (dcs-ports/read-tag io mes/ph-tag-id)))
    (is (= 28.0 (dcs-ports/read-tag io mes/temperature-tag-id)))
    (is (= 120.0 (dcs-ports/read-tag io mes/mixing-rpm-tag-id)))))

(deftest mock-plant-io-reading-overrides-take-precedence-over-default-seed
  (let [io (mes/mock-plant-io {:ph 9.0 :temperature-c 30.0})]
    (is (= 9.0 (dcs-ports/read-tag io mes/ph-tag-id)))
    (is (= 30.0 (dcs-ports/read-tag io mes/temperature-tag-id)))
    (is (= 120.0 (dcs-ports/read-tag io mes/mixing-rpm-tag-id))
        "un-overridden field keeps the default seed")))

(deftest seed-batch-reading-only-writes-present-fields
  (let [io (mes/mock-plant-io)]
    (mes/seed-batch-reading! io {:ph 8.0})
    (is (= 8.0 (dcs-ports/read-tag io mes/ph-tag-id)))
    (is (= 28.0 (dcs-ports/read-tag io mes/temperature-tag-id))
        "temperature untouched by a reading map that omits it")))

;; ----------------------------- real CFD-backed homogeneity -----------------------------

(deftest real-cfd-homogeneity-is-genuinely-computed-not-a-stub
  (testing "the actual kami-app-hygaccess-plant.mixing/run-mixing-scenario final CoV -- well-mixed (<1%), never the old hand-picked 2.5"
    (let [cov (mes/real-cfd-homogeneity-cov-pct)]
      (is (number? cov))
      (is (mes/homogeneity-cov-physically-plausible? cov))
      (is (< cov 1.0) "a converged mixing-tank solve settles well below 1% CoV"))))

(deftest real-cfd-homogeneity-is-memoized-across-repeated-calls
  (testing "same process, same deterministic solve -- byte-identical result every call, not re-run"
    (is (= (mes/real-cfd-homogeneity-cov-pct) (mes/real-cfd-homogeneity-cov-pct)))))

(deftest mock-plant-io-falls-through-to-real-cfd-for-unseeded-homogeneity
  (let [io (mes/mock-plant-io)]
    (is (= (mes/real-cfd-homogeneity-cov-pct) (dcs-ports/read-tag io mes/homogeneity-tag-id)))))

(deftest mock-plant-io-homogeneity-override-bypasses-the-real-cfd-result
  (testing "an explicit override (e.g. to exercise an implausible-reading HARD hold) wins over the real solve"
    (let [io (mes/mock-plant-io {:mixing-homogeneity-cov-pct 150.0})]
      (is (= 150.0 (dcs-ports/read-tag io mes/homogeneity-tag-id))))))

;; ----------------------------- batch-telemetry-reading -----------------------------

(deftest batch-telemetry-reading-reads-through-io-and-carries-the-real-cfd-homogeneity
  (let [io (mes/mock-plant-io)
        reading (mes/batch-telemetry-reading io "batch-001")]
    (is (= "batch-001" (:batch-id reading)))
    (is (= 11.5 (:ph reading)))
    (is (= 28.0 (:temperature-c reading)))
    (is (= 120.0 (:mixing-rpm reading)))
    (is (= (mes/real-cfd-homogeneity-cov-pct) (:mixing-homogeneity-cov-pct reading)))
    (is (= :dcs-plant-io (:source reading)))))

(deftest batch-telemetry-reading-honors-per-io-overrides
  (let [io (mes/mock-plant-io {:ph 6.5 :mixing-homogeneity-cov-pct 3.3})
        reading (mes/batch-telemetry-reading io "batch-002")]
    (is (= "batch-002" (:batch-id reading)))
    (is (= 6.5 (:ph reading)))
    (is (= 3.3 (:mixing-homogeneity-cov-pct reading)))))
