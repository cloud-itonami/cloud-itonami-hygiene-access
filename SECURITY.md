# Security Policy

This project handles hygiene/disinfectant formulation, production-batch,
crew-safety, market-entry, and marketing-claim coordination workflows for
programs targeting water-scarce / poor-sanitation-infrastructure markets.
Treat vulnerabilities as potentially high impact even when the demo data is
synthetic — a bypass here could route toward an unsafe formulation, an
unsubstantiated health claim, or an unapproved market entry.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real formulation, batch, or crew data exposure
- authorization bypass
- Hygiene Access Operations Governor bypass
- a bypass that would allow a toxic co-formulation (sodium hypochlorite +
  acid/ammonia) to be logged or shipped
- a bypass that would allow an unsubstantiated marketing/health claim to be
  proposed as substantiated
- a bypass that would allow a market-entry proposal against an unapproved
  country or above its affordability ceiling
- unauthorized chemical-safety/medical/regulatory-certification-decision
  bypass
- audit-ledger tampering
- over-disclosure in reports or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on formulation/batch data, policy enforcement, or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real formulation/batch/crew/consumer data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
- Any real deployment requires the applicable local regulatory approval
  (e.g. India CDSCO/BIS, GCC conformity bodies, ASEAN member-state
  regulators) before market entry — obtain and record that approval
  independently of this repository.
