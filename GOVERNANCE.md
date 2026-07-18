# Governance

`cloud-itonami-hygiene-access` is an OSS open-business blueprint for commercializing low-cost hygiene/disinfectant active ingredients (sodium hypochlorite, isopropylmethylphenol) for water-scarce / poor-sanitation-infrastructure markets — formulation-batch logging, packaging design, market-entry/pricing, marketing-claim substantiation, and distribution-channel coordination.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a formulation/filling-line-equipment action the governor refuses is never dispatched to hardware.
- the Hygiene Access Operations Governor remains independent of the advisor.
- hard policy violations (equipment-control bypass, line actuation, certification decision-making, toxic co-formulation, unsubstantiated marketing claims, unapproved market entry, above-ceiling pricing, unlicensed channel partners, record-suppression, unauthorized disclosure) cannot be overridden by human approval where marked PERMANENT.
- every schedule, sign-off, record, market-entry, marketing-claim, and disclose path is auditable.
- sensitive operating, formulation, and personal data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification, or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require safety, audit, and data-flow review. Operating this program for real requires the applicable local regulatory approval (e.g. India CDSCO/BIS, GCC conformity bodies, ASEAN member-state regulators) — this repository's own operator does not grant that approval.

Certified operators can lose certification for:
- bypassing formulation/filling-line-control or record policy checks
- claiming or exercising a chemical-safety, medical, or regulatory-market-access certification authority this actor does not have
- formulating or shipping a toxic co-formulation (sodium hypochlorite + acid/ammonia)
- publishing an unsubstantiated marketing/health claim
- entering a market without independently verified local regulatory approval
- mishandling sensitive data
- misrepresenting certification or approval status
- failing to respond to security or safety incidents
