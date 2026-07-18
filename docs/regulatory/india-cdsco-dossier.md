# India (CDSCO/BIS) regulatory dossier outline — DRAFT / TEMPLATE

> **DRAFT / TEMPLATE — prepared by an AI coding agent as a starting point for
> local regulatory counsel. This is NOT a filed submission, NOT legal advice,
> and NOT a claim that any `cloud-itonami-hygiene-access` product has
> actually been approved, licensed, registered, or cleared by CDSCO, BIS, or
> any other Indian authority.** Every "TODO" below marks a field a real
> applicant must fill with real facility, testing, and personnel data before
> this could become an actual application. See `docs/regulatory/README.md`
> for the disclaimer that applies to all three dossiers in this directory,
> and the repository README's "What this actor does NOT do" section — this
> actor's own `market-not-approved-violations`/`certification-decision-
> blocked-violations` governor checks structurally refuse to let this actor
> (or any advisor behind it) claim, decide, or grant a real regulatory
> approval; this document is a coordination aid for the HUMAN who will
> actually file, not a substitute for CDSCO/BIS or for counsel.

## 1. Regulatory classification (why this dossier has two tracks, not one)

India does not regulate this product line under one regime:

- **Sodium-hypochlorite SKUs (`:water-purification-drops`,
  `:surface-disinfectant`)** — disinfectants are regulated as **Drugs**
  under the **Drugs and Cosmetics Act, 1940** (and Rules, 1945). The
  regulator is **CDSCO** (Central Drugs Standard Control Organisation),
  headed by the **Drugs Controller General of India (DCGI)**. This is the
  DRUG track.
- **IPMP antibacterial-soap SKUs (`:antibacterial-soap`, both the bar and
  liquid-soap SKUs in `products.edn`)** — soap-format antibacterial products
  fall under India's **cosmetics regime**, also CDSCO-administered but under
  a *distinct* Cosmetics Rules pathway (cosmetics manufacturing licence /
  BIS cosmetic standards), not the Drug track above. **Do not conflate the
  two tracks** — a single combined filing across both product families is
  not the correct approach; each SKU family needs its own track's dossier.

This document outlines the DRUG track (sodium-hypochlorite SKUs) in detail
in sections 2-6, then separately flags the cosmetics track for the IPMP SKUs
in section 7 without conflating the two.

## 2. Track A — sodium-hypochlorite disinfectant SKUs (Drug track)

### 2.1 Product identity / composition declaration

| Field | Draft value (from `products.edn` / `hygaccess.registry`) | Source of truth in this repo |
|---|---|---|
| Product name | Hygiene Access 次亜塩素酸ナトリウム水道水浄化用ドロップ 1.0% | `products.edn` `int.hygaccess.water-purification-drops` |
| Active ingredient | Sodium hypochlorite (NaOCl), CAS 7681-52-9 | `hygaccess.registry/known-actives` |
| Declared stock concentration | 1.0% w/v (within the 0.5–1.5% closed efficacy window for this product type) | `hygaccess.registry/efficacy-window-pct` |
| Applicable product standard | **BIS IS 11673 : Part 2 : 2019 — "Sodium Hypochlorite Solution — Specification, Part 2: Water Treatment Use (Second Revision)"** (spot-checked against BIS/archive.org listings as of this repo's build date) | N/A — external standard |
| Second sodium-hypochlorite SKU | Hygiene Access 次亜塩素酸ナトリウム環境表面消毒剤 0.1% (surface disinfectant, ready-to-use) — falls under the general-purpose-disinfectant track, not IS 11673 Part 2 specifically (that standard is water-treatment-use only); TODO: confirm whether IS 11673 Part 1 (household/industrial use) or a separate BIS disinfectant standard is the applicable reference for this SKU | `products.edn` `int.hygaccess.surface-disinfectant` |
| Raw-material lot traceability | This repo's own `hygaccess.registry`/`hygaccess.store` GMP raw-material-lot model (see below) | `hygaccess.registry/raw-material-assay-plausibility-pct`, `hygaccess.store` `raw-material-lots` |

TODO (real applicant): confirm current edition/amendment status of IS 11673
Part 2:2019 directly with BIS before citing it in a live filing (standards
are periodically revised; this dossier cites the edition identified at
research time and should not be trusted as current without a fresh check).

### 2.2 Manufacturing-site / GMP declaration

A real CDSCO filing requires the applicant to substantiate manufacturing
conditions with **real facility data** — this repo cannot and does not
provide that. What this repo DOES provide, as the internal QA basis a real
applicant would build the facility-level GMP declaration on top of, is the
`cloud-itonami-hygiene-access` actor's own governed data model added in this
build:

- **Raw-material lot release** (`hygaccess.registry/raw-material-lot-
  release-eligible?`) — every production batch must cite a raw-material
  supply lot that is independently verified, registered, has RECEIVED its
  own Certificate of Analysis, and whose CoA assay result is plausible for
  its active (`hygaccess.registry/raw-material-assay-plausibility-pct`).
  Enforced as a HARD governor check (`raw-material-lot-not-verified-
  violations` / `raw-material-lot-coa-not-received-violations` /
  `raw-material-lot-assay-implausible-violations` in
  `hygaccess.governor`) — a batch citing an unverified or CoA-incomplete
  lot cannot be logged.
- **In-process QC (IPQC)** — each batch's own `:ipqc` record
  (`:ph-check-pass?` / `:assay-mid-batch-pct` /
  `:mixing-homogeneity-cov-pct`) is independently re-derived and the
  mixing-homogeneity coefficient-of-variation is HARD-gated at ≤5.0%
  (`hygaccess.registry/homogeneity-within-threshold?`,
  `hygaccess.governor/mixing-homogeneity-cov-exceeds-threshold-violations`).
- **Certificate of Analysis / batch release** — a batch may not be
  referenced by a shipment-coordination proposal unless it independently
  carries a passing CoA (`:coa {:coa-pass? true ...}`) AND an in-threshold
  IPQC homogeneity reading, both re-derived from the batch's own stored
  record (`hygaccess.registry/batch-release-qc-complete?`,
  `hygaccess.governor/batch-release-qc-incomplete-violations`).

This is a SIMULATED coordination/proposal-layer QA model (see repository
README "What this actor does NOT do") — it demonstrates the SHAPE of the
batch-record/QA discipline a real GMP-certified facility would need, but it
is not itself a GMP certification, an inspection, or a substitute for a real
facility's own quality system. TODO (real applicant): commission a real
GMP-certified manufacturing facility, a real accredited CoA testing
laboratory, and real raw-material supplier qualification before citing any
of the above in an actual CDSCO filing.

### 2.3 Stability / shelf-life data placeholder

TODO (real applicant): sodium hypochlorite solutions degrade over time
(concentration drifts downward, accelerated by heat/light/dilution) —
real accelerated and real-time stability studies establishing a validated
shelf-life and storage-condition label claim are required. This repo has
NO stability data; `hygaccess.registry/efficacy-window-pct` is a
POINT-IN-TIME concentration window at batch release, not a shelf-life
claim. Do not present the efficacy window as a substitute for a stability
study in a real filing.

### 2.4 Proposed labeling — cross-referenced to the actor's own substantiated-claims registry

The label claims below are drawn VERBATIM from
`hygaccess.registry/substantiated-claims` for `:water-purification-drops` —
the SAME closed claim set `hygaccess.governor/claim-not-substantiated-
violations` enforces in software (a `:propose-marketing-claim` proposal
citing any OTHER claim is HARD-blocked). This consistency is deliberate: the
label a real filer proposes to CDSCO should be the same claim the actor's
own governor would ever let this program commit to using downstream.

- "reduces waterborne bacterial and viral pathogens when used per labeled
  dosing (WHO Safe Water System-style point-of-use treatment)"
- "for household drinking-water treatment only — not a substitute for
  pre-filtering highly turbid water"

TODO (real applicant): a real CDSCO drug-label filing requires far more than
these two marketing-style claim strings — dosing instructions, warnings,
batch/expiry marking, manufacturer licence number, etc. per Drugs and
Cosmetics Rules labeling requirements. These two strings are the CONTENT
claims this repo's own governor already restricts marketing communications
to; they are a starting point for the health-claim portion of a real label,
not the whole label.

### 2.5 Import-licence branch (if sourced/imported rather than domestically manufactured)

If sodium hypochlorite solution for this program is imported rather than
manufactured domestically, it may fall under the **Medical Device Rules,
2017 (as amended 2020/2022)**, requiring an **MD-15 import licence** from
CDSCO (filed via Form MD-14 on the SUGAM portal per the general CDSCO
medical-device-import process). TODO (real applicant): confirm with
CDSCO/counsel whether THIS SPECIFIC product (a water-treatment/surface
disinfectant sold as a Drug, not marketed as a medical device) is actually
notified under the Medical Device Rules for import-licensing purposes, or
whether the ordinary Drug-import licensing route under the Drugs and
Cosmetics Rules applies instead — this dossier does not assert which route
applies without that confirmation, only that the MD-15 pathway EXISTS and
should be checked.

## 3. Track B — IPMP antibacterial-soap SKUs (Cosmetics track, DISTINCT from Track A)

The two IPMP SKUs (`int.hygaccess.antibacterial-soap-bar`,
`int.hygaccess.antibacterial-liquid-soap`) are soap-format products, not
disinfectant-drug-format products, so they fall under India's
**cosmetics regime** — also CDSCO-administered, but via the Cosmetics
Rules pathway (cosmetics manufacturing/import licensing, BIS cosmetic
product standards where applicable), NOT the Drug track in section 2.
Do not file these SKUs under the Drug track above.

- Active: isopropylmethylphenol (IPMP / o-cymen-5-ol), 0.1–0.3% (closed
  efficacy window, `hygaccess.registry/efficacy-window-pct`).
- Proposed label claim (verbatim from `hygaccess.registry/substantiated-
  claims` for `:antibacterial-soap`): "reduces transient bacteria on hands
  with proper handwashing technique" / "supports hand-hygiene behavior
  alongside adequate water access".
- TODO (real applicant): confirm current Cosmetics Rules registration/
  licensing pathway (domestic manufacturing licence vs. import
  registration) applicable to a medicated/antibacterial soap bar and a
  liquid soap SKU respectively, and whether IPMP requires any
  ingredient-level disclosure/limit under applicable BIS cosmetic
  standards.

## 4. Checklist of what a real applicant still needs

- [ ] A real GMP-certified manufacturing facility (domestic) or a real
      MD-15/import-licensed supply chain (imported) — this repo provides
      only a simulated batch-record/QA data MODEL, not an actual facility
      or actual GMP certification.
- [ ] A real accredited testing laboratory relationship for Certificate of
      Analysis issuance (this repo's `:coa` field is a data placeholder the
      governor checks structurally, not a real lab result).
- [ ] Real stability/shelf-life study data (none exists in this repo).
- [ ] An in-country regulatory agent / local counsel registered to file
      with CDSCO/BIS on the applicant's behalf.
- [ ] Confirmation of current IS 11673 Part 2:2019 edition/amendment status
      directly with BIS (standards move; this dossier reflects a
      point-in-time research check).
- [ ] Confirmation of which import/licensing branch (Drug-import vs.
      Medical-Device MD-15) actually applies to the specific sodium-
      hypochlorite SKU and sourcing model chosen.
- [ ] Separate, non-conflated filings for the Drug-track sodium-
      hypochlorite SKUs and the Cosmetics-track IPMP SKUs.
