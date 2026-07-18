(ns hygaccess.mes
  "Manufacturing Execution System (MES) integration CONTRACT layer for
  the low-cost hygiene/disinfectant commercialization actor.

  CRITICAL SCOPE BOUNDARY: this namespace is an INTERFACE CONTRACT plus
  a deterministic MOCK implementation ONLY -- it does NOT connect to,
  control, or actuate any real formulation/filling-line equipment or
  plant historian/SCADA system. It defines what a REAL plant's MES
  would need to expose for this actor to integrate with (equipment-
  status polling + a batch-telemetry reading), the same
  'administrative-only, never a real control path' posture
  `:log-production-batch`/`hygaccess.registry` already establishes for
  every other data point this actor logs (see README `What this actor
  does NOT do`). `MockMES` below is a fixed, documented, non-real
  reference implementation -- the same 'deterministic mock so the actor
  graph runs offline' discipline `hygaccess.advisor/mock-advisor`
  already uses. A real deployment would implement `MESSource` against
  an actual plant MES/historian; `hygaccess.governor` independently
  re-verifies every field a `:record-mes-reading` proposal carries
  before it can ever commit -- this namespace supplies READINGS, never
  a verdict, and never actuates anything.

  `cfd-result->telemetry-reading` below is the concrete adapter from the
  sibling repo `kotoba-lang/kami-app-hygaccess-plant`'s own CFD
  mixing-tank simulation result shape into this protocol's telemetry-
  reading shape -- the 'loose EDN-map coupling' this actor's docs have
  promised since `hygaccess.registry/homogeneity-cov-threshold-pct`'s
  own doc comment first named that sibling repo. This repo still does
  NOT fetch, read, or depend on that repo's code (no `deps.edn` entry
  added) -- the adapter is a plain function over a plain EDN map shaped
  like that repo's own documented output."
  )

;; ----------------------------- physical plausibility windows -----------------------------
;;
;; Every window below is this actor's own documented REPRESENTATIVE
;; choice for catching fabricated/sensor-fault telemetry data -- not a
;; citation to a specific instrument spec, mirroring
;; `hygaccess.registry/raw-material-assay-plausibility-pct`'s own
;; "plausibility window, not a precise specification floor or ceiling"
;; framing.

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
  thermally-controlled process (see `cfd-result->telemetry-reading`
  below: the sibling CFD sim is isothermal). The lower bound keeps a
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

;; ----------------------------- MESSource protocol -----------------------------

(defprotocol MESSource
  "What a REAL plant's Manufacturing Execution System would need to
  expose for this actor to integrate with -- at minimum, equipment-
  status polling and a batch-telemetry reading. `hygaccess.governor`
  independently re-verifies every field returned before any
  `:record-mes-reading` proposal built from it can ever commit; this
  protocol supplies readings only, never a verdict, and no
  implementation of it may ever actuate real equipment (see ns
  docstring)."
  (equipment-status [src equipment-id]
    "Poll `equipment-id`'s current operating status. Returns a map
    `{:equipment-id .. :online? bool :last-heartbeat-at str|nil
    :source kw}` (or nil if unknown to this source).")
  (batch-telemetry-reading [src batch-id]
    "Pull `batch-id`'s current batch-level telemetry:
    `{:batch-id .. :temperature-c .. :ph .. :mixing-rpm ..
    :mixing-homogeneity-cov-pct .. :source kw}`. In a real deployment
    this is a REAL instrument reading -- ground truth this actor's
    `:record-mes-reading` op logs, never a self-report the advisor
    invents."))

;; ----------------------------- MockMES (reference implementation, NOT a real plant connection) -----------------------------

(defrecord MockMES [readings]
  MESSource
  (equipment-status [_ equipment-id]
    {:equipment-id equipment-id
     :online? true
     :last-heartbeat-at "mock-mes: no real plant connection -- see hygaccess.mes ns docstring"
     :source :mock-mes})
  (batch-telemetry-reading [_ batch-id]
    (or (get readings batch-id)
        {:batch-id batch-id
         :temperature-c 28.0
         :ph 11.5
         :mixing-rpm 120.0
         :mixing-homogeneity-cov-pct 2.5
         :source :mock-mes})))

(defn mock-mes
  "A deterministic MockMES reference implementation. NOT a real plant
  connection -- returns either a caller-supplied `readings` map
  (`batch-id -> telemetry-reading map`, for tests/demo scenarios that
  need specific values) or a fixed, documented default reading, used so
  this actor's graph runs offline, mirroring
  `hygaccess.advisor/mock-advisor`'s own 'deterministic mock, default
  everywhere' discipline. In production `MESSource` would be
  implemented against a real plant's SCADA/MES/historian system --
  `hygaccess.governor` independently re-verifies every field regardless
  of source, so swapping this for a real implementation changes nothing
  about the governance contract."
  ([] (mock-mes {}))
  ([readings] (->MockMES readings)))

;; ----------------------------- CFD-result adapter (kotoba-lang/kami-app-hygaccess-plant) -----------------------------

(defn cfd-result->telemetry-reading
  "Adapter: maps the sibling repo `kotoba-lang/kami-app-hygaccess-
  plant`'s own CFD mixing-tank simulation result-record shape (the
  return value of that repo's
  `kami-app-hygaccess-plant.mixing/run-mixing-scenario`, verified
  against that repo's actual source, a plain EDN map -- referenced here
  by SHAPE ONLY; this repo does NOT fetch, read, or depend on that
  repo's code, no `deps.edn` entry added, matching the existing
  `hygaccess.registry/homogeneity-cov-threshold-pct` doc-comment
  convention) into THIS namespace's own batch-telemetry-reading shape,
  for `batch-id`.

  The sibling repo's own result map shape is:
    {:solver :hygaccess-mixing-tank
     :mesh {:nx .. :ny .. :lx .. :ly .. :dx .. :dy .. :n-cells ..}
     :flow {:nu-eff-m2-s .. :dt-s .. :steps-run .. :converged .. :max-courant ..}
     :scalar {:diffusivity-m2-s .. :dt-s .. :steps .. :dilution-ratio ..}
     :process {:process/product-sku .. :process/active .. ...}
     :concentration-field-pct [..per-cell values..]
     :concentration-mean-pct <number>
     :concentration-mass-conservation-defect <number>
     :mixing-homogeneity-cov-pct-history [..]
     :mixing-homogeneity-cov-pct <number>   ;; THE critical field
     :fidelity :finite-volume-reference
     :status :screening-only}

  This CFD scenario is an ISOTHERMAL, non-reactive scalar-transport
  simulation (see that repo's `mixing.cljc` ns docstring: a frozen PISO
  flow field driving transient scalar advection/diffusion, no thermal
  or pH physics at all) -- it does NOT model temperature or pH, and
  does NOT propagate the tank's own agitation-drive-velocity into this
  RESULT record either (only into the separate `:tank/...` INPUT map,
  which this function does not receive). This adapter does NOT
  fabricate values for fields the CFD sim does not actually produce:
  `:temperature-c`/`:ph`/`:mixing-rpm` come back `nil` from a
  CFD-sourced reading -- a real deployment would need a SEPARATE
  physical instrument (or a thermal/pH-capable simulation) for those
  three fields. `hygaccess.governor`'s plausibility checks are written
  nil-safe (see `mes-reading-*-implausible-violations`), so a nil field
  never fabricates a spurious HARD-hold, but it also never counts as
  evidence of anything."
  [cfd-result batch-id]
  {:batch-id batch-id
   :temperature-c nil
   :ph nil
   :mixing-rpm nil
   :mixing-homogeneity-cov-pct (:mixing-homogeneity-cov-pct cfd-result)
   :source :cfd-simulation
   :cfd-solver (:solver cfd-result)
   :cfd-fidelity (:fidelity cfd-result)
   :cfd-status (:status cfd-result)
   :cfd-steps (get-in cfd-result [:scalar :steps])
   :cfd-mesh-n-cells (get-in cfd-result [:mesh :n-cells])
   :cfd-flow-converged (get-in cfd-result [:flow :converged])})
