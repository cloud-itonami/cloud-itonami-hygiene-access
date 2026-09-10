(ns hygaccess.modbus-demo
  "DEMONSTRATION / INTEGRATION-TEST capability ONLY -- proves the real
  closed-loop control simulation
  (`hygaccess.mes/run-homogeneity-control-loop`, already backed by a real
  finite-volume CFD solve -- see ADR-0004/0005) is now externally
  observable/pollable via a real (loopback-only) Modbus TCP interface,
  wire-protocol-compatible with real SCADA/HMI tooling, while remaining
  entirely simulated -- no physical equipment is or can be reached
  through this interface. `kotoba-lang/dcs`'s `dcs.modbus` (a real
  MBAP-framed Modbus TCP server, see that repo's README) binds
  `127.0.0.1` only by default and REFUSES to bind anything else -- that
  guarantee is unchanged and unbypassed here.

  JVM only (.clj), like `dcs.modbus`/`dcs.opcua`/`dcs.plc` themselves --
  every other `.cljc` namespace in this actor is unaffected. This
  namespace is NOT required by `hygaccess.sim`/`hygaccess.governor`/
  `hygaccess.operation`/`hygaccess.advisor`, and does NOT add a new
  governed op: exposing a live protocol server for an already-computed,
  already-real control loop is an observability capability, not a
  proposal/decision `hygaccess.governor` needs to arbitrate (unlike
  `:record-mes-reading`/`:log-production-batch`, which write persisted
  records the governor gates independently of this namespace). See
  README.md 'Modbus observability demo' for the exact boundary statement.

  All registers are READ-ONLY input registers (Modbus function code 04
  only -- no FC06/16 writer is registered): this is an OBSERVABILITY
  demo. A real Modbus master can WATCH the control loop's live state; it
  cannot remotely steer it (the loop's own commanded RPM stays internal
  to `run-homogeneity-control-loop`, exactly as ADR-0005 Decision 4
  states -- 'no physical actuation pathway anywhere in this codebase').

  Register map (see `kotoba-lang/dcs`'s `dcs.modbus` docstring for the
  `:scaled-int16` encoding: `word = round(value * scale)`, unsigned
  0..65535):

    input 0  MIX-TANK-01.HOMOGENEITY-COV-PCT  scale 100  (CoV %, two decimal places)
    input 1  MIX-TANK-01.RPM                  scale 10   (agitator RPM, one decimal place)
    input 2  MIX-TANK-01.PH                   scale 100  (pH, two decimal places)
    input 3  MIX-TANK-01.TEMP                 scale 100  (deg C, two decimal places)
    input 4  MIX-TANK-01.ALARM-ACTIVE         bool16     (0/1 -- see `alarm-active-tag-id`)

  CoV is scale 100, not 1000: `homogeneity-cov-physical-range-pct`
  ([0.0 100.0]) is this actor's OWN plausibility-detection ceiling for a
  FINAL/converged reading, but a fresh, unmixed tank's very FIRST
  `run-homogeneity-control-loop` iteration can genuinely read a CoV well
  above 100% (two barely-mixed regions of very different concentration
  legitimately produce a high coefficient of variation before agitation
  has done any work) -- scale 100 keeps every real value in this loop's
  own trajectory representable (max 655.35) without silently clamping a
  real early-iteration reading, at the cost of one fewer decimal place
  than `dcs.modbus`'s own CoV x1000 illustrative convention. Two decimal
  places is still ample precision against the 5.0% GMP threshold this
  loop targets."
  (:require [dcs.modbus :as modbus]
            [dcs.ports :as dcs-ports]
            [hygaccess.mes :as mes]))

(def alarm-active-tag-id
  "An ad-hoc, demo-only tag id -- NOT one of `hygaccess.mes/mixing-tank-
  system`'s four registered `dcs.model` tags (that system's own docstring
  is explicit: pH/temperature/RPM/homogeneity are the whole tag set). The
  real closed-loop trace's per-iteration `:alarm-active?` fact (see
  `hygaccess.mes/run-homogeneity-control-loop`'s `:trace`) has no
  existing IFieldIO tag of its own to live on -- this id is this demo's
  own bridge for making that fact externally observable too, written by
  the same `on-iteration` callback that mirrors the other four values
  (see `poll-tags!` below). Any `dcs.ports/IFieldIO` this demo is pointed
  at (in practice, `hygaccess.mes/mock-plant-io`'s underlying atom map)
  accepts an arbitrary tag id via `write-tag!`/`read-tag` -- it is not
  restricted to `mixing-tank-system`'s own registered set."
  "MIX-TANK-01.ALARM-ACTIVE")

(defn register-map
  "The Modbus register map this demo serves -- see namespace docstring."
  []
  (-> (modbus/register-map)
      (modbus/add-input (modbus/input-register 0 mes/homogeneity-tag-id (modbus/scaled-int16 100 false)))
      (modbus/add-input (modbus/input-register 1 mes/mixing-rpm-tag-id (modbus/scaled-int16 10 false)))
      (modbus/add-input (modbus/input-register 2 mes/ph-tag-id (modbus/scaled-int16 100 false)))
      (modbus/add-input (modbus/input-register 3 mes/temperature-tag-id (modbus/scaled-int16 100 false)))
      (modbus/add-input (modbus/input-register 4 alarm-active-tag-id modbus/bool16))))

(defn poll-tags!
  "Mirror one `run-homogeneity-control-loop` `trace-entry` (see that fn's
  `on-iteration` callback) onto `io`'s tags -- CoV/RPM straight from the
  entry, pH/temperature as ACTUALLY USED that tick (`:ph`/
  `:temperature-c`, honoring any test override), and the ad-hoc
  `alarm-active-tag-id` from `:alarm-active?`. Intended to be partially
  applied as the `on-iteration` callback:
  `#(poll-tags! io %)`. Returns `io`."
  [io trace-entry]
  (dcs-ports/write-tag! io mes/homogeneity-tag-id
                          (get-in trace-entry [:sensor-reading :mixing-homogeneity-cov-pct]))
  (dcs-ports/write-tag! io mes/mixing-rpm-tag-id (:command-issued trace-entry))
  (dcs-ports/write-tag! io mes/ph-tag-id (:ph trace-entry))
  (dcs-ports/write-tag! io mes/temperature-tag-id (:temperature-c trace-entry))
  (dcs-ports/write-tag! io alarm-active-tag-id (boolean (:alarm-active? trace-entry)))
  io)

(defn start-server!
  "Start the observability Modbus server (see namespace docstring) bound
  to `io` (a `dcs.ports/IFieldIO` -- in practice
  `hygaccess.mes/mock-plant-io`'s underlying instance, the SAME `io` a
  caller then drives `run-homogeneity-control-loop`'s `:on-iteration`
  writes onto via `poll-tags!`). opts as `dcs.modbus/start-server!`
  (`:host`, default loopback-only-enforced; `:port`, default
  `dcs.modbus/default-port`). Returns a handle for `stop-server!`."
  ([io] (start-server! io {}))
  ([io opts] (modbus/start-server! io (register-map) opts)))

(def stop-server!
  "Stop a server started by `start-server!`."
  modbus/stop-server!)
