# Regulatory dossier drafts — index

> **DRAFT / TEMPLATE — every document in this directory was prepared by an
> AI coding agent as a starting point for LOCAL REGULATORY COUNSEL. None of
> them is a filed submission, none is legal advice, and none is a claim
> that any `cloud-itonami-hygiene-access` product has actually been
> approved, registered, licensed, notified, or conformity-marked by any
> real regulatory authority anywhere.** Every dossier is full of explicit
> `TODO (real applicant)` markers for facts a real filer must supply with
> real facility/testing/personnel data — treat these documents as
> OUTLINES of what a real filing would need to cover, not as filled-in
> applications. This matches the repository README's own "What this actor
> does NOT do" disclosure: `cloud-itonami-hygiene-access` is a
> coordination/proposal-layer business actor, not a real regulatory or
> medical authority, and nothing in this directory changes that — these
> dossiers exist purely as software-adjacent documentation output, the
> same "practical/operational realism, not a claim of literal
> manufacturing/regulatory readiness" scope this repo's GMP batch-record
> model (see `docs/adr/`) was built under.

## Dossiers in this directory

| File | Region / authority | Scope |
|---|---|---|
| [`india-cdsco-dossier.md`](./india-cdsco-dossier.md) | India — CDSCO (Central Drugs Standard Control Organisation) / BIS (Bureau of Indian Standards) | Drug track (sodium-hypochlorite disinfectant SKUs, referencing BIS IS 11673 Part 2:2019, MD-15 import-licence branch) + a distinctly-flagged Cosmetics track (IPMP soap SKUs) — the two tracks are NOT conflated |
| [`gcc-gso-dossier.md`](./gcc-gso-dossier.md) | Gulf/GCC — GSO (Gulf Cooperation Council Standardization Organization) + national bodies (SFDA, ESMA/MOHAP, etc.) | Gulf Conformity Mark pathway, Draft GSO 2654:2025 GHS-aligned chemical classification/labeling regulation (still draft at this repo's build date), national-body add-on requirements |
| [`asean-cosmetic-dossier.md`](./asean-cosmetic-dossier.md) | Indonesia (BPOM) + Philippines (Philippines FDA), under the ASEAN Harmonized Cosmetic Regulatory Scheme | IPMP antibacterial-soap SKUs; PROMINENTLY surfaces the finding that IPMP lacks binding, ingredient-specific ASEAN regulation (flagged for Indonesia specifically), contrasted with Japan's mature OTC medicated-soap precedent, plus a real strategic sequencing recommendation |

## SKU → applicable regulatory track(s) → dossier mapping

| SKU (`products.edn` `:product/id`) | Active | Target countries in this program | Applicable track(s) | Dossier |
|---|---|---|---|---|
| `int.hygaccess.water-purification-drops` | Sodium hypochlorite | IN, BD, PK, ID, PH | India Drug track (IS 11673 Part 2:2019) for IN; a separate South/Southeast Asia national-regulator track for BD/PK/ID/PH is OUT OF SCOPE for this build's dossier set — see "What's not covered" below | `india-cdsco-dossier.md` (IN only) |
| `int.hygaccess.surface-disinfectant` | Sodium hypochlorite | IN, SA, AE, EG | India Drug track for IN; Gulf/GSO track for SA/AE; Egypt is grouped in this program's own market taxonomy but has its own national framework outside GSO — OUT OF SCOPE for this build's dossier set | `india-cdsco-dossier.md` (IN), `gcc-gso-dossier.md` (SA/AE) |
| `int.hygaccess.antibacterial-soap-bar` | Isopropylmethylphenol (IPMP) | IN, BD, PK, ID, PH, EG | India Cosmetics track for IN; ASEAN/BPOM track for ID; ASEAN/Philippines-FDA track for PH; BD/PK/EG are OUT OF SCOPE for this build's dossier set | `india-cdsco-dossier.md` (IN, Cosmetics track section), `asean-cosmetic-dossier.md` (ID, PH) |
| `int.hygaccess.antibacterial-liquid-soap` | Isopropylmethylphenol (IPMP) | IN, BD, PK, ID, PH | Same as above minus EG | `india-cdsco-dossier.md` (IN, Cosmetics track section), `asean-cosmetic-dossier.md` (ID, PH) |

### What's not covered

This dossier set covers the THREE regions the task's own reference research
targeted (India, Gulf/GCC, ASEAN Indonesia+Philippines) as representative
depth, not an exhaustive per-country filing for every country in
`hygaccess.registry/valid-market-countries`. **Bangladesh (BD), Pakistan
(PK), and Egypt (EG) do NOT have a dedicated dossier in this directory** —
a real go-to-market program targeting those countries needs its own
country-specific regulatory research and dossier, out of scope for this
build. Do not infer BD/PK/EG readiness from the presence of dossiers for
other countries in this directory.

## Cross-cutting note: labeling consistency with this actor's own governor

Every dossier in this directory that proposes label/marketing-claim content
draws that content VERBATIM from `hygaccess.registry/substantiated-claims`
— the SAME closed, per-product-type claim set
`hygaccess.governor/claim-not-substantiated-violations` already enforces in
software for every `:propose-marketing-claim` proposal this actor will ever
route. This is deliberate: a real regulatory filing's proposed label claims
should be the SAME claims the software governor already restricts this
program to using downstream, not a separately-invented set. If a real
filer's actual marketing needs diverge from this closed claim set, that is
a signal to update `hygaccess.registry/substantiated-claims` (a real
product/regulatory decision requiring its own review) rather than to file a
label claim the software governor would itself reject.

Similarly, every dossier that references batch/QA data points at this
repo's own GMP raw-material-lot / in-process-QC (IPQC) mixing-homogeneity /
Certificate-of-Analysis (CoA) batch-release model, added in the same build
as these dossiers (see `docs/adr/`) — the internal QA data shape a real
applicant's actual GMP-certified facility would need to substantiate with
real data, not a claim that this repo's simulated data IS that
substantiation.
