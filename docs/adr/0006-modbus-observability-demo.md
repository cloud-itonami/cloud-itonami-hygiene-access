# ADR-0006: A real (loopback-only, read-only) Modbus TCP observability demo for the closed-loop control simulation

## Status

Accepted. Extends `hygaccess.mes` (ADR-0005) with one new, additive `.clj` namespace (`hygaccess.modbus-demo`) and one new, additive, backward-compatible optional argument (`:on-iteration`) to `hygaccess.mes/run-homogeneity-control-loop`. No op name, no governor check, no existing public var/fn signature was removed, renamed, or weakened. Not a governed op, not part of this actor's advisor/governor/phase/operation graph.

## Context

`docs/adr/0005-closed-loop-control-simulation.md` made `hygaccess.mes/run-homogeneity-control-loop` a real closed-loop control simulation (sensor read → PID compute → command → CFD state update, iterated) and, in its Decision 4, stated the boundary as plainly as this repo's other hard-boundary statements: *"No GPIO, no serial, no Modbus, no OPC-UA, no PLC client, no equipment-control code of any kind exists anywhere in this codebase or its `kami-app-hygaccess-plant`/`dcs` dependencies, and none is intended."* That boundary is restated, sharpened, and kept true below — not weakened.

The sibling library `kotoba-lang/dcs` has since added a protocol-SIMULATION layer (`dcs.gpio`/`dcs.modbus`/`dcs.opcua`/`dcs.plc`) on top of its existing `dcs.ports/IFieldIO` seam: real wire-format servers (a real Modbus TCP server with real MBAP framing, function codes 03/04/06/16, built on a real independent Modbus-for-Java library) that are, at the same time, backed entirely by software state and bound to `127.0.0.1` only by construction (see that repo's README "Protocol-simulation layer" section). The owner's ask for this build: demonstrate that this actor's ALREADY-REAL closed-loop control simulation is reachable through one of those protocol simulators, as a concrete end-to-end proof that the digital twin is wire-protocol-compatible with real SCADA/HMI tooling — while remaining entirely simulated.

## Decision

### Decision 1: `hygaccess.modbus-demo` — a new, additive, non-governed `.clj` namespace

A new namespace, `hygaccess.modbus-demo`, wraps `kotoba-lang/dcs`'s `dcs.modbus` to serve `hygaccess.mes`'s four `dcs.model` tags (pH, temperature, mixing RPM, mixing-homogeneity CoV) plus one ad-hoc, demo-only alarm-active flag, ALL as **read-only** Modbus input registers (function code 04 only — no write register is registered for any of them). This is a *watch*, not a *steer*: a real Modbus master connecting to this server can observe the control loop's live state; it has no path to command it. The control loop's own commanded RPM stays exactly where ADR-0005 put it — computed and applied internally by `run-homogeneity-control-loop`, never through `dcs.ports/write-tag!`, never through this new Modbus seam either.

`hygaccess.modbus-demo` is JVM only (`.clj`, matching `dcs.modbus`/`dcs.opcua`/`dcs.plc` themselves — every other namespace in this actor remains portable `.cljc`), and is not required by `hygaccess.sim`/`hygaccess.governor`/`hygaccess.operation`/`hygaccess.advisor`. Exposing a live protocol server for an already-computed, already-real control loop is a demonstration/integration-test capability, not a proposal or decision `hygaccess.governor` needs to arbitrate — nothing about `:record-mes-reading`'s or `:log-production-batch`'s own governed commit path changes.

### Decision 2: `run-homogeneity-control-loop` gains one optional, backward-compatible `:on-iteration` callback

`hygaccess.mes/run-homogeneity-control-loop` gains `:on-iteration` (default `nil`, meaning zero behavior change for every existing call site and every pre-ADR-0006 test), a `(fn [trace-entry])` invoked once per iteration with that iteration's own `trace-entry` — the same map shape that has always been assembled into `:trace`. The loop's own control flow, return value, determinism, and existing `:trace` audit record are completely unchanged; the callback is a pure observation hook. `hygaccess.modbus-demo/poll-tags!` is the intended callback body: it mirrors that iteration's CoV/RPM/pH/temperature/alarm-active values onto a `dcs.ports/IFieldIO` (in practice, `hygaccess.mes/mock-plant-io`'s own instance) via `dcs.ports/write-tag!` — so a Modbus client polling `dcs.modbus`'s server (bound to that SAME `IFieldIO`) between iterations sees the control loop's state actually advancing, not a frozen snapshot.

### Decision 3: register-map encoding — CoV at scale 100, not `dcs.modbus`'s own illustrative scale 1000

`dcs.modbus`'s own README/docstring illustrates a `CoV × 1000` register convention for a small, near-converged CoV (e.g. 0.032 → 32). This actor's real closed-loop trace's very FIRST iteration (a freshly-charged, unmixed tank) legitimately reads a CoV well above `hygaccess.mes/homogeneity-cov-physical-range-pct`'s own [0, 100] plausibility ceiling for a *final* reading — two barely-mixed regions of different concentration produce a real, physically meaningful high coefficient of variation before agitation has done any work (empirically observed during this ADR's own integration testing: iteration 0 read ~300%). A `scale 1000` encoding would silently clamp that real, genuinely-computed early value to the register's max representable word (65535 → 65.535) — i.e., **the wire read would silently stop matching internal reality**, exactly the kind of silent mismatch this repo's whole "ground truth over self-report" discipline exists to prevent, now applied to a wire-protocol encoding choice instead of a governor check. `hygaccess.modbus-demo/register-map` uses `scale 100` for the CoV register instead (representable range 0–655.35, two decimal places of resolution — still ample precision against the 5.0% GMP threshold this loop targets), so every real value the loop can genuinely produce stays exactly representable.

### Decision 4: the fourth hard boundary, restated again — real wire protocol, zero actuation, zero non-loopback exposure

ADR-0005's Decision 4 said "no Modbus... anywhere in this codebase." That is no longer literally true — `hygaccess.modbus-demo` exists, and it genuinely speaks real Modbus TCP wire framing. What has NOT changed, restated as plainly as ADR-0005's own boundary statement:

- **Read-only.** Every register this demo serves is a `:dcs/input` register (FC04). No FC06/16 writer is registered for any of them — real Modbus itself has no "write input register" function code, so this is enforced by the protocol, not merely by this demo's own configuration choice.
- **Loopback-only, unconditionally.** `dcs.modbus/start-server!` refuses to bind anything other than `127.0.0.1`/`localhost`/`::1` — this demo does not, and cannot, override that.
- **No physical equipment is or can be reached through this interface.** Every value served is read from the SAME in-memory `dcs.ports/IFieldIO` `hygaccess.mes/mock-plant-io` has always been — a real Modbus client polling this server is watching this software's own digital twin, at one further remove, exactly as `hygaccess.governor`'s own "never trust the advisor's self-report, only ground truth" discipline already treats every other reading in this codebase.
- **The control loop's own command path is entirely unchanged.** `run-homogeneity-control-loop` still computes and applies its RPM command internally; `:on-iteration` is a read-side mirror, not a new input.

Restated for `README.md`'s own "four hard boundaries" list (updated, not weakened): *no GPIO, no serial, no OPC-UA, no PLC client, no equipment-control code of any kind exists anywhere in this codebase — and the one real, loopback-only, read-only Modbus TCP interface that DOES now exist is a wire-protocol-compatible WINDOW onto this software's own digital twin, never a path INTO it.*

## Consequences

(+) A concrete, independently-verifiable, end-to-end proof that this actor's already-real closed-loop control simulation is wire-protocol-compatible with real SCADA/HMI tooling — not merely an internal function-call assertion: `test/hygaccess/modbus_integration_test.clj` polls a real, separate `com.digitalpetri.modbus.client.ModbusTcpClient` connection at every control-loop iteration and asserts the externally-read value matches the loop's own internal state for that exact tick.

(+) `run-homogeneity-control-loop`'s new `:on-iteration` hook is a small, generically useful extension point (any caller — a future dashboard, a different protocol demo — can observe the loop's live trajectory) that cost zero behavior change to the loop itself.

(+) The CoV-encoding investigation (Decision 3) is itself a small piece of real domain knowledge this build didn't previously have written down: a fresh-tank CoV reading can genuinely exceed the [0,100] plausibility ceiling this actor's OWN governor uses for a *final* MES reading — worth keeping in mind if a future change ever tried to apply that same ceiling to an in-progress control-loop trace entry instead of only a final/logged reading.

(-) `hygaccess.mes.cljc` picks up one new optional keyword argument; every existing caller (including every pre-ADR-0006 test) is unaffected, verified by the full pre-existing test suite passing unmodified.

(-) This build now has one JVM-only (`.clj`) source file where every other namespace is portable `.cljc` — an explicit, documented exception (matches `kotoba-lang/dcs`'s own `dcs.runner`/`dcs.modbus`/`dcs.opcua`/`dcs.plc` precedent of host-side-only namespaces), not a silent inconsistency.

## Verification

- `cloud-itonami-hygiene-access`: `clojure -M:test` green — 183 tests / 663 assertions (up from ADR-0005's 181/631; two new tests, `hygaccess.modbus-integration-test`). `clojure -M:lint` clean (0 errors, 0 warnings).
- `external-modbus-poll-matches-internal-control-loop-state-at-every-tick` — starts `hygaccess.modbus-demo`'s server bound to a fresh `mock-plant-io`, drives `run-homogeneity-control-loop` (small-mesh fixture, `mes_test.cljc`'s own `small-tank` convention, `:max-iterations 4` for test speed), and on EVERY iteration: mirrors that tick's trace-entry onto the IO via `poll-tags!`, then independently reads back all five registers through a real, separately-connected `ModbusTcpClient` and asserts each decoded value matches the trace-entry's own internal value (within each register's own encoding resolution) — CoV, RPM, pH, temperature, and alarm-active, at every single tick, plus the final reading.
- `demo-registers-are-read-only-observability-only-never-remote-control` — asserts the register map contains zero `:dcs/holding` (writable) registers, only `:dcs/input` (read-only), and that its tag ids line up exactly with `hygaccess.mes`'s own registered tag ids (not ad-hoc lookalike strings).
- No payment/fund-movement, real-agency-filing, or real-equipment-control code was added or touched — `hygaccess.modbus-demo` only reads from and mirrors onto an in-memory `dcs.ports/IFieldIO`; `dcs.modbus` itself is a real, independently-tested (`kotoba-lang/dcs`'s own test suite) loopback-only wire server with no default network exposure beyond `127.0.0.1`.
