# ADR-0001: HygieneAccessAdvisor ⊣ Hygiene Access Operations Governor architecture

## Status

Accepted. `cloud-itonami-hygiene-access` promoted directly to
`:implemented` in this repository, following the verified
fresh-scaffold protocol established by prior actors in the
`cloud-itonami` fleet (most directly `cloud-itonami-isic-2023`'s
`soapmfg.*` and `cloud-itonami-isic-2029`'s `adhesivemfg.*`).

This ADR is this repository's own internal architecture decision
record. A LATER, separate superproject-side ADR (in
`com-junkawasaki/root`) records the decision to register this repo
into the west manifest and fleet; that ADR references this one, not
the reverse.

Extended, additively, by `docs/adr/0002-gmp-and-regulatory-dossiers.md`
(GMP-style raw-material-lot/IPQC/CoA batch-release checks + regulatory
dossier drafts) — none of this ADR's 19 HARD + 1 SOFT checks were
removed or renamed by that extension.

## Context

`cloud-itonami-hygiene-access` publishes an OSS blueprint for
commercializing two low-cost, long-off-patent, publicly documented
hygiene/disinfectant active ingredients — sodium hypochlorite (NaOCl)
and isopropylmethylphenol (IPMP / o-cymen-5-ol) — as affordable
products for water-scarce / poor-sanitation-infrastructure markets
(India, Gulf/Arabia/MENA, South & Southeast Asia). Unlike its closest
`cloud-itonami-isic-*` plant-operations siblings, this actor's scope
is not a single manufacturing plant's back-office coordination alone —
it covers the FULL commercialization chain: formulation-batch logging,
formulation/filling-line-equipment maintenance, safety-concern
flagging, shipment coordination, packaging design, market-entry/
pricing (go-to-market), and marketing-claim substantiation. This is
the first actor in the fleet to reach all the way to go-to-market
(pricing, distribution channel, marketing claim), not just
manufacturing operations.

The closest domain analogs are `cloud-itonami-isic-2023` (soap/
detergent/cosmetics plant operations, `soapmfg.*` — closest for the
"ground truth, not self-report" fragrance-allergen-labeling-
completeness pattern this build's own marketing-claim-substantiation
check mirrors) and `cloud-itonami-isic-2029` (adhesives, `adhesivemfg.*`
— closest for the two-permanent-block pattern: a physical-equipment
block (`:actuate-line?`) plus a domain-specific authority-boundary
block (`certification-decision-blocked`), and the purity-spec-FLOOR
check this build's own efficacy-WINDOW check generalizes). The
efficacy-window (G21 analog) and no-toxic-co-formulation (G22 analog)
domain facts themselves are drawn directly from
`etzhayyim/com-etzhayyim-yakushi`'s own Wave 2 disinfectant-
formulation gates, reused verbatim where the two actors' scopes
overlap (the `sodium-hypochlorite` `:surface-disinfectant` window,
0.05–0.5%, is IDENTICAL to yakushi's own already-declared window for
cross-fleet consistency) and extended with genuinely new
domain-specific gates a BOP go-to-market actor needs that neither
sibling has: market-entry-country-approval, affordability-price-
ceiling, and distribution-channel-partner-licensing.

## Decision

### Decision 1: Self-contained domain logic (no external hygiene/disinfectant-commercialization capability library to wrap)

Like `soapmfg.registry`/`adhesivemfg.registry`, this vertical has NO
pre-existing `kotoba-lang/hygaccess`-style capability library to wrap
(verified: no such repo exists). The equipment/batch-verification,
shipment-weight, product-type, active-ingredient-authorization,
efficacy-window, no-toxic-co-formulation, packaging-format,
market-entry-approval, affordability-ceiling, channel-partner-
licensing, and marketing-claim-substantiation functions live as pure
functions in `hygaccess.registry` and are re-verified independently by
`hygaccess.governor` — the same "ground truth, not self-report"
discipline established across prior actors.

### Decision 2: Two active ingredients, three product types, one closed efficacy-window table shared across both

Rather than model an open-ended catalog of disinfectant actives, this
build picks the exact two actives + three product types the task
specifies, with concentration windows taken directly from real-world
precedent rather than re-derived: sodium hypochlorite in
`:water-purification-drops` (0.5–1.5% stock solution, WHO/CDC "Safe
Water System" household point-of-use precedent) and
`:surface-disinfectant` (0.05–0.5%, identical to
`etzhayyim/com-etzhayyim-yakushi`'s own declared window, reused
verbatim); isopropylmethylphenol in `:antibacterial-soap` (0.1–0.3%,
representative OTC medicated-soap formulation concentration). The
`[active product-type] -> [min max]` table
(`hygaccess.registry/efficacy-window-pct`) does DOUBLE DUTY as both
the efficacy-window check AND the closed authorization table for which
active may be formulated into which product type — an
`[active product-type]` pair absent from the table is rejected before
concentration is even examined (`invalid-active-for-product-type`,
distinct from `concentration-outside-efficacy-window`).

### Decision 3: Coordination, not control, not a certification/regulatory authority, and NOT real sales infrastructure — scope boundary at the back-office and go-to-market-draft level

This actor is **strictly back-office coordination and go-to-market
DRAFTING**, not real manufacturing control, not real certification/
regulatory authority, and not real sales execution. It does NOT:
- Control formulation/filling-line equipment directly
- Make plant-safety or product-safety decisions (exclusive to the
  human plant/program coordinator)
- Actuate the formulation/filling line
- Decide, grant, or revoke a chemical-safety, medical, or
  regulatory-market-access certification (exclusive to the applicable
  authority, e.g. India CDSCO/BIS, a GCC conformity body, an ASEAN
  member-state regulator)
- Execute a real sale, payment, or freight dispatch — a committed
  `:propose-market-entry`/`:coordinate-shipment` record is a DRAFT,
  not an executed transaction

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human coordinator
approval. This is not a replacement for the coordinator's authority,
a certification/regulatory authority's process, or real sales
infrastructure — it is a proposal-screening and documentation layer.

**CRITICAL SAFETY + ETHICS BOUNDARY**: this domain combines a
safety-critical hazard (sodium hypochlorite + acid/ammonia toxic-gas
mixing) with a public-health ethics hazard (unsubstantiated marketing/
health claims against a vulnerable, water-scarce/poor-sanitation-
infrastructure population). Safety-concern flagging, market-entry
proposals, and marketing-claim proposals NEVER auto-commit. All
escalate immediately to human review, and no proposal may ever attempt
a toxic co-formulation or a certification decision, regardless of
confidence or phase.

### Decision 4: Safety-concern, market-entry, and marketing-claim escalation — always human sign-off

`:flag-safety-concern` (chemical-hazard/toxic-co-formulation/
contamination concern) ALWAYS escalates, never auto-commits — a
circuit-breaker, not a threshold. `:propose-market-entry` and
`:propose-marketing-claim` are likewise ALWAYS high-stakes
(`hygaccess.governor/high-stakes` includes `:coordination/
new-market-entry` and `:coordination/marketing-claim-change`
unconditionally) AND never members of any phase's `:auto` set — two
independent layers agree that a go-to-market decision and a public
health claim always require a human, regardless of how governor-clean
the proposal is.

### Decision 5: Two independent verified/registered gates (equipment AND batch), plus three genuinely new go-to-market ground-truth gates

Like `cloud-itonami-isic-2029`, this vertical has TWO entity kinds each
gating a different op: `:schedule-maintenance` independently verifies
the referenced **equipment** unit's own `:verified?`/`:registered?`
fields; `:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields, plus an
independent shipment-weight recompute. This build additionally
introduces THREE go-to-market ground-truth gates no prior
`cloud-itonami-isic-*` actor needed, because no prior actor reached
market-entry/pricing/claims:
- `market-not-approved` — a `:propose-market-entry` proposal's own
  target country must be independently marked `:approved?` true in
  the store's `market-entry-approvals` table (never taken on the
  advisor's self-report). This does NOT grant real regulatory market
  access; it records that this actor's own operator has confirmed the
  applicable local approval exists.
- `price-above-ceiling` — a genuinely new domain-specific check for a
  BOP-market actor: the proposal's own `:price-minor` must not exceed
  its product type's own closed affordability-ceiling table
  (`hygaccess.registry/price-ceiling-minor`, representative
  social-marketing pricing, documented per-number in the registry
  comment).
- `channel-partner-not-licensed` — a `:propose-market-entry`
  proposal's own channel-partner must be independently marked
  `:licensed?` true AND actually serve the declared channel
  (`hygaccess.registry/channel-partner-ready?`), the closest analog
  the task's own reference materials describe as a
  "vendor-eligibility"-style HARD check.

### Decision 6: Two permanent blocks (formulation/filling-line actuation, certification decision), plus a THIRD structurally distinct permanent block (no-toxic-co-formulation)

Mirroring `cloud-itonami-isic-2029`'s two-permanent-block pattern
(`line-actuate-blocked-violations` / `certification-decision-blocked-
violations`), this build carries both blocks forward with the same
severity (`line-actuate-blocked`, `certification-decision-blocked`,
both boolean-flag-triggered, unconditional, no phase or human-approval
override). It ALSO adds a THIRD permanent block of a structurally
DIFFERENT shape: `no-toxic-co-formulation-blocked` is not a boolean
flag the caller sets, but a SET-INTERSECTION ground-truth check over
the batch's own effective `:active` and `:co-ingredients` fields
(`hygaccess.registry/toxic-co-formulation?`) — mirroring
`etzhayyim/com-etzhayyim-yakushi`'s own G22 "no-toxic-gas-formulation"
EXACTLY (sodium hypochlorite + any acid or ammonia is "constitutionally
unrepresentable"). Three permanent, unconditional, non-overridable
blocks instead of two is this vertical's central architectural
adaptation.

### Decision 7: Marketing-claim substantiation as a first-class ethical guardrail, not an afterthought

`claim-not-substantiated-violations` mirrors
`soapmfg.registry/fragrance-allergen-labeling-incomplete?`'s "ground
truth, not self-report" discipline, applied to a closed per-product-
type set of WHO/CDC-style substantiated claims
(`hygaccess.registry/substantiated-claims`). Any proposed claim NOT a
member of that closed set is HARD-blocked. This exists specifically to
prevent unsubstantiated "cures disease X" style health claims against
a vulnerable, water-scarce/poor-sanitation-infrastructure population —
treated here as a first-class governor check with its own dedicated
test coverage, not folded into a generic validation catch-all.

### Decision 8: HARD invariants (no override)

Four HARD governor invariants (elaborated into 19 concrete checks in
`hygaccess.governor`, mirroring `cloud-itonami-isic-2029`'s own
elaboration of its four HARD invariants into 13 concrete checks, scaled
up for this actor's seven-op go-to-market scope) block proposals and
cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment)
   must be independently verified/registered before any action is
   taken against it, and a shipment's weight must independently
   recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment
   control)
3. Direct formulation/filling-line-equipment control, line actuation,
   a certification-decision attempt, or a toxic sodium-hypochlorite +
   acid/ammonia co-formulation is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment`/`:propose-packaging-design`/
   `:propose-market-entry`/`:propose-marketing-claim` only

Additionally, three go-to-market-specific HARD gates (Decision 5) and
the marketing-claim-substantiation gate (Decision 7) block a
`:propose-market-entry`/`:propose-marketing-claim` proposal from ever
committing against an unapproved market, an above-ceiling price, an
unlicensed channel partner, or an unsubstantiated claim.

## Consequences

(+) Affordable hygiene/disinfectant commercialization for
water-scarce/poor-sanitation-infrastructure markets now has a
documented, governed, auditable coordination layer spanning
formulation through go-to-market that funnels all decisions through
independent validation before human approval.

(+) The "coordination, not control, not a certification/regulatory
authority, not real sales infrastructure" boundary is explicit in
code: all `:effect :propose`, all real-world actuation requires human
coordinator sign-off, and no path exists for this actor to decide a
certification or execute a real sale.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into 19 concrete governor checks) protect against scope creep into
unauthorized equipment operation, line actuation, certification-
decision-making, toxic co-formulation, unapproved market entry,
above-ceiling pricing, unlicensed channel partnering, or
unsubstantiated marketing claims. Safety concerns, market-entry
proposals, and marketing-claim proposals are circuit-breakers, not
thresholds.

(-) Still a simulation/proposal layer, not real manufacturing control,
a real regulatory/certification authority, or real sales
infrastructure. Equipment actuation and formulation/filling-line
operation remain human-controlled via external channels; regulatory
market-access approval remains a real-world authority's process
entirely outside this actor; and a committed market-entry/shipment
record is a draft, not an executed transaction.

(-) No integration with real formulation/plant-management databases,
regulatory-approval databases, or payment/logistics systems — this is
a standalone coordinator blueprint. The closed `price-ceiling-minor`,
`substantiated-claims`, and `valid-market-countries` tables are
representative, illustrative subsets, not exhaustive multi-
jurisdiction, multi-regulator specification databases.

## Verification

- `cloud-itonami-hygiene-access`: `clojure -M:test` green (all tests
  pass; see the superproject ADR for the exact
  `Ran N tests containing M assertions, 0 failures, 0 errors` output,
  verified from an independent fresh clone), `clojure -M:lint` clean,
  `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, equipment-not-verified, batch-not-verified,
  shipment-weight-exceeded, line-actuate-blocked, certification-
  decision-blocked, no-toxic-co-formulation-blocked, already-
  scheduled, invalid-product-type, invalid-active-for-product-type,
  concentration-outside-efficacy-window, invalid-off-spec-rate,
  invalid-packaging-format, market-not-approved, price-above-ceiling,
  claim-not-substantiated, channel-partner-not-licensed).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
- `:itonami.blueprint/governor` is `:hygiene-access-operations-
  governor`, grep-verified UNIQUE fleet-wide
  (`gh search code "hygiene-access-operations-governor" --owner
  cloud-itonami`, zero hits before this repo was created); so is the
  `hygaccess` namespace prefix (`gh search code "hygaccess" --owner
  cloud-itonami`, zero hits).
