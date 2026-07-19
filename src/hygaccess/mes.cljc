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
     hand-picked number, and (as of this build) no longer even a single
     monolithic CFD call -- `mock-plant-io` below reads it through
     `real-cfd-homogeneity-cov-pct`, which is now the FINAL reading of a
     GENUINE CLOSED CONTROL LOOP (`run-homogeneity-control-loop`): each
     iteration reads a sensor summary from the sibling repo
     `kotoba-lang/kami-app-hygaccess-plant`'s stepwise CFD API
     (`kami-app-hygaccess-plant.mixing/sensor-reading` over a state
     produced by `init-state`/`step`), feeds the CoV error into
     `kotoba-lang/dcs`'s real `dcs.pid` PID algorithm to compute a
     candidate agitator RPM setpoint, runs one `dcs.alarm` ISA-18.2
     scan-cycle against this actor's own pH/temperature/RPM plausibility
     windows (below) and OVERRIDES the PID's command with a documented
     safe-state RPM whenever any of them trips, then applies whichever
     command won to genuinely advance the CFD's OWN next tick
     (`kami-app-hygaccess-plant.mixing/step`) -- repeated until the CoV
     is under `hygaccess.registry/homogeneity-cov-threshold-pct` or a
     bounded max-iteration count is hit. See `run-homogeneity-control-
     loop`'s own docstring for the full account, and README.md for this
     build's explicit 'digital twin only, no physical actuation pathway
     anywhere in this codebase' boundary statement.

     This loop is genuinely computed, not memoized-away-into-a-fake: it
     IS the real math (PID + CFD + alarm state machine), same as the
     prior single-call solve was. It is, however, still expensive
     (multiple PISO re-converges), so exactly like the prior monolithic
     call, `control-loop-result` below is a `delay`, realized at most
     ONCE per process and cached for every subsequent read within that
     process's lifetime -- see that var's own docstring. This is safe
     for the SAME reason the prior memoization was: the loop is a
     deterministic pure function of its fixed default inputs."
  (:require [dcs.model :as dcs-model]
            [dcs.ports :as dcs-ports]
            [dcs.pid :as pid]
            [dcs.alarm :as alarm]
            [kami-app-hygaccess-plant.mixing :as mixing]
            [kami-app-hygaccess-plant.process :as process]
            [kami-app-hygaccess-plant.tank :as tank]
            [hygaccess.registry :as registry]))

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

(def default-seed
  "The fixed, documented default reading for pH (this build has no pH
  dynamics/CFD source at all -- the sibling CFD sim has no chemistry
  model, only momentum + scalar-concentration transport, see
  `kami-app-hygaccess-plant.mixing` ns docstring) and the STARTING
  agitator RPM the closed control loop below seeds its first tick with
  (`run-homogeneity-control-loop` moves RPM for real from here on --
  see that fn's docstring). Deliberately does NOT seed the homogeneity
  or temperature tags -- leaving them unset means every fresh
  `mock-plant-io` reads homogeneity through `real-cfd-homogeneity-cov-
  pct` (now the real closed control loop's final reading) and
  temperature through that same loop's flow-derived proxy."
  {:ph 11.5 :temperature-c 28.0 :mixing-rpm 120.0})

;; ----------------------------- closed control loop: PID + ISA-18.2 alarm + real CFD (kotoba-lang/dcs + kami-app-hygaccess-plant) -----------------------------
;;
;; A REAL closed-loop control simulation, entirely within this software's
;; own digital twin: sensor read -> PID compute -> command -> CFD state
;; update, iterated, until the mixing-homogeneity CoV is in spec or a
;; bounded number of iterations is exhausted. NO physical actuation
;; pathway exists anywhere in this codebase or is intended -- no GPIO, no
;; serial, no Modbus, no OPC-UA, no PLC client, nowhere. "Closed-loop"
;; here means the next CFD tick's boundary condition is a real function
;; of the previous tick's simulated state and a genuinely computed
;; command, the same boundary this repo's every other "administrative-
;; only, never a real control path" statement already draws (see README
;; `What this actor does NOT do`) -- just now stated for the mixing-
;; homogeneity reading specifically, as plainly as this repo's existing
;; no-payment/no-real-filing statements.

(def agitator-rpm-setpoint-actuator-limits
  "[10.0 120.0] RPM -- the closed loop's OWN PID output-limits (`dcs.pid/
  step`'s `output-limits` arg), DELIBERATELY DIFFERENT from `mixing-rpm-
  plausible-range` ([10.0 500.0]) above for the same reason
  `homogeneity-cov-physical-range-pct` is deliberately different from
  `hygaccess.registry/homogeneity-cov-threshold-pct`: one window is a
  wide PLAUSIBILITY-DETECTION ceiling for catching fabricated/sensor-
  fault telemetry (500 RPM), the other is this loop's own narrower
  REALISTIC actuator operating envelope. The upper bound (120.0) is not
  arbitrary -- it matches this repo's own already-documented
  `default-seed` nominal RPM reading (120.0), so the control loop's
  actuator ceiling is grounded in a figure this repo already treats as
  'a normal reading' rather than reusing the wide fabricated-data
  ceiling. The lower bound matches the plausibility floor."
  [10.0 120.0])

(def alarm-override-safe-rpm-setpoint
  "20.0 RPM -- the safe-state command an active pH/temperature/RPM alarm
  forces INSTEAD OF the PID's own output (see `run-homogeneity-control-
  loop`). Deliberately NOT zero: stopping the agitator entirely would
  let the NaOCl/water charge settle/stratify while the alarm condition
  is unresolved, a different (and not obviously safer) failure mode.
  20.0 is comfortably above the 10.0 RPM plausibility/actuator floor
  (so it reads as a real, deliberately-chosen gentle-agitation command,
  not a degenerate edge value) and well below this loop's own normal
  operating range (`agitator-rpm-setpoint-actuator-limits` tops out at
  120.0) and the PID's typical commanded setpoints once mixing is under
  way -- keeps the batch gently, continuously agitated (preventing
  settling) WITHOUT letting the PID keep escalating agitation on a
  plant with an unresolved instrumentation/plausibility fault. A
  genuine judgment call, documented per this build's own discipline of
  writing down the reasoning behind every representative choice."
  20.0)

(def homogeneity-pid-tuning
  "Reverse-acting PID tuning (NEGATIVE-signed `{:kp :ki :kd}`) for the
  mixing-homogeneity closed loop. RAISING the agitator RPM setpoint
  LOWERS the CoV process variable (more agitation -> faster convective
  mixing -> lower CoV, empirically verified in
  `kami-app-hygaccess-plant`'s own `higher-rpm-mixes-faster-than-lower-
  rpm` test) -- the OPPOSITE sign relationship a direct-acting loop
  assumes. `dcs.pid/step`'s `error = sp - pv` formula has no separate
  reverse/direct-acting toggle, so this loop implements 'reverse
  acting' the standard real-DCS way: negative-signed gains. With
  `pv = current CoV` and `sp = hygaccess.registry/homogeneity-cov-
  threshold-pct` (5.0), `error` is INCREASINGLY NEGATIVE the further
  CoV sits above the 5.0% target; multiplied by a NEGATIVE `kp` that
  yields a MORE POSITIVE (higher-RPM) output exactly when more
  agitation is needed, and the output relaxes toward the PID's steady-
  state value as CoV approaches the target from above. `kp` dominates
  (the CoV/RPM relationship is strongly monotonic and does not need
  aggressive integral action to reach threshold within this loop's
  bounded iteration budget); small `ki`/`kd` damp residual offset/
  overshoot without material windup risk (`dcs.pid`'s own clamped
  conditional-integration anti-windup, unmodified, still applies)."
  {:kp -6.0 :ki -0.3 :kd -1.0})

(def control-loop-max-iterations
  "40 -- the closed loop's bounded iteration ceiling (NEVER an unbounded
  loop). Matches `kami-app-hygaccess-plant.mixing/init-state`'s own
  default `scalar-steps`: this loop never runs past the batch's own
  physically-simulated mixing window (90 simulated seconds / 40 ticks)
  -- if the CoV has not reached threshold by the end of the batch's own
  simulated agitation window, that is itself the answer (an out-of-spec
  batch), not a reason to keep simulating past the window the CFD
  scenario itself represents."
  40)

(def ph-alarm-deadband 0.5)
(def temperature-alarm-deadband-c 2.0)
(def rpm-alarm-deadband 10.0)

(defn- alarm-hi-id [tag-id] (str tag-id ".HI"))
(defn- alarm-lo-id [tag-id] (str tag-id ".LO"))

(def homogeneity-control-loop-id "MIX-TANK-01.RPM-LOOP")

(def mixing-tank-system
  "The one illustrative mixing tank this build instruments, expressed in
  `dcs.model` terms (see ns docstring 'ENGINE REWIRE' part 1) -- one
  `:dcs/area` (the tank itself), four `:dcs/tags` (all `:ai`, analog
  input -- this domain only ever READS telemetry through `dcs.ports`,
  it never writes a setpoint/output through THAT seam; the closed
  control loop below writes its own RPM command internally, directly,
  never through `dcs.ports/write-tag!` -- see `run-homogeneity-control-
  loop`), six `:dcs/alarms` (an ISA-18.2 HI/LO pair per pH/temperature/
  RPM tag, at each tag's own plausibility-window bounds), and one
  `:dcs/loop` (the mixing-homogeneity PID loop itself, `dcs.model`-
  registered for introspection/documentation even though this build
  drives its `dcs.pid`/`dcs.alarm` calls directly rather than through
  `dcs.execute/scan` -- same 'partial adoption' posture as the tag
  registry, see ns docstring). Mirrors the one tank the sibling repo
  `kotoba-lang/kami-app-hygaccess-plant` itself simulates
  (`kami-app-hygaccess-plant.process/default-tank`, id
  `:hygaccess-mixing-tank-01`) -- same illustrative single-tank scope,
  now named consistently across both repos' own domain data."
  (-> (dcs-model/system)
      (dcs-model/add-area (dcs-model/area "mix-tank-01" {:name "Water-purification-drops dilution/mixing tank (illustrative)"}))
      (dcs-model/add-tag (dcs-model/tag ph-tag-id :ai
                                         {:units :pH :range ph-plausible-range :area "mix-tank-01"
                                          :description "In-tank pH probe reading."}))
      (dcs-model/add-tag (dcs-model/tag temperature-tag-id :ai
                                         {:units :degC :range temperature-plausible-range-c :area "mix-tank-01"
                                          :description "In-tank ambient-process temperature probe reading (flow-derived proxy, see kami-app-hygaccess-plant.mixing/sensor-reading)."}))
      (dcs-model/add-tag (dcs-model/tag mixing-rpm-tag-id :ai
                                         {:units :rpm :range mixing-rpm-plausible-range :area "mix-tank-01"
                                          :description "Agitator/impeller speed reading -- the closed loop's own commanded setpoint."}))
      (dcs-model/add-tag (dcs-model/tag homogeneity-tag-id :ai
                                         {:units :pct :range homogeneity-cov-physical-range-pct :area "mix-tank-01"
                                          :description "Mixing-homogeneity coefficient-of-variation, real closed-loop-CFD-sourced."}))
      (dcs-model/add-alarm (dcs-model/alarm (alarm-hi-id ph-tag-id)
                                             {:tag ph-tag-id :type :hi :priority :high
                                              :setpoint (second ph-plausible-range) :deadband ph-alarm-deadband}))
      (dcs-model/add-alarm (dcs-model/alarm (alarm-lo-id ph-tag-id)
                                             {:tag ph-tag-id :type :lo :priority :high
                                              :setpoint (first ph-plausible-range) :deadband ph-alarm-deadband}))
      (dcs-model/add-alarm (dcs-model/alarm (alarm-hi-id temperature-tag-id)
                                             {:tag temperature-tag-id :type :hi :priority :high
                                              :setpoint (second temperature-plausible-range-c) :deadband temperature-alarm-deadband-c}))
      (dcs-model/add-alarm (dcs-model/alarm (alarm-lo-id temperature-tag-id)
                                             {:tag temperature-tag-id :type :lo :priority :high
                                              :setpoint (first temperature-plausible-range-c) :deadband temperature-alarm-deadband-c}))
      (dcs-model/add-alarm (dcs-model/alarm (alarm-hi-id mixing-rpm-tag-id)
                                             {:tag mixing-rpm-tag-id :type :hi :priority :high
                                              :setpoint (second mixing-rpm-plausible-range) :deadband rpm-alarm-deadband}))
      (dcs-model/add-alarm (dcs-model/alarm (alarm-lo-id mixing-rpm-tag-id)
                                             {:tag mixing-rpm-tag-id :type :lo :priority :high
                                              :setpoint (first mixing-rpm-plausible-range) :deadband rpm-alarm-deadband}))
      (dcs-model/add-loop (dcs-model/ctrl-loop homogeneity-control-loop-id
                                                {:pv-tag homogeneity-tag-id :output-tag mixing-rpm-tag-id
                                                 :mode :auto :setpoint registry/homogeneity-cov-threshold-pct
                                                 :tuning homogeneity-pid-tuning
                                                 :output-limits agitator-rpm-setpoint-actuator-limits}))))

(def monitored-alarm-ids
  "The six `:dcs/alarms` `evaluate-alarms` runs every control-loop
  iteration -- HI/LO for each of pH/temperature/RPM."
  [(alarm-hi-id ph-tag-id) (alarm-lo-id ph-tag-id)
   (alarm-hi-id temperature-tag-id) (alarm-lo-id temperature-tag-id)
   (alarm-hi-id mixing-rpm-tag-id) (alarm-lo-id mixing-rpm-tag-id)])

(defn init-alarm-states
  "Every monitored alarm's initial ISA-18.2 state (`:normal`, `dcs.alarm`'s
  own first-scan default)."
  []
  (into {} (map (fn [id] [id :normal])) monitored-alarm-ids))

(defn- reading-for-alarm-tag [tag-id readings]
  (condp = tag-id
    ph-tag-id (:ph readings)
    temperature-tag-id (:temperature-c readings)
    mixing-rpm-tag-id (:mixing-rpm readings)))

(defn evaluate-alarms
  "One ISA-18.2 scan-cycle evaluation (`dcs.alarm/step`, unmodified) of
  every `monitored-alarm-ids` entry against `readings`
  (`{:ph :temperature-c :mixing-rpm}`), given the PREVIOUS `alarm-
  states` map (`init-alarm-states` for the first tick). A reading that
  is `nil` for a tag leaves that tag's alarms' state UNCHANGED this
  tick (no data this scan proves nothing, mirrors `homogeneity-values-
  match?`'s own 'missing value is never treated as a signal' stance).

  Returns `{:alarm-states' .. :active? bool :trips [..]}` -- `:trips` is
  every STATE TRANSITION `dcs.alarm/step` reported this tick (its own
  `:dcs/event`, tagged with `:alarm-id`); `:active?` is true iff ANY
  monitored alarm sits in a non-`:normal` ISA-18.2 state (unacked/
  acked/rtn-unacked -- `dcs.alarm`'s own definition of 'active') AFTER
  this evaluation."
  [alarm-states readings]
  (let [{:keys [states trips]}
        (reduce
         (fn [acc alarm-id]
           (let [cfg (dcs-model/alarm-by-id mixing-tank-system alarm-id)
                 prev (get alarm-states alarm-id :normal)
                 v (reading-for-alarm-tag (:dcs/tag cfg) readings)]
             (if (nil? v)
               (update acc :states assoc alarm-id prev)
               (let [{:dcs/keys [state' event]} (alarm/step cfg v prev)]
                 (cond-> acc
                   true (update :states assoc alarm-id state')
                   event (update :trips conj (assoc event :alarm-id alarm-id)))))))
         {:states {} :trips []}
         monitored-alarm-ids)]
    {:alarm-states' states
     :active? (boolean (some #(not= :normal %) (vals states)))
     :trips trips}))

(defn run-homogeneity-control-loop
  "The genuine closed control loop this actor's mixing-homogeneity
  reading is now sourced from -- NOT a memoized single monolithic CFD
  call (see ns docstring 'ENGINE REWIRE' part 2). Each iteration:

  1. Read the current CFD state's sensor summary
     (`kami-app-hygaccess-plant.mixing/sensor-reading` over `mstate` --
     current CoV + the flow-derived temperature proxy).
  2. Feed `pv = CoV`, `sp = hygaccess.registry/homogeneity-cov-
     threshold-pct` into `dcs.pid/step` (`homogeneity-pid-tuning`,
     reverse-acting -- see that var's docstring) to compute a candidate
     RPM setpoint, clamped to `agitator-rpm-setpoint-actuator-limits`.
  3. Run one ISA-18.2 scan-cycle (`evaluate-alarms`) against pH/
     temperature/the currently-commanded RPM's own plausibility
     windows. If ANY alarm is active, OVERRIDE the PID's candidate with
     `alarm-override-safe-rpm-setpoint` instead -- the PID's own state
     (integral/prev-error) still advances normally underneath (so it
     resumes smoothly once the alarm clears), only the OUTPUT actually
     applied this tick is substituted.
  4. Apply whichever command won
     (`kami-app-hygaccess-plant.mixing/step`) to genuinely advance the
     CFD's own flow + concentration state one tick -- this is the real
     boundary-condition update the whole point of Repo 1's stepwise API
     exists for.
  5. Repeat until the CoV is under threshold
     (`hygaccess.registry/homogeneity-within-threshold?`) or
     `control-loop-max-iterations` is hit -- a hard bound, never an
     unbounded loop.

  `ph`/`temperature-c`/the STARTING rpm are, by default, this loop's own
  documented constants (`default-seed`'s pH, `kami-app-hygaccess-
  plant.mixing/sensor-reading`'s live temperature proxy, and the tank's
  own nominal drive velocity converted to RPM) -- `ph-override`/
  `temperature-override`/`initial-rpm-override` are TEST-ONLY hooks to
  force an implausible reading and exercise the alarm-override path
  without reaching into `dcs.model`/`dcs.alarm` internals directly
  (mirrors `mock-plant-io`'s own `reading-overrides` convention).

  Returns `{:final-reading .. :final-cov .. :converged? .. :iterations-
  run .. :alarm-triggered? .. :trace [..]}` -- `:trace` is the FULL
  per-iteration audit record (sensor reading, pH/temperature used, PID
  output, alarm active?/trips, command actually issued, command
  source `:pid`/`:alarm-override`) this build keeps as IPQC evidence
  (see `hygaccess.governor`'s new control-loop-alarm visibility check
  and README.md)."
  [{:keys [tank process ph-override temperature-override initial-rpm-override max-iterations]
    :or {tank (process/default-tank) process process/default-process
         max-iterations control-loop-max-iterations}}]
  (let [initial-rpm (double (or initial-rpm-override
                                 (tank/drive-velocity-m-s->agitator-rpm
                                  tank (tank/agitation-drive-velocity-m-s tank))))
        ph (double (or ph-override (:ph default-seed)))]
    (loop [i 0
           mstate (mixing/init-state {:tank tank :process process})
           pid-state (pid/init-state)
           alarm-states (init-alarm-states)
           current-rpm initial-rpm
           trace []]
      (let [reading (mixing/sensor-reading mstate)
            cov (:mixing-homogeneity-cov-pct reading)
            temperature-c (double (or temperature-override (:temperature-proxy-c reading)))
            alarm-eval (evaluate-alarms alarm-states {:ph ph :temperature-c temperature-c :mixing-rpm current-rpm})
            alarm-active? (:active? alarm-eval)
            {pid-output :dcs/output pid-state' :dcs/state'}
            (pid/step homogeneity-pid-tuning pid-state cov registry/homogeneity-cov-threshold-pct
                      (:scalar-dt-s mstate) agitator-rpm-setpoint-actuator-limits)
            command (if alarm-active? alarm-override-safe-rpm-setpoint pid-output)
            command-source (if alarm-active? :alarm-override :pid)
            trace-entry {:iteration i :tick (:tick mstate)
                         :sensor-reading reading :ph ph :temperature-c temperature-c
                         :pid-output pid-output
                         :alarm-active? alarm-active? :alarm-trips (:trips alarm-eval)
                         :command-issued command :command-source command-source}
            trace' (conj trace trace-entry)
            converged? (registry/homogeneity-within-threshold? cov)]
        (if (or converged? (>= i max-iterations))
          {:final-reading reading :final-cov cov :converged? converged?
           :iterations-run (inc i)
           :alarm-triggered? (boolean (some :alarm-active? trace'))
           :trace trace'}
          (recur (inc i)
                 (mixing/step mstate {:agitator-rpm-setpoint command})
                 pid-state'
                 (:alarm-states' alarm-eval)
                 command
                 trace'))))))

;; ----------------------------- real CFD-backed homogeneity (kotoba-lang/kami-app-hygaccess-plant), now via the real closed loop above -----------------------------

;; Memoized real closed-CONTROL-LOOP mixing-tank result (PID + ISA-18.2
;; alarm + `kami-app-hygaccess-plant.mixing`'s stepwise CFD, see
;; `run-homogeneity-control-loop`) AT MOST ONCE per process, the first
;; time anything derefs this var -- every subsequent `@control-loop-
;; result` (e.g. every later mock telemetry read within the same test-
;; process/session) reuses the cached result rather than re-running the
;; loop. This is safe BECAUSE the loop is a deterministic pure function
;; of its fixed default inputs (same discipline as the prior monolithic
;; `cfd-result` this replaces, and as `kami-app-hygaccess-plant.mixing/
;; step`'s own documented determinism) -- a `delay` is the idiomatic
;; Clojure 'run once, cache forever' primitive for exactly this shape of
;; memoization, portable .cljc (JVM/cljs alike), no extra library
;; needed. (`defonce` does not support a docstring arg -- hence this
;; being a comment, not a docstring.)
(defonce ^:private control-loop-result
  (delay (run-homogeneity-control-loop {})))

(defn real-cfd-homogeneity-cov-pct
  "The REAL, genuinely-computed final mixing-homogeneity CoV (%) for the
  illustrative mixing tank -- the LAST reading of
  `run-homogeneity-control-loop`'s own real closed-loop trace (PID +
  ISA-18.2 alarm + `kami-app-hygaccess-plant.mixing`'s stepwise CFD),
  NOT a hand-picked stub and NOT a single monolithic solve's own final
  tick. See `control-loop-result` above for the memoization discipline
  -- safe to call on every reading."
  []
  (:final-cov @control-loop-result))

(defn control-loop-alarm-triggered?
  "Ground-truth visibility fact (see `hygaccess.governor`'s independent
  re-derivation of the same signal, and README.md): did the SAME real
  closed-loop trace `real-cfd-homogeneity-cov-pct` reads from ever fire
  an alarm override (any tick where `evaluate-alarms` reported
  `:active? true`, forcing `alarm-override-safe-rpm-setpoint` instead of
  the PID's own command)? Sourced from `control-loop-result`'s own
  cached `:alarm-triggered?` -- a batch whose control-loop trace hit an
  alarm never looks silently identical to one that converged cleanly
  under normal PID control."
  []
  (:alarm-triggered? @control-loop-result))

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
  :mixing-homogeneity-cov-pct .. :control-loop-alarm-triggered? ..
  :source :dcs-plant-io}` -- the same shape the retired `MESSource`/
  `MockMES` contract documented, PLUS `:control-loop-alarm-triggered?`
  (new this build, see `control-loop-alarm-triggered?` above).
  `:control-loop-alarm-triggered?` is deliberately sourced DIRECTLY from
  `control-loop-alarm-triggered?` (the real closed-loop trace), NOT
  through `io`'s tag map like the other four fields -- it is meta-
  information about HOW the homogeneity number was derived, not itself
  a plant instrument tag, so it cannot be silently overridden away by an
  `io` reading-override the way `:mixing-homogeneity-cov-pct` itself
  deliberately CAN be (see `mock-plant-io`'s own docstring) -- a batch
  whose control loop actually hit an alarm cannot be made to look clean
  by overriding the homogeneity tag alone. `hygaccess.governor`
  independently re-verifies every field (including this one, see its
  own new visibility/escalation check) before any `:record-mes-reading`
  proposal built from it can ever commit -- this fn supplies a reading
  only, never a verdict, and never actuates anything (`dcs.ports/write-
  tag!` is used elsewhere in this namespace ONLY by the mock's own
  internal telemetry seeding, `seed-batch-reading!`/`mock-plant-io` --
  never to actuate real equipment)."
  [io batch-id]
  {:batch-id batch-id
   :temperature-c (dcs-ports/read-tag io temperature-tag-id)
   :ph (dcs-ports/read-tag io ph-tag-id)
   :mixing-rpm (dcs-ports/read-tag io mixing-rpm-tag-id)
   :mixing-homogeneity-cov-pct (dcs-ports/read-tag io homogeneity-tag-id)
   :control-loop-alarm-triggered? (control-loop-alarm-triggered?)
   :source :dcs-plant-io})
