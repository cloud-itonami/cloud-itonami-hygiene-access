# ASEAN (Indonesia/Philippines cosmetics) regulatory dossier outline — DRAFT / TEMPLATE

> **DRAFT / TEMPLATE — prepared by an AI coding agent as a starting point for
> local regulatory counsel. This is NOT a filed submission, NOT legal
> advice, and NOT a claim that any `cloud-itonami-hygiene-access` product
> has actually been notified, registered, or approved by BPOM, the
> Philippines FDA, or any other ASEAN-region authority.** See
> `docs/regulatory/README.md` for the disclaimer that applies to all three
> dossiers in this directory.

## 1. Regulatory framework

The two IPMP antibacterial-soap SKUs (`int.hygaccess.antibacterial-soap-
bar`, `int.hygaccess.antibacterial-liquid-soap`) target Indonesia (**ID**)
and the Philippines (**PH**) among this program's `hygaccess.registry/
valid-market-countries`. Both countries are ASEAN member states, and
cosmetic products in the region are governed by the **ASEAN Harmonized
Cosmetic Regulatory Scheme (AHCRS)** — a regional framework built around
mutual recognition of national notifications rather than a single
centralized ASEAN-wide approval body. National implementation is per
country:

- **Indonesia** — **BPOM** (Badan Pengawas Obat dan Makanan, the National
  Agency for Drug and Food Control) is the national notifying authority.
- **Philippines** — the **Philippines FDA** (Food and Drug Administration)
  is the national notifying authority.

## 2. IMPORTANT FINDING — surfaced prominently, not buried: IPMP itself lacks binding, IPMP-specific regulation in the ASEAN region

**This is a genuine finding from web research conducted for this dossier,
not a hedge or boilerplate caveat, and it directly affects go-to-market
sequencing for the IPMP SKUs specifically (as distinct from the
sodium-hypochlorite SKUs, which are a separate active with their own India/
Gulf regulatory tracks in the sibling dossiers in this directory):**

> Isopropylmethylphenol (IPMP / o-cymen-5-ol) does NOT have a binding,
> ingredient-specific regulation under the ASEAN Cosmetic Directive (ACD)
> annexes (flagged specifically for **Indonesia**, where BPOM Regulation
> No. 25 of 2025 on Technical Requirements for Cosmetic Ingredients governs
> general cosmetic-ingredient compliance but does not appear to carry an
> IPMP-specific ingredient-level standard/limit at research time). This
> means ASEAN-region ingredient-level quality standards for IPMP are
> comparatively INCONSISTENT compared to a more established precedent in
> markets like **Japan**, where IPMP is a long-used OTC medicated-soap
> active with a mature regulatory and safety-usage history (Japan's Quasi-
> Drug / OTC medicated-cosmetic framework has treated IPMP as a recognized
> antibacterial active for an extended period).

**This directly corrects an overstatement that must NOT be repeated in this
program's go-to-market materials**: IPMP should NOT be described as
"widely regulatory-precedented" in ASEAN specifically. The Japan precedent
is real and load-bearing for a REGULATORY-STRATEGY argument (see section 3
below), but it is a DIFFERENT market's precedent, not evidence that ASEAN
itself has settled, ingredient-specific IPMP regulation. A real filer
should not conflate "IPMP has real-world usage history somewhere" with
"IPMP has a clear regulatory pathway in ASEAN" — this dossier treats those
as two separate facts.

TODO (real applicant): confirm current BPOM Regulation No. 25/2025 annex
contents and Philippines FDA cosmetic ingredient list directly, and check
whether any IPMP-specific guidance has been issued since this dossier's
research date — regulatory annexes are amended periodically and this
finding reflects a point-in-time check, not a permanent absence.

## 3. Recommended strategic sequencing (a strategic note, NOT a governor-code change)

**This section is a real strategic recommendation for the human go-to-
market decision-maker — it is NOT implemented as any change to
`hygaccess.governor`/`hygaccess.registry`, and it does not and should not
attempt to encode "use Japan precedent" as a machine-checkable rule.** The
actor's own `market-not-approved-violations` HARD check continues to gate
`:propose-market-entry` on the store's ground-truth `market-entry-
approvals` table regardless of this strategic note — this section only
informs how a human operator might sequence REAL regulatory engagement
before ever marking ID/PH `:approved? true` in that ground-truth table.

Two real options for a real filer, not mutually exclusive:

1. **Lean on Japan-market safety/usage-history data as supporting
   documentation in the ASEAN notification.** Japan's long OTC medicated-
   soap usage history for IPMP is a genuine, citable safety/precedent data
   point a real BPOM/Philippines-FDA notification dossier could include as
   supporting evidence for ingredient safety — even though it does not
   substitute for an ASEAN-specific ingredient standard, real notification
   processes generally accept supporting safety data from other mature
   regulatory markets.
2. **Sequence ASEAN entry AFTER establishing precedent in a market with a
   clearer IPMP-specific pathway** (e.g. Japan itself, or another market
   with an established IPMP-specific standard) — entering a market with
   settled IPMP precedent first, then citing that market's approval/usage
   history when approaching ASEAN notification, reduces the real-world risk
   of ASEAN-notification delay/rejection due to ingredient-standard
   ambiguity.

A real go-to-market decision-maker should weigh both options against actual
program timeline/capital constraints — this dossier does not pick one for
them.

## 4. Draft dossier outline (once the sequencing question above is resolved)

### 4.1 Indonesia (BPOM) notification outline

| Field | Draft value | Source |
|---|---|---|
| Product | Hygiene Access IPMP 抗菌石鹸バー 0.15% (antibacterial soap bar) | `products.edn` `int.hygaccess.antibacterial-soap-bar` |
| Active | Isopropylmethylphenol (IPMP), 0.15% (within the closed 0.1–0.3% efficacy window) | `hygaccess.registry/efficacy-window-pct` |
| Notifying authority | BPOM | External |
| Governing framework | ASEAN Harmonized Cosmetic Regulatory Scheme, implemented nationally per BPOM Regulation No. 25/2025 (cosmetic-ingredient technical requirements) — TODO (real applicant): confirm current regulation number/status at filing time | External |
| Proposed label claim | "reduces transient bacteria on hands with proper handwashing technique" (verbatim member of `hygaccess.registry/substantiated-claims` for `:antibacterial-soap` — same claim set `hygaccess.governor/claim-not-substantiated-violations` enforces for this actor's own `:propose-marketing-claim` proposals) | `hygaccess.registry` |
| IPMP-specific ingredient standard status | NOT settled in ASEAN region per section 2 finding above — see recommended sequencing in section 3 | This dossier's own research |

### 4.2 Philippines FDA notification outline

| Field | Draft value | Source |
|---|---|---|
| Product | Hygiene Access IPMP 抗菌液体石鹸 0.2% (antibacterial liquid soap) | `products.edn` `int.hygaccess.antibacterial-liquid-soap` |
| Active | Isopropylmethylphenol (IPMP), 0.2% | `hygaccess.registry/efficacy-window-pct` |
| Notifying authority | Philippines FDA | External |
| Governing framework | ASEAN Harmonized Cosmetic Regulatory Scheme, implemented nationally per Philippines FDA cosmetic notification rules — TODO (real applicant): confirm current rules at filing time | External |
| Proposed label claim | "supports hand-hygiene behavior alongside adequate water access" (verbatim member of `hygaccess.registry/substantiated-claims` for `:antibacterial-soap`) | `hygaccess.registry` |
| IPMP-specific ingredient standard status | Same finding as Indonesia — not settled in ASEAN region generally, not specifically re-verified for the Philippines at research time; TODO (real applicant): confirm directly with Philippines FDA | This dossier's own research |

## 5. Checklist of what a real applicant still needs

- [ ] A resolved sequencing decision (Japan-first vs. Japan-as-supporting-
      evidence) per section 3, made by the human go-to-market
      decision-maker.
- [ ] Current BPOM Regulation No. 25/2025 annex text and Philippines FDA
      cosmetic ingredient list, confirmed directly (this dossier's finding
      reflects a point-in-time research check).
- [ ] Real safety/usage-history documentation from the Japan OTC medicated-
      soap market if pursuing option 1 in section 3 (none is bundled in
      this repo — this is a documentation-sourcing task for the real
      filer).
- [ ] Local counsel/regulatory agent registered to notify with BPOM and/or
      Philippines FDA respectively.
- [ ] **Note on the seed/demo data**: `hygaccess.store/sample-data!`'s
      `market-entry-approvals` table currently marks ID and PH
      `:approved? true` — this is DEMO/TEST seed data only, used to
      exercise this actor's own governor test coverage (both the approved
      and not-yet-approved code paths need seed coverage), and is
      explicitly documented elsewhere in this repo as NOT a claim of real
      regulatory approval (see `hygaccess.registry/market-approved?`
      docstring, `products.edn`'s own `target-countries-approved` vs.
      `target-countries` distinction). Given this dossier's own finding in
      section 2, a real operator should NOT treat that seed value as
      evidence IPMP is actually cleared for ASEAN entry — real BPOM/
      Philippines-FDA notification per section 3's sequencing decision must
      still happen before this product line is genuinely ready for these
      markets, regardless of what the demo seed data says.
