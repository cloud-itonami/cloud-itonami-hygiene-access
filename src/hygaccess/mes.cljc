(ns hygaccess.mes
  "Manufacturing Execution System (MES) integration CONTRACT layer for
  the low-cost hygiene/disinfectant commercialization actor.

  CRITICAL SCOPE BOUNDARY: this namespace is an INTERFACE CONTRACT plus
  a deterministic MOCK implementation ONLY -- it does NOT connect to,
  control, or actuate any real formulation/filling-line equipment or
  plant historian/SCADA system. It defines what a REAL plant's MES
  would need to expose for this actor to integrate with (a batch-
  telemetry reading), the same 'administrative-only, never a real
  control path' posture `:log-production-batch`/`hygaccess.registry`
  already establishes for every other data point this actor logs (see
  README `What this actor does NOT do`). `hygaccess.governor`
  independently re-verifies every field a `:record-mes-reading`
  proposal carries before it can ever commit -- this namespace supplies
  READINGS, never a verdict, and never actuates anything.

  ENGINE REWIRE (this build, two parts):

  1. The bespoke `MESSource` protocol + `MockMES` defrecord are RETIRED
     in favor of `dcs.ports/IFieldIO` (`kotoba-lang/dcs`, the 'DCS-as-
     EDN' domain library) -- a host-injected `read-tag`/`write-tag!`
     seam a real plant fieldbus/OPC-UA/historian driver could implement
     identically, not a hygaccess-specific interface. The four tags this
     domain needs (pH, temperature, mixing RPM, mixing-homogeneity-
     cov-pct) are declared in `dcs.model` terms (`mixing-tank-system`
     below) -- this domain has no automatic control loop of its own (no
     PID/alarm requirement, just telemetry readings), so `dcs.execute`'s
     scan-cycle engine is not invoked here; only the tag-registry
     (`dcs.model`) + read/write seam (`dcs.ports`) portions of `dcs` are
     used, a legitimate partial adoption (`dcs.model`/`dcs.ports` have
     no dependency on `dcs.execute`).

  2. The mixing-homogeneity tag's mock reading is no longer a canned/
     hand-picked number -- `mock-plant-io` below reads it through
     `real-cfd-homogeneity-cov-pct`, which actually INVOKES the sibling
     repo `kotoba-lang/kami-app-hygaccess-plant`'s own real, tested
     finite-volume Navier-Stokes + scalar-transport CFD solve
     (`kami-app-hygaccess-plant.mixing/run-mixing-scenario`) -- pure
     math over `kami-app-hygaccess-plant.process/default-tank`/
     `default-process`, no physical actuation of anything. That solve
     is a deterministic pure function of its own default inputs (see
     that repo's own golden test `deterministic-across-repeated-runs`),
     so re-running it on every single mock telemetry read would be
     wasted, expensive (PISO to convergence + 40 scalar-transport
     ticks -- ~2.5s wall-clock measured on this build's dev machine)
     recomputation of the exact same answer; `cfd-result` below is a
     `delay`, realized at most ONCE per process and cached for every
     subsequent read within that process's lifetime -- see that var's
     own docstring."
  (:require [dcs.model :as dcs-model]
            [dcs.ports :as dcs-ports]
            [kami-app-hygaccess-plant.mixing :as mixing]
            [kami-app-hygaccess-plant.process :as process]))

;; ----------------------------- physical plausibility windows -----------------------------
;;
;; Every window below is this actor's own documented REPRESENTATIVE
;; choice for catching fabricated/sensor-fault telemetry data -- not a
;; citation to a specific instrument spec, mirroring
;; `hygaccess.registry/raw-material-assay-plausibility-pct`'s own
;; "plausibility window, not a precise specification floor or ceiling"
;; framing. UNCHANGED by the dcs/CFD engine rewire above -- these
;; remain this actor's OWN governance contract, independent of which
;; telemetry-source abstraction supplies the raw reading.

(def ph-plausible-range
  "[0.0 14.0] -- the full real-world aqueous-solution pH scale. A
  reading outside this range is not a real pH measurement of any
  solution, fabricated/sensor-fault data, never a real telemetry
  point."
  [0.0 14.0])

(def temperature-plausible-range-c
  "[5.0 55.0] degrees C -- this actor's own documented REPRESENTATIVE
  plausibility window for an AMBIENT (unheated/uncooled) liquid
  dilution/mixing process -- this plant does not model a
  thermally-controlled process (the sibling CFD sim is isothermal, see
  `real-cfd-homogeneity-cov-pct` below). The lower bound keeps a
  cold-morning ambient start comfortably above freezing; the upper
  bound allows for un-air-conditioned Gulf/MENA and South/Southeast
  Asian ambient plant temperatures well above a temperate 25C, without
  accepting a physically-implausible near-boiling reading. A reading
  outside this window (e.g. 90C) indicates fabricated/sensor-fault
  data, not a real ambient mixing-tank telemetry point."
  [5.0 55.0])

(def mixing-rpm-plausible-range
  "[10.0 500.0] RPM -- this actor's own documented REPRESENTATIVE
  plausibility window for a small-batch (tens-of-liters) agitated
  mixing-tank paddle/impeller speed, matching the scale of the sibling
  `kotoba-lang/kami-app-hygaccess-plant` `tank.cljc`'s own small-batch
  0.6m-cross-section tank (agitated by a jet-shear drive at a
  representative 0.5 m/s tip velocity -- that figure and this RPM
  window are NOT derived from each other, both are independently-
  documented representative choices for their own respective models).
  A reading outside this window (e.g. 5000 RPM) indicates fabricated/
  sensor-fault data, not a real small-batch agitator speed."
  [10.0 500.0])

(def homogeneity-cov-physical-range-pct
  "[0.0 100.0] -- the PHYSICAL plausibility bound on a
  coefficient-of-variation reading (a CoV cannot be negative, and a CoV
  above 100% would mean the standard deviation exceeds the mean -- an
  implausible/fabricated reading for this process class). Deliberately
  DIFFERENT from `hygaccess.registry/homogeneity-cov-threshold-pct`
  (5.0%, the GMP ACCEPTANCE threshold): an MES reading above 5.0% is
  still a real, physically-plausible telemetry point worth recording
  (a batch that mixed poorly), just one that will separately fail the
  GMP acceptance gate elsewhere; only a reading outside [0, 100] is
  rejected HERE as fabricated/sensor-fault DATA."
  [0.0 100.0])

(def homogeneity-match-tolerance-pct
  "0.5 percentage points -- this actor's own documented REPRESENTATIVE
  reconciliation tolerance between an independently-sourced MES/CFD
  homogeneity reading and a batch's own self-reported IPQC
  `:mixing-homogeneity-cov-pct` value. Not a claim of a specific
  metrological citation; open to revision if a real deployment's
  applicable measurement-uncertainty budget sets a different number."
  0.5)

(defn- within? [[lo hi] v]
  (boolean (and (number? v)
                (>= (double v) (double lo))
                (<= (double v) (double hi)))))

(defn ph-plausible? [ph] (within? ph-plausible-range ph))
(defn temperature-plausible? [temp-c] (within? temperature-plausible-range-c temp-c))
(defn mixing-rpm-plausible? [rpm] (within? mixing-rpm-plausible-range rpm))
(defn homogeneity-cov-physically-plausible? [cov] (within? homogeneity-cov-physical-range-pct cov))

(defn- abs-diff
  "Portable (no `Math/abs` cljs-interop dependency) absolute difference."
  [a b]
  (let [d (- (double a) (double b))]
    (if (neg? d) (- d) d)))

(defn homogeneity-values-match?
  "Ground-truth cross-check: do `mes-value` and `self-reported-value`
  (both `:mixing-homogeneity-cov-pct` numbers) agree within
  `homogeneity-match-tolerance-pct`? nil/non-number on either side is
  never treated as a match -- a missing value proves nothing, so
  callers must independently guard for presence before relying on a
  `false` result here as evidence of a real mismatch."
  [mes-value self-reported-value]
  (boolean
   (and (number? mes-value) (number? self-reported-value)
        (<= (abs-diff mes-value self-reported-value) homogeneity-match-tolerance-pct))))

;; ----------------------------- dcs.model: the mixing-tank tag set -----------------------------

(def ph-tag-id "MIX-TANK-01.PH")
(def temperature-tag-id "MIX-TANK-01.TEMP")
(def mixing-rpm-tag-id "MIX-TANK-01.RPM")
(def homogeneity-tag-id "MIX-TANK-01.HOMOGENEITY-COV-PCT")

(def mixing-tank-system
  "The one illustrative mixing tank this build instruments, expressed in
  `dcs.model` terms (see ns docstring 'ENGINE REWIRE' part 1) -- one
  `:dcs/area` (the tank itself) and four `:dcs/tags` (all `:ai`, analog
  input -- this domain only ever READS telemetry, it never writes a
  setpoint/output through this seam). Mirrors the one tank the sibling
  repo `kotoba-lang/kami-app-hygaccess-plant` itself simulates
  (`kami-app-hygaccess-plant.process/default-tank`, id
  `:hygaccess-mixing-tank-01`) -- same illustrative single-tank scope,
  now named consistently across both repos' own domain data. Not fed
  through `dcs.execute/scan` (see ns docstring) -- this system exists
  for its tag REGISTRY (id/kind/units/range), the seam `mock-plant-io`
  below reads/writes through via `dcs.ports/IFieldIO`."
  (-> (dcs-model/system)
      (dcs-model/add-area (dcs-model/area "mix-tank-01" {:name "Water-purification-drops dilution/mixing tank (illustrative)"}))
      (dcs-model/add-tag (dcs-model/tag ph-tag-id :ai
                                         {:units :pH :range ph-plausible-range :area "mix-tank-01"
                                          :description "In-tank pH probe reading."}))
      (dcs-model/add-tag (dcs-model/tag temperature-tag-id :ai
                                         {:units :degC :range temperature-plausible-range-c :area "mix-tank-01"
                                          :description "In-tank ambient-process temperature probe reading."}))
      (dcs-model/add-tag (dcs-model/tag mixing-rpm-tag-id :ai
                                         {:units :rpm :range mixing-rpm-plausible-range :area "mix-tank-01"
                                          :description "Agitator/impeller speed reading."}))
      (dcs-model/add-tag (dcs-model/tag homogeneity-tag-id :ai
                                         {:units :pct :range homogeneity-cov-physical-range-pct :area "mix-tank-01"
                                          :description "Mixing-homogeneity coefficient-of-variation, CFD/IPQC-sourced."}))))

;; ----------------------------- real CFD-backed homogeneity (kotoba-lang/kami-app-hygaccess-plant) -----------------------------

;; Memoized real CFD mixing-tank solve result. Realizes `kami-app-
;; hygaccess-plant.mixing/run-mixing-scenario` (that repo's own tested
;; finite-volume incompressible-NS + scalar-transport solver, run over
;; `kami-app-hygaccess-plant.process/default-tank`/`default-process`) AT
;; MOST ONCE per process, the first time anything derefs this var --
;; every subsequent `@cfd-result` (e.g. every later mock telemetry read
;; within the same test-process/session) reuses the cached result rather
;; than re-running the solve. This is safe BECAUSE the solve is a
;; deterministic pure function of its fixed default inputs (verified
;; against that repo's own golden test `deterministic-across-repeated-
;; runs`: identical input -> byte-identical output, every time) -- a
;; `delay` is the idiomatic Clojure 'run once, cache forever' primitive
;; for exactly this shape of memoization, portable .cljc (JVM/cljs
;; alike), no extra library needed. (`defonce` does not support a
;; docstring arg -- hence this being a comment, not a docstring.)
(defonce ^:private cfd-result
  (delay (mixing/run-mixing-scenario {:tank (process/default-tank)
                                       :process process/default-process})))

(defn real-cfd-homogeneity-cov-pct
  "The REAL, genuinely-computed mixing-homogeneity CoV (%) for the
  illustrative mixing tank -- the actual final-tick output of `kami-
  app-hygaccess-plant.mixing/run-mixing-scenario`'s own converged
  Navier-Stokes + scalar-transport solve (`:mixing-homogeneity-cov-pct`
  in its result map), NOT a hand-picked stub. See `cfd-result` above
  for the memoization discipline -- safe to call on every reading."
  []
  (:mixing-homogeneity-cov-pct @cfd-result))

;; ----------------------------- mock IFieldIO (dcs.ports/IFieldIO), NOT a real plant connection -----------------------------

(defrecord PlantIO [values]
  dcs-ports/IFieldIO
  (read-tag [_ tag-id]
    (if-let [v (get @values tag-id)]
      v
      (when (= tag-id homogeneity-tag-id)
        (real-cfd-homogeneity-cov-pct))))
  (write-tag! [_ tag-id value]
    (swap! values assoc tag-id value)))

(def default-seed
  "The fixed, documented default reading for the two tags this mock does
  NOT back with a real solve (pH/temperature/RPM have no CFD/simulation
  source in this build -- the sibling CFD sim is isothermal and does not
  model pH or RPM at all, see `real-cfd-homogeneity-cov-pct` above and
  `kami-app-hygaccess-plant.mixing` ns docstring). Deliberately does NOT
  seed the homogeneity tag -- leaving it unset means every fresh
  `mock-plant-io` reads it through `real-cfd-homogeneity-cov-pct`."
  {:ph 11.5 :temperature-c 28.0 :mixing-rpm 120.0})

(defn seed-batch-reading!
  "Write a caller-supplied reading's fields onto `io`'s shared tags
  through `dcs.ports/write-tag!` -- mirrors the old bespoke `mock-mes`
  per-batch `readings` override map's role (tests/demo scenarios that
  need a SPECIFIC value, e.g. an implausible reading to exercise a HARD
  hold), adapted to this build's single-shared-tank dcs model: a real
  single-instrumented tank's tags reflect whichever batch currently
  occupies it, so 'seed a reading for a batch' becomes 'write that
  batch's values onto the shared tags right before reading them'. Only
  writes fields PRESENT (non-nil) in `reading` -- an absent field is
  left as whatever `io` already holds (or, for the homogeneity tag,
  falls through to the real CFD result). Returns `io`."
  [io reading]
  (doseq [[k tag-id] {:ph ph-tag-id :temperature-c temperature-tag-id
                       :mixing-rpm mixing-rpm-tag-id
                       :mixing-homogeneity-cov-pct homogeneity-tag-id}
          :when (some? (get reading k))]
    (dcs-ports/write-tag! io tag-id (get reading k)))
  io)

(defn mock-plant-io
  "A deterministic mock `dcs.ports/IFieldIO` implementation. NOT a real
  plant connection -- returns a fresh io seeded with the documented
  `default-seed` reading (optionally overridden), used so this actor's
  graph runs offline, mirroring `hygaccess.advisor/mock-advisor`'s own
  'deterministic mock, default everywhere' discipline. In production
  `dcs.ports/IFieldIO` would be implemented against a real plant's
  fieldbus/OPC-UA/historian system -- `hygaccess.governor` independently
  re-verifies every field regardless of source, so swapping this for a
  real implementation changes nothing about the governance contract.

  `reading-overrides` (optional) -- a reading map (same shape as
  `seed-batch-reading!` takes) merged OVER `default-seed`, for tests/
  demo scenarios that need a specific pH/temperature/RPM value (or an
  explicit homogeneity override, bypassing the real CFD result for that
  one io instance -- useful for exercising an implausible-reading HARD
  hold without depending on the real solve's own genuine number)."
  ([] (mock-plant-io {}))
  ([reading-overrides]
   (let [io (->PlantIO (atom {}))]
     (seed-batch-reading! io (merge default-seed reading-overrides))
     io)))

(defn batch-telemetry-reading
  "Pull `batch-id`'s current batch-level telemetry by reading through
  `io` (a `dcs.ports/IFieldIO` implementation -- `mock-plant-io` in this
  build, a real plant fieldbus/OPC-UA/historian driver in a real
  deployment). Returns:
  `{:batch-id .. :temperature-c .. :ph .. :mixing-rpm ..
  :mixing-homogeneity-cov-pct .. :source :dcs-plant-io}` -- the SAME
  shape the retired `MESSource`/`MockMES` contract documented.
  `hygaccess.governor` independently re-verifies every field before any
  `:record-mes-reading` proposal built from it can ever commit -- this
  fn supplies a reading only, never a verdict, and never actuates
  anything (`dcs.ports/write-tag!` is used elsewhere in this namespace
  ONLY by the mock's own internal telemetry seeding, `seed-batch-
  reading!`/`mock-plant-io` -- never to actuate real equipment)."
  [io batch-id]
  {:batch-id batch-id
   :temperature-c (dcs-ports/read-tag io temperature-tag-id)
   :ph (dcs-ports/read-tag io ph-tag-id)
   :mixing-rpm (dcs-ports/read-tag io mixing-rpm-tag-id)
   :mixing-homogeneity-cov-pct (dcs-ports/read-tag io homogeneity-tag-id)
   :source :dcs-plant-io})
