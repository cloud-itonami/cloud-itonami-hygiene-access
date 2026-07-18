# Operator Quickstart — Affordable hygiene/disinfectant commercialization

Shortest path from clone to a verified local dry-run for
`cloud-itonami-hygiene-access`.

## Prerequisites

- Clojure 1.12+ (`clojure --version`)
- Java 17+
- Git

No invented metrics; this is a governed OSS blueprint, not a hosted SaaS demo.

## 1. Clone

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-hygiene-access.git
cd cloud-itonami-hygiene-access
```

## 2. Run tests

```bash
clojure -M:test
```

Expect green if maturity is `implemented`. Fix failures before operating.

## 3. Open the product face

```bash
open docs/index.html   # or: python3 -m http.server -d docs 8080
```

Publish: enable GitHub Pages on `main` `/docs`, or any static host.

## 4. Where the Governor sits

- Blueprint governor key: `hygiene-access-operations-governor`
- Source path: `src/hygaccess/governor.cljc`
- Pattern: advise → govern → phase-gate → commit | escalate | hold (itonami actor / ADR-2607011000)

## 5. Where the commercial catalog sits

- `products.edn` at repo root — four representative SKUs (water-purification
  drops, surface disinfectant, antibacterial soap bar, antibacterial liquid
  soap) with formulation, packaging, pricing, target-market, marketing-claim,
  and distribution-channel data.

## 6. Claim / go-live

- Free claim funnel: https://itonami.cloud/hygiene-access/
- Paid path docs: https://itonami.cloud/docs/go-live.md
- Blueprint: `blueprint.edn`

## Constraints

- Do not invent users/revenue numbers for marketing.
- Do not add a marketing/health claim to the closed substantiated-claims set
  without a real evidentiary basis.
- Any real market entry requires the applicable local regulatory approval
  (e.g. India CDSCO/BIS, GCC conformity bodies, ASEAN member-state
  regulators) — this operator does not itself grant that approval.
- No force-push; keep AGPL headers.
- Secrets stay out of this repo.
