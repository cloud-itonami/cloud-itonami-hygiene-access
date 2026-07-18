# ADR-0002: GMP-style batch-record/QA model + regulatory dossier drafts

## Status

Accepted. Extends `cloud-itonami-hygiene-access` beyond ADR-0001's original
19 HARD + 1 SOFT governor checks without removing or renaming any of them.

## Context

The human owner asked to push this product line's design toward
"実務的な製造" (practical/operational manufacturing readiness). Literally
manufacturing chemical batches is outside any coding agent's capability —
what CAN be legitimately advanced, as software + documentation, is (1) a
much more realistic GMP (Good Manufacturing Practice) -style batch-record/
QA data model, enforced with the same "ground truth, not self-report"
governor discipline ADR-0001 already established, and (2) real-world-
grounded regulatory filing DRAFT outlines per target market. This ADR
records both additions. A sibling, independently-built repo
(`kotoba-lang/kami-app-hygaccess-plant`) is separately building a CFD
mixing-tank simulation that will eventually produce a real mixing-
homogeneity metric — this repo does NOT fetch, read, or depend on that
repo's code; it only names it BY CONVENTION in a code comment on the
`:mixing-homogeneity-cov-pct` field, matching this fleet's established
"loose EDN-map coupling only, no direct code-level integration between
separate actors/repos" discipline (see e.g.
`etzhayyim/com-etzhayyim-swachh-actor`'s relationship to `junkan` for
the precedent of this pattern elsewhere in the fleet).

## Decision

### Decision 1: Three new GMP gates, five new HARD governor checks, additive only

Three new pieces of domain logic were added to `hygaccess.registry`, each
independently re-verified by `hygaccess.governor` (never trusting the
advisor's own self-report, the same discipline every existing check in
this repo already establishes):

1. **Raw-material lot verification** — a new store entity kind,
   `raw-material-lots` (`hygaccess.store`), `{:lot-number .. :active ..
   :supplier .. :coa-received? bool :coa-assay-pct <number> :verified?
   bool :registered? bool}`. A `:log-production-batch` proposal that cites
   an (effective, patch-or-existing) `:raw-material-lot-number` is gated
   by THREE new HARD checks: `raw-material-lot-not-verified-violations`
   (lot must exist, be `:verified?` AND `:registered?` — mirrors the
   existing equipment/batch verified-gate pattern exactly),
   `raw-material-lot-coa-not-received-violations` (the lot's own
   `:coa-received?` must be true), and
   `raw-material-lot-assay-implausible-violations` (the lot's own
   `:coa-assay-pct` must fall within a NEW closed per-ACTIVE plausibility
   window, `hygaccess.registry/raw-material-assay-plausibility-pct` —
   deliberately DIFFERENT from the existing `efficacy-window-pct`, which
   is the FINISHED product's own concentration window; a raw-material lot
   is an incoming concentrated SUPPLY lot, not the diluted finished
   product).
2. **In-process QC (IPQC) mixing-homogeneity** — the `:log-production-
   batch` proposal's own value schema gains an optional `:ipqc` map
   (`{:ph-check-pass? bool :assay-mid-batch-pct <number> :mixing-
   homogeneity-cov-pct <number>}`). ONE new HARD check,
   `mixing-homogeneity-cov-exceeds-threshold-violations`, INDEPENDENTLY
   re-derives the EFFECTIVE `:mixing-homogeneity-cov-pct` (patch's own
   value, else the batch's already-recorded value) and verifies it does
   not exceed `hygaccess.registry/homogeneity-cov-threshold-pct` (5.0%,
   documented as this actor's own REPRESENTATIVE acceptance threshold for
   a simple liquid-liquid dilution mixing process — general pharma/
   chemical blend-uniformity practice treats single-digit-percent CoV as
   a reasonable 'well-mixed' bar for this process class; this is NOT
   presented as a specific regulatory citation for these exact products).
   In a real deployment this coefficient-of-variation number would come
   from either a physical IPQC sample or a CFD mixing-tank simulation —
   see `kotoba-lang/kami-app-hygaccess-plant` (name-only reference, see
   Context above).
3. **Certificate of Analysis (CoA) / batch-release sign-off** — the
   batch record gains an optional `:coa` map (`{:coa-assay-result-pct
   <number> :coa-tested-by <string> :coa-date <string> :coa-pass?
   bool}`). ONE new HARD check, `batch-release-qc-incomplete-violations`,
   gates `:coordinate-shipment` IN ADDITION to ADR-0001's existing
   `batch-not-verified-violations`/`shipment-weight-exceeded-violations`:
   a batch may not be referenced by a shipment-coordination proposal
   unless it independently has `:coa {:coa-pass? true ...}` AND its own
   `:ipqc/:mixing-homogeneity-cov-pct` is within the 5.0% threshold from
   (2) — BOTH re-derived from the batch's OWN stored record via
   `hygaccess.registry/batch-release-qc-complete?`, never from the
   shipment proposal's own self-report (the shipment proposal's `:value`
   does not even carry these fields — only `:batch-id`/`:weight-kg`/
   `:destination`). This is a genuine second, independent line of
   defense: even though check (2) already blocks a bad homogeneity
   reading from ever being LOGGED via `:log-production-batch`, check (3)
   re-verifies the STORED record independently at shipment time.

All five checks are ADDITIVE to ADR-0001's 19 HARD + 1 SOFT structure —
none of the original 19 HARD checks or the confidence/high-stakes SOFT
gate were removed, renamed, or altered. The closed seven-op allowlist
(`hygaccess.governor/allowed-ops`) and seven-effect allowlist
(`allowed-proposal-effects`) are unchanged; no new op was added.

### Decision 2: Seed data stays backward-compatible

`hygaccess.store/sample-data!`'s `batch-001`/`batch-002` were extended
with a full clean GMP trail (a valid `:raw-material-lot-number`, an
in-threshold `:ipqc`, and a passing `:coa`) so every PRE-EXISTING test
exercising these batches continues to auto-commit/escalate exactly as
before — none of ADR-0001's original 87 tests / 283 assertions needed to
change. `batch-003` was deliberately left WITHOUT any of the three new
fields (nil), matching its existing UNVERIFIED/unregistered, pre-QC
status; the new checks use an "effective" (patch-or-existing,
`nil`-safe) pattern identical to the existing `invalid-active-for-
product-type-violations`/`concentration-outside-efficacy-window-
violations` checks, so an absent optional field never fabricates a new
violation. Six `raw-material-lots` were seeded: three clean lots
(`RM-LOT-NAOCL-001`/`-002` feeding `batch-001`/`batch-002`,
`RM-LOT-IPMP-001` available for IPMP-active batches) plus three
dedicated bad lots (`RM-LOT-NAOCL-003` NOT verified/registered,
`RM-LOT-NAOCL-004` CoA NOT received, `RM-LOT-NAOCL-005` an implausible
CoA assay result) for HARD-hold test coverage of the three new
raw-material checks.

### Decision 3: Regulatory dossier drafts are documentation, not governor code

`docs/regulatory/` gained three DRAFT/TEMPLATE regulatory dossier
outlines (India/CDSCO, Gulf/GSO, ASEAN/BPOM+Philippines-FDA) plus an
index README, prepared by an AI agent as a starting point for local
regulatory counsel — explicitly NOT filed submissions, NOT legal advice,
and NOT a claim that any product has actually been approved anywhere (see
`docs/regulatory/README.md`'s disclaimer, restated at the top of every
individual dossier). These are documentation artifacts only; no governor/
registry code encodes anything from them as an enforced rule, EXCEPT
where a dossier deliberately cross-references an ALREADY-existing
enforced rule for consistency (e.g. every dossier's proposed label
content is drawn verbatim from `hygaccess.registry/substantiated-claims`,
the same closed set `claim-not-substantiated-violations` already
enforces — this is citation, not new enforcement). The ASEAN dossier in
particular surfaces a genuine research finding (IPMP lacks binding,
ingredient-specific ASEAN regulation, contrasted with Japan's mature OTC
medicated-soap precedent) as a real strategic note for the human
go-to-market decision-maker, explicitly NOT implemented as governor code
— see `docs/regulatory/asean-cosmetic-dossier.md` section 3's own
disclaimer to this effect.

## Consequences

(+) The batch-record/QA data model now covers the incoming raw-material
supply chain (not just the finished batch), an in-process QC checkpoint
distinct from post-hoc off-spec-rate reporting, and a formal batch-
release sign-off gate before shipment — a materially more realistic GMP
simulation than ADR-0001's original three-field batch record
(product-type/active/concentration/weight/off-spec-rate), while
remaining, like ADR-0001, a coordination/proposal-layer simulation, NOT
a real chemical-manufacturing control system, real GMP certification, or
real regulatory authority.

(+) Three regulatory dossier drafts give a real go-to-market
decision-maker a grounded starting point per target region, cross-
referenced against this actor's own already-enforced substantiated-
claims set so a real filing's proposed label content stays consistent
with what the software would ever let this program commit to using.

(+) All additions are additive: the original 19 HARD + 1 SOFT checks,
the closed seven-op/seven-effect allowlists, and every pre-existing test
are unchanged and still green.

(-) The GMP model remains a SIMULATION — no real facility, no real
accredited testing laboratory, no real raw-material supplier
qualification backs any of this repo's `:coa`/`:ipqc`/raw-material-lot
data. The regulatory dossiers are drafts full of explicit `TODO (real
applicant)` markers, not filled-in applications; none of the three
target regions' actual approval processes have been engaged.

(-) The `homogeneity-cov-threshold-pct` (5.0%) is this actor's own
documented REPRESENTATIVE choice for a liquid-liquid dilution mixing
process, not a citation to a specific regulatory number for these exact
products — a real deployment's applicable GMP guidance may set a
different number.

## Verification

- `cloud-itonami-hygiene-access`: `clojure -M:test` green — 104 tests
  containing 362 assertions, 0 failures, 0 errors (up from ADR-0001's 87
  tests / 283 assertions; none of the original tests changed).
  `clojure -M:lint` clean (0 errors, 0 warnings). `clojure -M:dev:run`
  demo extended to exercise all five new HARD-hold scenarios directly
  (raw-material-lot-not-verified, raw-material-lot-coa-not-received,
  raw-material-lot-assay-implausible, mixing-homogeneity-cov-exceeds-
  threshold, batch-release-qc-incomplete via a not-yet-passing CoA), in
  addition to every pre-existing scenario, still settling without
  exception.
- The five new HARD governor check names, exactly as they appear in
  `hygaccess.governor` and in `:rule` values on ledger facts:
  `raw-material-lot-not-verified`, `raw-material-lot-coa-not-received`,
  `raw-material-lot-assay-implausible`,
  `mixing-homogeneity-cov-exceeds-threshold`,
  `batch-release-qc-incomplete`.
- `docs/regulatory/india-cdsco-dossier.md`,
  `docs/regulatory/gcc-gso-dossier.md`,
  `docs/regulatory/asean-cosmetic-dossier.md`, and
  `docs/regulatory/README.md` all exist, each carrying the DRAFT/
  TEMPLATE disclaimer prominently at the top (or, for the README, once
  at the top applying to the whole directory).
