# Contributing

`cloud-itonami-hygiene-access` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation, and operator/go-to-market model.

## Development
The capability layer lives in `kotoba-lang/*` libraries. This repo holds the
business blueprint and operator/go-to-market contracts.

```bash
clojure -M:test
clojure -M:lint
```

## Rules
- Do not commit real operating, formulation, personal, or credential data.
- Keep maintenance scheduling, shipment records, packaging designs,
  market-entry proposals, marketing-claim proposals, and disclosures behind
  the Hygiene Access Operations Governor.
- Treat workflows as high-risk: add tests for equipment-control gating,
  certification-decision gating, toxic-co-formulation gating,
  marketing-claim-substantiation gating, market-entry-approval gating,
  affordability-ceiling gating, channel-partner-licensing gating, record
  integrity, safety-concern escalation, and audit logging.
- Never add a marketing/health claim to the closed `substantiated-claims`
  set without a real evidentiary basis documented in the PR description —
  this set exists specifically to prevent unsubstantiated claims against a
  vulnerable population.
- Document any new business-model, formulation, or operator assumption in
  `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator, go-to-market, or
certification docs need updates.
