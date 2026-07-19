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
            [hygaccess.mes :as mes]
            [hygaccess.registry :as registry]
            [kami-app-hygaccess-plant.process :as process]))

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

;; ----------------------------- real closed-loop-CFD-backed homogeneity -----------------------------
;;
;; This build's engine rewire (2026-07-19): `real-cfd-homogeneity-cov-pct`
;; is no longer a single monolithic `run-mixing-scenario` call's own
;; final tick -- it is `run-homogeneity-control-loop`'s FINAL trace
;; reading, and that loop TERMINATES as soon as the CoV first crosses
;; under `hygaccess.registry/homogeneity-cov-threshold-pct` (5.0%), a
;; real closed-loop controller's own correct behaviour (a real plant
;; stops driving harder once it's in spec, it doesn't keep agitating
;; for no reason). The final CoV therefore lands SOMEWHERE under 5.0%,
;; not reliably under 1.0% the way the old monolithic 40-tick-to-near-
;; zero run did -- `< cov 1.0` was a fact about THAT specific mechanism,
;; not a requirement of this actor's own domain; it is replaced below
;; with the actual domain requirement (in spec, genuinely computed,
;; never the old hand-picked 2.5).

(deftest real-cfd-homogeneity-is-genuinely-computed-not-a-stub
  (testing "run-homogeneity-control-loop's own real final reading -- converged in-spec, never the old hand-picked 2.5"
    (let [cov (mes/real-cfd-homogeneity-cov-pct)]
      (is (number? cov))
      (is (mes/homogeneity-cov-physically-plausible? cov))
      (is (registry/homogeneity-within-threshold? cov)
          "the default (no test overrides) closed-loop run converges to in-spec CoV before the iteration bound")
      (is (not= 2.5 cov) "never the old hand-picked stub value"))))

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

(deftest batch-telemetry-reading-carries-the-control-loop-alarm-visibility-fact
  (testing "the new field is present and boolean, sourced from the real trace, not the io tag map"
    (let [io (mes/mock-plant-io)
          reading (mes/batch-telemetry-reading io "batch-001")]
      (is (contains? reading :control-loop-alarm-triggered?))
      (is (boolean? (:control-loop-alarm-triggered? reading)))
      (is (= (mes/control-loop-alarm-triggered?) (:control-loop-alarm-triggered? reading)))
      (testing "an io homogeneity override does NOT change this field -- it isn't a plant tag"
        (let [io2 (mes/mock-plant-io {:mixing-homogeneity-cov-pct 150.0})
              reading2 (mes/batch-telemetry-reading io2 "batch-001")]
          (is (= (mes/control-loop-alarm-triggered?) (:control-loop-alarm-triggered? reading2))))))))

;; ----------------------------- real closed control loop: PID + ISA-18.2 alarm + real CFD -----------------------------
;;
;; `run-homogeneity-control-loop` is exercised directly here (below the
;; governor/actor level) on a small mesh for speed -- the default
;; (24x24-mesh) memoized production path is exercised indirectly by
;; `real-cfd-homogeneity-cov-pct` above; `hygaccess.governor-contract-
;; test` exercises the SAME mechanism end to end through the actual
;; actor graph (visibility/escalation on a forced alarm).

(def ^:private small-tank
  (assoc (process/default-tank) :tank/mesh-resolution [10 10]))

(deftest clean-control-loop-run-converges-under-pid-alone-no-alarm
  (testing "a clean run (no forced-implausible fixtures): PID drives CoV under threshold, purely computed, never an alarm override"
    (let [r (mes/run-homogeneity-control-loop {:tank small-tank})]
      (is (true? (:converged? r)))
      (is (false? (:alarm-triggered? r)))
      (is (registry/homogeneity-within-threshold? (:final-cov r)))
      (is (<= (:iterations-run r) (inc mes/control-loop-max-iterations)))
      (is (every? #(= :pid (:command-source %)) (:trace r))
          "every command in a clean run came from the PID, never the alarm override")
      (testing "the RPM command is genuinely computed, not a canned constant -- it changes across iterations as the error shrinks"
        (is (> (count (distinct (map :command-issued (:trace r)))) 1)))
      (testing "the CoV trajectory is genuinely computed and strictly improving"
        (let [covs (map #(get-in % [:sensor-reading :mixing-homogeneity-cov-pct]) (:trace r))]
          (is (every? (fn [[a b]] (< b a)) (partition 2 1 covs))))))))

(deftest alarm-triggered-run-forces-safe-state-and-still-terminates-safely
  (testing "a forced out-of-window pH fixture: every command is overridden to the documented safe RPM, and the loop still terminates (converges or hits the bound), never hangs or blows past the safe range"
    (let [r (mes/run-homogeneity-control-loop {:tank small-tank :ph-override 20.0})]
      (is (true? (:alarm-triggered? r)))
      (is (every? #(= :alarm-override (:command-source %)) (:trace r))
          "pH never returns to plausible in this fixture, so the override wins every tick")
      (is (every? #(= mes/alarm-override-safe-rpm-setpoint (:command-issued %)) (:trace r)))
      (is (some #(seq (:alarm-trips %)) (:trace r)) "at least one real ISA-18.2 state transition was recorded")
      (is (<= (:iterations-run r) (inc mes/control-loop-max-iterations))
          "bounded -- never an unbounded loop even while alarm-overridden")
      (testing "the safe-state RPM still keeps the batch agitating -- CoV keeps improving even at the reduced setpoint"
        (let [covs (map #(get-in % [:sensor-reading :mixing-homogeneity-cov-pct]) (:trace r))]
          (is (every? (fn [[a b]] (< b a)) (partition 2 1 covs))))))))

(deftest control-loop-never-runs-past-its-own-iteration-bound
  (testing "an artificially tiny max-iterations still terminates the loop exactly at the bound, converged or not"
    (let [r (mes/run-homogeneity-control-loop {:tank small-tank :max-iterations 2})]
      (is (false? (:converged? r)) "2 ticks isn't enough to reach threshold from the just-charged state on this mesh")
      (is (= 3 (:iterations-run r)) "ticks 0,1,2 evaluated, then the bound stops it -- never an unbounded loop")
      (is (= 3 (count (:trace r)))))))

(deftest control-loop-is-deterministic
  (testing "same inputs -> byte-identical trace, pure functional (PID + CFD + alarm state machine, no randomness)"
    (let [r1 (mes/run-homogeneity-control-loop {:tank small-tank})
          r2 (mes/run-homogeneity-control-loop {:tank small-tank})]
      (is (= (:final-cov r1) (:final-cov r2)))
      (is (= (:iterations-run r1) (:iterations-run r2)))
      (is (= (map :command-issued (:trace r1)) (map :command-issued (:trace r2)))))))
