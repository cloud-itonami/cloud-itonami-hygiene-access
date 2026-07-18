# ADR-0003: MES integration contract, regulatory-submission-status tracking, and sales quote/order/fulfillment extensions

## Status

Accepted. Extends `cloud-itonami-hygiene-access` beyond ADR-0001's original 19 HARD + 1 SOFT checks and ADR-0002's 5 additional GMP checks (24 HARD + 1 SOFT total) without removing, renaming, or weakening any of them.

## Context

The human owner has repeatedly pushed to make this product design more "実務的" (practical/operational). The owner has been told, twice now, that literal chemical manufacturing, literal regulatory-agency filing, and literal real-world sales/payment execution are outside what any coding agent can or should do — manufacturing requires physical/chemical capability this session doesn't have, regulatory filing requires real legal entity/signatures/counsel, and real payment/fund-movement execution is explicitly forbidden by this operator's safety floor (never build or wire anything that would actually move real money, process a real payment, or execute a real financial transaction). The owner explicitly agreed to a software-only interpretation: model/track these three domains as data structures and state machines, with zero real execution capability. This ADR records that extension, built to a HARD constraint, not a style preference:

- **No payment gateway/processor integration of any kind.** No field, function, or dependency related to charging, transferring, settling, or processing money. A price is a plain reference number on a record, nothing more.
- **No code that could file anything with a real government/regulatory system.** Status tracking only — a human must supply the evidence (filer name, date, reference number) for any "submitted" state, and the governor must never auto-commit that transition.
- **No code that connects to or controls real manufacturing equipment.** The MES layer is an interface CONTRACT plus a deterministic MOCK implementation only, the same "administrative-only" posture `:log-production-batch` has always had.

A sibling, independently-built repo (`kotoba-lang/kami-app-hygaccess-plant`) had already shipped a real (if coarse, illustrative) finite-volume CFD mixing-tank simulation producing a `:mixing-homogeneity-cov-pct` result, referenced by this repo's own `hygaccess.registry/homogeneity-cov-threshold-pct` doc comment since ADR-0002 as a "loose EDN-map coupling, no code dependency" promise. This ADR is the first build to actually realize that coupling as a concrete adapter function, still without adding a `deps.edn` dependency on that repo.

## Decision

### Decision 1: MES integration contract layer, six new HARD checks, additive only

New namespace `hygaccess.mes`:
- `MESSource` protocol (`equipment-status`, `batch-telemetry-reading`) — what a REAL plant's Manufacturing Execution System would need to expose.
- `MockMES` — a deterministic reference implementation, NOT a real plant connection, fixed/documented default readings (or caller-supplied per-batch readings for tests/demo).
- `cfd-result->telemetry-reading` — an adapter mapping `kotoba-lang/kami-app-hygaccess-plant`'s own CFD result-record shape (verified against that repo's actual `kami-app-hygaccess-plant.mixing/run-mixing-scenario` source: `{:solver .. :mesh {..} :flow {..} :scalar {..} :process {..} :concentration-field-pct [..] :concentration-mean-pct .. :concentration-mass-conservation-defect .. :mixing-homogeneity-cov-pct-history [..] :mixing-homogeneity-cov-pct .. :fidelity :finite-volume-reference :status :screening-only}`) into this namespace's own telemetry-reading shape. That CFD scenario is isothermal and does not model temperature or pH at all, and does not propagate the tank's own agitation-drive-velocity into the RESULT record — the adapter does NOT fabricate values for fields the sibling repo's simulation does not actually produce; `:temperature-c`/`:ph`/`:mixing-rpm` come back `nil` from a CFD-sourced reading. Still no `deps.edn` dependency on that repo.

New op `:record-mes-reading` (proposal effect `:mes-reading/record`), gated by six new HARD governor checks:
1. `mes-reading-batch-not-verified` — the referenced batch must independently be `:verified?` AND `:registered?` (mirrors `batch-not-verified-violations` exactly).
2. `mes-reading-ph-implausible` — when declared, `:ph` must independently fall within `[0.0 14.0]` (the full aqueous-solution pH scale).
3. `mes-reading-temperature-implausible` — when declared, `:temperature-c` must independently fall within `[5.0 55.0]` (this actor's own documented representative window for an ambient, un-thermally-controlled small-batch dilution process across Gulf/MENA and South/Southeast Asian climates).
4. `mes-reading-rpm-implausible` — when declared, `:mixing-rpm` must independently fall within `[10.0 500.0]` (a representative small-batch agitator-speed window, matching the scale of the sibling repo's own small-batch tank model).
5. `mes-reading-homogeneity-implausible` — when declared, `:mixing-homogeneity-cov-pct` must independently fall within the PHYSICAL bound `[0.0 100.0]` — deliberately DIFFERENT from `hygaccess.registry/homogeneity-cov-threshold-pct` (the 5.0% GMP acceptance threshold checked elsewhere); a reading above 5.0% is still real, physically-plausible telemetry worth recording here.
6. `mes-reading-homogeneity-mismatch` — the interesting one: if a PRIOR MES reading already exists on file for this batch, the batch's own CURRENTLY-RECORDED IPQC self-report (`:ipqc :mixing-homogeneity-cov-pct`) must independently match that prior reading's own value within a documented 0.5-percentage-point tolerance. This CLOSES THE LOOP ADR-0002 left open: `mixing-homogeneity-cov-exceeds-threshold-violations`/`batch-release-qc-incomplete-violations` only ever re-derive the SELF-REPORTED IPQC number's own plausibility/threshold, never cross-validate it against an independent instrument/simulation reading. Once independent MES/CFD ground truth exists for a batch, its self-report can no longer silently diverge from it.

`:record-mes-reading` is the SECOND op (alongside `:log-production-batch`) added to phase 3's `:auto` set — a deliberate design decision, not merely conservative default: an MES/CFD telemetry reading is administrative logging of an already-independently-verified instrument/simulation value, not a go-to-market/financial/regulatory decision, and the six HARD checks above already gate it tightly before it can ever reach this path.

### Decision 2: Regulatory-submission-status tracking state machine, two new HARD checks, non-breaking WARN-only market-approval consistency layer

New namespace `hygaccess.regulatory`:
- Closed state machine per (market, product-type) pair: `:draft → :counsel-review → :submitted → :agency-review → :approved | :rejected | :withdrawn`. Tracks the REAL-WORLD status of the three dossier drafts already under `docs/regulatory/` (`india-cdsco-dossier.md`, `gcc-gso-dossier.md`, `asean-cosmetic-dossier.md`) — STATUS TRACKING ONLY, no HTTP client to any real agency, no auto-generated filing.
- `consequential-statuses` (`:submitted`/`:approved`/`:rejected`) require complete, non-blank human-supplied evidence (`:filed-by`/`:filing-date`/`:agency-reference`) — never defaulted or auto-generated.
- `market-approval-without-submission-warnings` — a non-breaking, WARN-only consistency scan.

New op `:record-regulatory-submission-status` (proposal effect `:regulatory-submission/transition`), gated by two new HARD governor checks:
7. `regulatory-transition-invalid` — INDEPENDENTLY looks up the (market, product-type) submission's own CURRENTLY STORED status (or `:draft` if none exists yet) and re-verifies the proposal's own claimed `:to-status` is a valid single-step transition — no skipping states, never taken on the proposal's own claim.
8. `regulatory-evidence-missing` — for a transition into a consequential status, INDEPENDENTLY verifies all three evidence fields are present as non-blank strings in the proposal's own value.

`:record-regulatory-submission-status` is NEVER a member of any phase's `:auto` set — always human-approval-gated via the existing `interrupt-before` mechanism, mirroring the permanent-manual pattern `:schedule-maintenance` already establishes, regardless of confidence.

**Non-breaking wiring choice for market-approval ↔ regulatory-submission consistency**: in a real deployment, an `:approved` regulatory-submission-status record is intended to be the actual evidence behind a market's `market-entry-approvals` boolean. Retrofitting that as a new HARD block on `:propose-market-entry` would be a BREAKING change — `hygaccess.store/sample-data!` already seeds six markets `:approved? true` (IN/BD/ID/PH/SA/AE) and 104 pre-existing tests assert against them. Instead, `hygaccess.governor/check` now ALWAYS attaches `hygaccess.regulatory/market-approval-without-submission-warnings`'s result under a new `:warnings` key in its verdict map — a non-blocking, non-escalating, purely informational signal computed on every check call, regardless of op, so it is visible to any caller/UI without holding or escalating any proposal, existing or new. `sample-data!` seeds exactly ONE backing `:approved` regulatory-submission (`REG-IN-water-purification-drops`), leaving the other five approved markets (BD/ID/PH/SA/AE) intentionally unbacked, so both branches of the warning are exercised by the seed data itself.

### Decision 3: Sales quote/order/fulfillment workflow, six new HARD checks, NO payment code

Extended `hygaccess.registry`/`hygaccess.store`/`hygaccess.governor`/`hygaccess.advisor`:
- `hygaccess.registry/sku-catalog` — a closed SKU → `{:product-type .. :price-minor ..}` ground-truth table mirroring `products.edn`'s own four SKUs (kept in sync, asserted by a `registry-test.cljc` test that parses `products.edn` directly).
- `hygaccess.registry/fulfillment-transitions` — `:pending → :packed → :shipped → :delivered`, or `:cancelled` from any PRE-SHIPPED state only (`:shipped`/`:delivered` may never transition to `:cancelled` — dispatch already happened, a post-dispatch return/refusal is a different real-world process this build does not model).

New op `:propose-sales-order` (proposal effect `:sales-order/propose`) — a quote/purchase-order record (buyer reference, SKU, quantity, price), gated by three new HARD checks:
9. `sales-order-market-not-approved` — REUSES the SAME ground-truth source/predicate `market-not-approved-violations` (check 16) already uses, not a duplicated rule.
10. `sales-order-price-mismatch` — INDEPENDENTLY recomputes whether the proposal's own claimed price matches the referenced SKU's own registered price in `sku-catalog` — ground truth, not self-report.
11. `sales-order-quantity-invalid` — the proposal's own quantity must be a positive number at or below a documented representative ceiling (100,000 units per single order).

New op `:update-fulfillment-status` (proposal effect `:sales-order/fulfillment-transition`) — transitions an EXISTING sales order's own `:fulfillment-status` field in place (no new numbered record, mirrors `:batch/upsert`'s merge-into-entity pattern), gated by three new HARD checks:
12. `fulfillment-order-not-found` — INDEPENDENTLY verifies the referenced sales-order record actually exists (an addition beyond the task brief's literal two checks, added for the same ground-truth-not-self-report discipline as every other check in this repo — a fulfillment-status update against a nonexistent order would otherwise fabricate a record this actor has no independent knowledge of).
13. `fulfillment-transition-invalid` — INDEPENDENTLY re-derives the order's OWN currently-recorded `:fulfillment-status` (or `:pending` if absent) and re-verifies the proposal's claimed `:to-status` is a valid single-step transition.
14. `fulfillment-shipment-not-on-file` — a transition to `:shipped` must INDEPENDENTLY reference an existing, already-committed `:coordinate-shipment` record (`hygaccess.store/shipment`) — you cannot mark an order shipped that has no corresponding real shipment record in the store.

Neither `:propose-sales-order` nor `:update-fulfillment-status` is ever a member of any phase's `:auto` set — always human-approval-gated, same reasoning as every consequential op in this repo.

**Explicitly NOT added**, verified: no field, function, or dependency with "pay"/"charge"/"transfer"/"settle"/"invoice-paid" or similar in its name anywhere in this build; no HTTP client or SDK reference to any payment processor; no balance/wallet/ledger-of-funds concept beyond the existing plain USDC-minor price reference numbers this repo already used for SKU pricing before this ADR (static catalog numbers, not live money). No `:invoice-issued?` administrative flag was added either — the task brief framed it as optional ("if you want"), and this build declined it to keep the surface area minimal; a plain boolean flag with zero settlement semantics remains a safe, low-risk future addition if a real deployment wants a paper-trail reference.

### Decision 4: Test/lint/demo coverage

`test/hygaccess/registry_test.cljc`, `store_contract_test.cljc`, `governor_contract_test.cljc`, `operation_test.cljc`, and `phase_test.cljc` were all extended with coverage for every new HARD-hold path and every new happy path, following each file's own pre-existing organization (pure-function unit tests in `registry_test.cljc`, `Store` protocol contract tests in `store_contract_test.cljc`, full actor-graph HARD-hold/escalate/commit tests in `governor_contract_test.cljc`, one-happy-path-per-op smoke tests in `operation_test.cljc`, phase-table structural invariants in `phase_test.cljc`). `hygaccess.sim`'s demo driver was extended with a full happy-path walk of the four new ops (including a real `:coordinate-shipment` record a `:update-fulfillment-status :shipped` transition then references — ground truth, not self-report, exercised live) plus one demo scenario per new HARD-hold check.

One PRE-EXISTING test needed a behavior-preserving edit, not a behavior change: `phase-3-auto-commits-only-no-risk-ops` in `phase_test.cljc` asserted phase 3's `:auto` set was EXACTLY `#{:log-production-batch}` — updated to `#{:log-production-batch :record-mes-reading}` per Decision 1's `:record-mes-reading` auto-eligibility choice above (the only deliberate, documented deviation from ADR-0001/0002's set of auto-eligible ops). `phase-3-writes-all-seven-ops` was renamed `phase-3-writes-matches-write-ops-set` (11 ops now, not seven) — a docstring/name accuracy fix only, the assertion itself (`= phase/write-ops (:writes ...)`) is unchanged and was never at risk.

## Consequences

(+) The MES integration contract layer gives this actor a concrete, testable seam for a future real plant connection (implement `MESSource` against a real SCADA/MES/historian) without any code change to the governor's own discipline — every field is independently re-verified regardless of source. The CFD-result adapter is the first concrete realization of the "loose EDN-map coupling" this repo's docs have promised since ADR-0002, still with zero code dependency on the sibling repo.

(+) The regulatory-submission-status state machine gives a real go-to-market coordinator a place to record the REAL progress of the three dossier drafts already prepared, with the same human-evidence discipline as every other consequential fact in this repo, while the new WARN-only layer makes the gap between "this actor's own market-entry-approvals table says approved" and "there is an on-file regulatory-submission record backing that" visible without retroactively breaking any of the 104 pre-existing tests that depend on the six already-seeded approved markets.

(+) The sales quote/order/fulfillment workflow lets this actor track a real go-to-market pipeline (quote → order → pack → ship → deliver) end-to-end, tied by ground truth to the SAME `:coordinate-shipment` records this actor has tracked since ADR-0001, with zero payment/fund-movement code anywhere — verified by direct code review of every new field/function name added in this ADR.

(-) All three additions remain simulation/status-tracking/coordination layers, not real capability: `MockMES` is not a real plant connection, `hygaccess.regulatory` files nothing with any real agency, and a committed `:propose-sales-order`/`:update-fulfillment-status` record is a draft, never an executed sale or shipment.

(-) The plausibility windows (`hygaccess.mes/ph-plausible-range`, `temperature-plausible-range-c`, `mixing-rpm-plausible-range`, `homogeneity-match-tolerance-pct`; `hygaccess.registry/max-plausible-order-quantity`) are this actor's own documented REPRESENTATIVE choices, not citations to specific instrument/metrological/business-scale specifications — open to revision if a real deployment's applicable equipment/measurement/business-scale data sets different numbers.

(-) `hygaccess.regulatory/transitions` scopes `:withdrawn` to `:agency-review` only, matching the exact chain this repo's own task brief specified; a real deployment MAY want early withdrawal from `:counsel-review`/`:submitted` too, deliberately not invented here beyond the given chain.

## Verification

- `cloud-itonami-hygiene-access`: `clojure -M:test` green — 152 tests containing 538 assertions, 0 failures, 0 errors (up from ADR-0002's 104 tests / 362 assertions; none of the original tests' assertions changed, one test's expected `:auto` set was updated per Decision 1/4 above). `clojure -M:lint` clean (0 errors, 0 warnings). `clojure -M:dev:run` demo extended to exercise all fourteen new HARD-hold scenarios directly plus a full happy-path walk of all four new ops (including a live `:coordinate-shipment` → `:update-fulfillment-status :shipped` ground-truth cross-reference), in addition to every pre-existing scenario, still settling without exception.
- The fourteen new HARD governor check names, exactly as they appear in `hygaccess.governor` and in `:rule` values on ledger facts: `mes-reading-batch-not-verified`, `mes-reading-ph-implausible`, `mes-reading-temperature-implausible`, `mes-reading-rpm-implausible`, `mes-reading-homogeneity-implausible`, `mes-reading-homogeneity-mismatch`, `regulatory-transition-invalid`, `regulatory-evidence-missing`, `sales-order-market-not-approved`, `sales-order-price-mismatch`, `sales-order-quantity-invalid`, `fulfillment-order-not-found`, `fulfillment-transition-invalid`, `fulfillment-shipment-not-on-file`.
- No payment/fund-movement, real-agency-filing, or real-equipment-control code was added — checked directly (grep across the diff for `pay|charge|transfer|settle|invoice-paid`, HTTP-client/SDK references, and equipment-actuation code): zero hits outside comments explicitly documenting their ABSENCE.
