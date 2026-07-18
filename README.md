# cloud-itonami-hygiene-access: Affordable hygiene/disinfectant active-ingredient commercialization

Open Business Blueprint for commercializing two low-cost hygiene/disinfectant active ingredients — **sodium hypochlorite** (次亜塩素酸ナトリウム, NaOCl) and **isopropylmethylphenol** (IPMP / o-cymen-5-ol) — as affordable products for water-scarce / poor-sanitation-infrastructure markets (India, Gulf/Arabia/MENA, South & Southeast Asia). An autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates the full commercialization chain: formulation-batch data logging, formulation/filling-line-equipment maintenance scheduling, safety-concern flagging, outbound shipment coordination, packaging design, market-entry/pricing (go-to-market), and marketing-claim substantiation.

This repository designs a forkable OSS business for BOP (base-of-the-pyramid) hygiene-product commercialization: run by a qualified operator so a program keeps its own operating and go-to-market records instead of renting a closed SaaS.

## Scope: two active ingredients, three product types, one commercialization chain

Sodium hypochlorite and isopropylmethylphenol are both long-off-patent, publicly documented, low-cost actives with real-world social-marketing precedent (WHO/CDC "Safe Water System" household water treatment; OTC antibacterial-soap formulation). This build models the commercialization chain for THREE product types built on these two actives:

- `:water-purification-drops` — sodium hypochlorite, 0.5–1.5% stock-solution concentration (representative of the WHO/CDC Safe Water System point-of-use household water-treatment precedent: a dilute NaOCl stock solution, a few drops dosed per liter of drinking water by the end user)
- `:surface-disinfectant` — sodium hypochlorite, 0.05–0.5% ready-to-use concentration (matches `etzhayyim/com-etzhayyim-yakushi`'s own already-declared window for its sodium-hypochlorite surface-use-class product, reused here for cross-fleet consistency)
- `:antibacterial-soap` — isopropylmethylphenol, 0.1–0.3% concentration (representative of real-world OTC medicated/antibacterial soap formulation concentrations)

This build also carries a GMP (Good Manufacturing Practice) -style batch-record/QA data model — raw-material-lot release verification (`hygaccess.registry`'s `raw-material-lot-*` functions + `hygaccess.store`'s `raw-material-lots` table), an in-process-QC (IPQC) mixing-homogeneity coefficient-of-variation check, and a Certificate-of-Analysis (CoA) / batch-release sign-off gate before shipment — plus three regulatory filing DRAFT/TEMPLATE dossier outlines per target region under `docs/regulatory/` (India/CDSCO, Gulf/GSO, ASEAN/BPOM+Philippines-FDA). Both are additions of REALISM to this actor's own simulation, per `docs/adr/0002-gmp-and-regulatory-dossiers.md` — see `What this actor does NOT do` below, which remains fully in force: none of this makes the batch-record model a real GMP certification, or the dossiers a real regulatory filing.

`docs/adr/0003-mes-regulatory-sales-extensions.md` extends this further, still entirely within the same simulation/proposal-layer boundary, with three more pieces of "practical/operational" realism explicitly requested by the human owner and built to a hard software-only interpretation (see `What this actor does NOT do` and `The three hard boundaries, restated` below):
- A Manufacturing Execution System (MES) integration CONTRACT layer (`hygaccess.mes`) — what a REAL plant's MES would need to expose (equipment-status polling, batch-telemetry reading), a deterministic `MockMES` reference implementation (NOT a real plant connection), and an adapter (`cfd-result->telemetry-reading`) from the sibling repo `kotoba-lang/kami-app-hygaccess-plant`'s own CFD mixing-tank simulation result shape into this actor's own telemetry-reading shape — the concrete realization of the "loose EDN-map coupling" this actor's docs have promised since that sibling repo was first named.
- A regulatory-submission-STATUS-TRACKING state machine (`hygaccess.regulatory`) — `:draft → :counsel-review → :submitted → :agency-review → :approved | :rejected | :withdrawn` per (market, product-type) pair, tracking the REAL-WORLD status of the three dossier drafts already under `docs/regulatory/`. STATUS TRACKING ONLY: no HTTP client to any real agency, no auto-generated filing — a human must independently supply the evidence (filer name, date, agency reference) for any consequential transition.
- A sales quote/order/fulfillment workflow (`:propose-sales-order` / `:update-fulfillment-status`) — buyer reference + SKU + quantity + price quote/purchase-order records and a `:pending → :packed → :shipped → :delivered`/`:cancelled` fulfillment-status state machine, tied by ground truth to an already-committed `:coordinate-shipment` record before a transition to `:shipped` clears. A price here is a PLAIN REFERENCE NUMBER on a record — NO payment gateway, processor, or fund-movement code exists anywhere in this build.

## What this actor does

Proposes **hygiene/disinfectant commercialization coordination**, not equipment operation, certification decision-making, or literal real-world sales execution:
- `:log-production-batch` — formulation-batch data logging: product-type, active-ingredient + concentration-percent, batch weight, off-spec-rate (administrative, not an operational decision)
- `:schedule-maintenance` — formulation/filling-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a chemical-hazard/toxic-co-formulation/contamination concern (always escalates)
- `:coordinate-shipment` — outbound shipment coordination proposal against a verified batch
- `:propose-packaging-design` — packaging-format + net-content proposal for a product type (BOP/hot-climate/water-scarce/informal-retail-appropriate formats only)
- `:propose-market-entry` — bundles target-country + price-point + distribution-channel + channel-partner into one go-to-market proposal (the richest op — gated by ALL of: market-approved?, price-within-affordability-ceiling?, channel-partner-licensed?)
- `:propose-marketing-claim` — a marketing/health claim for a product type (must be a member of the closed, pre-substantiated WHO/CDC-style claim set)
- `:record-mes-reading` — logs an equipment/batch telemetry reading (temperature, pH, mixing RPM, mixing-homogeneity CoV) from a `hygaccess.mes/MESSource` call (mock in this build) tied to an existing batch — administrative logging, auto-eligible like `:log-production-batch`
- `:record-regulatory-submission-status` — transitions a (market, product-type) regulatory-submission-status record through its closed state machine — STATUS TRACKING ONLY, never a real filing, always human-approval-gated
- `:propose-sales-order` — a quote/purchase-order record (buyer reference + SKU + quantity + price) — NEVER a real sale, payment, or fund movement, always human-approval-gated
- `:update-fulfillment-status` — transitions a sales order's own fulfillment status through its closed state machine, a transition to `:shipped` gated on an existing, already-committed `:coordinate-shipment` record — always human-approval-gated

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical, regulated, public-health-adjacent domain**
(formulation/filling-line equipment, chemical-mixing hazard, medical/regulatory certification, market-access approval):

- Does NOT control formulation/filling-line equipment directly
- Does NOT make plant-safety or product-safety decisions (that's the human plant/program coordinator's exclusive authority)
- Does NOT actuate the formulation/filling line (human coordinator decides)
- Does NOT decide, grant, or revoke a chemical-safety, medical, or regulatory-market-access certification — that is EXCLUSIVELY the applicable authority's (e.g. India CDSCO/BIS, a GCC conformity body, an ASEAN member-state regulator) call, never this actor's
- Is NOT a real chemical-manufacturing control system — every op is a proposal/draft, never a real formulation-line actuation
- Is NOT literal real-world sales infrastructure — a `:propose-market-entry` commit is a DRAFT go-to-market record, not an executed sale, payment, or logistics dispatch
- Any real deployment of this program would still need the applicable local regulatory approval (e.g. India CDSCO/BIS, GCC conformity bodies, ASEAN member-state regulators) before market entry — this actor's own `market-entry-approvals` ground-truth table records that approval status, it does not itself grant it
- Is NOT a real GMP-certified manufacturing facility, a real accredited testing laboratory, or a real Certificate of Analysis — the raw-material-lot/IPQC/CoA batch-record model under `hygaccess.registry`/`hygaccess.store` is a SIMULATED data shape enforced with the same governor discipline as every other check, not real facility/lab data (see `docs/adr/0002-gmp-and-regulatory-dossiers.md`)
- The `docs/regulatory/` dossiers are NOT filed submissions, NOT legal advice, and NOT a claim that any product has actually been approved by any real regulatory authority — see `docs/regulatory/README.md`'s disclaimer
- ONLY proposes/coordinates operations back-office and go-to-market drafts; all actuation, all certification decisions, and all real regulatory approvals require the appropriate human or authority
- Safety-concern flagging, market-entry proposals, and marketing-claim proposals ALWAYS escalate — never auto-decided, no confidence threshold or phase below escalation
- **Is NOT a real Manufacturing Execution System (MES) connection or real equipment control** — `hygaccess.mes/MESSource` is an INTERFACE CONTRACT, and the only implementation shipped, `MockMES`, is a fixed, documented, non-real reference implementation. No code in this repo connects to, polls, or controls real plant equipment.
- **Does NOT file anything with any real government/regulatory system** — `hygaccess.regulatory` is STATUS TRACKING ONLY over the (market, product-type) dossier drafts already under `docs/regulatory/`. There is no HTTP client to any agency API and no auto-generated submission; a human must supply the evidence (filer name, date, agency reference) for any consequential transition, and the governor never auto-commits that transition.
- **Does NOT process, charge, transfer, or settle any real payment, and does NOT move real money in any way** — `:propose-sales-order`'s price is a plain reference number on a record, checked only against `hygaccess.registry/sku-catalog`'s own static catalog price. No payment gateway, processor, SDK, or fund-movement/wallet/balance-of-funds concept exists anywhere in this codebase.

### The three hard boundaries, restated (docs/adr/0003-mes-regulatory-sales-extensions.md)

This build was extended under an explicit, hard operator-safety constraint that literal chemical manufacturing, literal regulatory-agency filing, and literal real-world payment execution are outside what this actor may ever do — not a style preference, a hard line:

1. **No payment gateway/processor integration of any kind.** No field, function, or dependency related to charging, transferring, settling, or processing money. A price is a plain reference number on a record, nothing more.
2. **No code that files anything with a real government/regulatory system.** Status tracking only — a human supplies the evidence for any "submitted"/"approved"/"rejected" transition; the governor never auto-commits it.
3. **No code that connects to or controls real manufacturing equipment.** The MES layer is an interface CONTRACT plus a deterministic MOCK implementation, clearly documented as non-real — the same posture `:log-production-batch` has always had for the rest of this actor's plant data.

## Marketing-claim substantiation — a first-class ethical guardrail

`:propose-marketing-claim` proposals are checked against a CLOSED, pre-approved, WHO/CDC-style substantiated-claims set — never taken on the advisor's self-report that a claim is "backed by data" (`hygaccess.registry/substantiated-claims`, ground truth, not self-report, the same discipline `soapmfg.registry/fragrance-allergen-labeling-incomplete?` establishes for its own regulatory-disclosure obligation). This exists specifically to prevent unsubstantiated "cures disease X" style health claims against a vulnerable population, and is treated as a first-class check, not an afterthought.

## No-toxic-co-formulation — a permanent, unconditional block

A formulation-batch record may never declare BOTH a sodium-hypochlorite active AND an acid-bearing or ammonia-bearing co-ingredient — mixing household bleach with an acid produces toxic chlorine gas, and with ammonia produces toxic chloramine gas. This is structurally unrepresentable in this actor: PERMANENT, unconditional, no phase or human-approval override path — mirroring `etzhayyim/com-etzhayyim-yakushi`'s own G22 "no-toxic-gas-formulation" gate exactly.

## Architecture

Classic governed-actor pattern (`hygaccess.operation/build`, a langgraph-clj StateGraph):
1. **`hygaccess.advisor`** (sealed intelligence node, `HygieneAccessAdvisor`): proposes decisions only, never commits
2. **`hygaccess.governor`** (independent, `Hygiene Access Operations Governor`): validates against domain rules, re-derived from `hygaccess.registry`'s pure functions, `hygaccess.mes`/`hygaccess.regulatory`'s domain logic, and `hygaccess.store`'s SSoT — never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override), elaborated into 38 concrete checks (19 from `docs/adr/0001-architecture.md` + 5 GMP checks added by `docs/adr/0002-gmp-and-regulatory-dossiers.md` + 14 MES/regulatory-submission/sales checks added by `docs/adr/0003-mes-regulatory-sales-extensions.md`):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed seven-op allowlist
     - The proposal's own `:effect` must be one of the seven propose-shaped effects (no direct formulation/filling-line-equipment control)
     - Directly actuating the formulation/filling line (`:actuate-line? true`) is a PERMANENT, unconditional block
     - Deciding or granting a chemical-safety/medical/regulatory certification (`:decide-certification? true`) is a PERMANENT, unconditional block — exclusively the applicable authority's call
     - Declaring sodium hypochlorite alongside an acid-bearing or ammonia-bearing co-ingredient (toxic chlorine-gas/chloramine-gas hazard) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a formulation-batch patch
     - No active ingredient formulated into a product type it is not authorized for (closed `[active product-type]` table)
     - No concentration falling outside its `[active product-type]`'s own closed efficacy window (G21 analog — "more concentrated is not better")
     - No physically implausible `:off-spec-rate-pct` value
     - No packaging format outside the closed BOP-appropriate set
     - No market-entry proposal targeting a country not independently marked `:approved?`
     - No market-entry proposal priced above its product type's own closed affordability ceiling
     - No market-entry proposal citing a distribution-channel partner not independently marked `:licensed?` and serving the declared channel
     - No marketing-claim proposal citing a claim outside the closed, pre-substantiated set
     - A production-batch's cited raw-material lot must independently be `:verified?` AND `:registered?` (GMP raw-material release)
     - That lot must independently have RECEIVED its own Certificate of Analysis (`:coa-received?`)
     - That lot's own CoA assay result must independently fall within its active's closed plausibility window
     - A batch's own in-process-QC (IPQC) mixing-homogeneity coefficient-of-variation must independently stay ≤5.0%
     - A shipment may not be coordinated against a batch that does not independently have a passing CoA AND an in-threshold IPQC homogeneity reading on file (the GMP "batch release" QA sign-off gate)
     - An MES/CFD telemetry reading's referenced batch must independently be verified/registered, and each sensor value (pH, temperature, mixing RPM, mixing-homogeneity CoV) must independently fall within a documented physical-plausibility window
     - If a PRIOR MES/CFD reading already exists for a batch, that batch's own currently-recorded IPQC self-report must independently match the prior reading's own homogeneity value within a documented tolerance — closes the ground-truth loop left open by the GMP checks above
     - A regulatory-submission-status transition must independently be a valid single step in the closed state machine (no skipping states), and a transition into a consequential status (`:submitted`/`:approved`/`:rejected`) must independently carry complete, non-blank human-supplied evidence (`:filed-by`/`:filing-date`/`:agency-reference`) — never defaulted or auto-generated
     - A sales order's target market must independently be `:approved?` (reusing the SAME ground-truth source as `:propose-market-entry`), its price must independently match the referenced SKU's own registered price, and its quantity must independently be physically plausible
     - A fulfillment-status update must independently reference an existing sales order, be a valid single-step transition, and (for a transition to `:shipped`) independently reference an existing, already-committed `:coordinate-shipment` record
   - WARN (non-blocking, non-escalating, informational only): for every country independently `:approved?` in `market-entry-approvals`, whether at least one `:approved` regulatory-submission record backs it — surfaces the gap for a human go-to-market coordinator without holding or escalating any proposal, existing or new (see `hygaccess.regulatory` ns docstring)
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern`, `:propose-market-entry`, and `:propose-marketing-claim` always escalate, regardless of confidence
     - `:record-regulatory-submission-status`, `:propose-sales-order`, and `:update-fulfillment-status` are NEVER in any phase's `:auto` set — always human sign-off, regardless of confidence
     - A market-entry price at/above 80% of its own ceiling is an additional high-stakes signal
     - Low-confidence proposals
3. **`hygaccess.phase`** (Phase 0→3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`/`:propose-market-entry`/`:propose-marketing-claim`/`:record-regulatory-submission-status`/`:propose-sales-order`/`:update-fulfillment-status` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); `:log-production-batch` and `:record-mes-reading` (administrative logging of already-independently-verified data) may auto-commit at phase 3 when clean
4. **`hygaccess.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)
5. **`hygaccess.mes`** (MES integration CONTRACT + mock, `docs/adr/0003-mes-regulatory-sales-extensions.md`): the `MESSource` protocol a real plant MES would implement, the `MockMES` reference implementation (NOT a real plant connection), and the `kotoba-lang/kami-app-hygaccess-plant` CFD-result adapter
6. **`hygaccess.regulatory`** (regulatory-submission-status STATE MACHINE, `docs/adr/0003-mes-regulatory-sales-extensions.md`): the closed transition table, human-evidence-completeness check, and the non-breaking market-approval-without-submission WARNING

## Commercial catalog

See `products.edn` at repo root for four representative SKUs (water-purification drops, surface disinfectant, antibacterial soap bar, antibacterial liquid soap) with formulation, packaging, pricing, target-market, marketing-claim, and distribution-channel data consistent with this actor's own closed registry tables.

## Regulatory dossier drafts

`docs/regulatory/` holds three DRAFT/TEMPLATE regulatory filing outlines (India/CDSCO, Gulf/GSO, ASEAN/BPOM+Philippines-FDA), prepared as a starting point for local regulatory counsel — see `docs/regulatory/README.md` for the index, the SKU → regulatory-track mapping, and the prominent disclaimer that applies to all three (NOT filed submissions, NOT legal advice, NOT a claim of actual approval anywhere).

## Regulatory-submission tracking

`hygaccess.regulatory` (`docs/adr/0003-mes-regulatory-sales-extensions.md`) tracks the REAL-WORLD status of the three dossier drafts above, per (market, product-type) pair, through a closed state machine: `:draft → :counsel-review → :submitted → :agency-review → :approved | :rejected | :withdrawn`. STATUS TRACKING ONLY — no HTTP client to any real agency, no auto-generated filing; a human must independently supply the evidence (`:filed-by`/`:filing-date`/`:agency-reference`) for any consequential transition, and `:record-regulatory-submission-status` is never in any phase's `:auto` set.

In a REAL deployment, an `:approved` regulatory-submission-status record here is intended to be the actual evidence behind a market's own `market-entry-approvals` boolean — but this build wires that relationship as a WARN-only, non-blocking consistency check (`hygaccess.regulatory/market-approval-without-submission-warnings`), not a new HARD dependency, so the six markets `hygaccess.store/sample-data!` already seeds `:approved? true` (and every pre-existing test asserting against them) keep passing unchanged while the gap between "approved" and "backed by an on-file submission" is now visible in `hygaccess.governor/check`'s `:warnings` (only `IN` is seeded with a backing `:approved` submission by default — see `hygaccess.store/sample-regulatory-submissions`).

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc`/`phase.cljc`/`operation.cljc`/`mes.cljc`/`regulatory.cljc` + `deps.edn` complete the module set; tests green (152 tests / 538 assertions, up from 104/362), demo runnable, langgraph-clj integration verified. See `docs/adr/0001-architecture.md` for the original architecture, `docs/adr/0002-gmp-and-regulatory-dossiers.md` for the GMP batch-record/QA model + regulatory dossier drafts, and `docs/adr/0003-mes-regulatory-sales-extensions.md` for the MES integration contract, regulatory-submission-status tracking, and sales quote/order/fulfillment workflow added on top of both.

## License

AGPL-3.0-or-later
