(ns hygaccess.governor
  "Hygiene Access Operations Governor -- the independent compliance
  layer that earns the HygieneAccessAdvisor the right to commit. The
  advisor has no notion of whether a piece of formulation/filling-line
  equipment it wants to schedule maintenance against has actually been
  inspected/registered, whether a batch it wants to coordinate a
  shipment against has actually been QC-verified/registered, whether a
  maintenance proposal secretly tries to ACTUATE the formulation/
  filling line, whether a batch proposal secretly tries to DECIDE a
  chemical-safety/medical/regulatory certification, whether a batch
  declares a sodium-hypochlorite formulation alongside a toxic-gas-
  hazard co-ingredient, whether a shipment proposal's own claimed
  weight would blow through the batch's own logged production weight,
  whether a batch's own declared concentration actually stays inside
  its product type's own efficacy window, whether a packaging-design
  proposal's own format is BOP-appropriate, whether a market-entry
  proposal's own target country is actually approved, whether its
  price-point actually stays within the product type's own
  affordability ceiling, whether its channel-partner is actually
  licensed for the declared channel, whether a marketing-claim
  proposal's own claim is actually a substantiated, pre-approved
  claim, whether a production batch's cited raw-material lot has
  actually been verified/registered/CoA-received with a plausible
  assay result (GMP raw-material release), whether a batch's own
  in-process-QC mixing-homogeneity reading actually stays within
  threshold, or whether a batch actually has a passing Certificate of
  Analysis AND an in-threshold homogeneity reading on file before it
  may ship (GMP batch-release sign-off) -- so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is
  `:hygiene-access-operations-governor` (see
  docs/adr/0001-architecture.md).

  This actor is a COORDINATION/PROPOSAL-LAYER business actor -- NOT a
  real chemical-manufacturing control system, NOT a real regulatory or
  medical authority, and NOT literal real-world sales infrastructure
  (see README `What this actor does NOT do`).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only         -- did the CALLER's own
                                              request actually declare
                                              `:effect :propose`? HARD,
                                              unconditional, evaluated
                                              BEFORE anything else.
    2. Closed op allowlist                -- is `:op` one of the seven
                                              ops this actor is
                                              authorized to coordinate?
                                              HARD hold otherwise.
    3. Closed proposal-effect allowlist   -- is the PROPOSAL's own
                                              `:effect` one of the
                                              seven propose-shaped
                                              effects? HARD, PERMANENT,
                                              unconditional.
    4. Line-actuate blocked               -- for `:schedule-
                                              maintenance`, does the
                                              proposal's own `:value`
                                              declare `:actuate-line?
                                              true`? HARD, PERMANENT,
                                              unconditional. NO phase
                                              and NO human approval can
                                              ever override this.
    5. Certification-decision blocked     -- does the proposal's own
                                              `:value`/`:patch` declare
                                              `:decide-certification?
                                              true`? Deciding or
                                              granting a chemical-
                                              safety, medical, or
                                              regulatory-market-access
                                              certification is
                                              EXCLUSIVELY the
                                              applicable certification/
                                              regulatory authority's
                                              (e.g. India CDSCO/BIS, a
                                              GCC conformity body, an
                                              ASEAN member-state
                                              regulator) call, never
                                              this actor's -- HARD,
                                              PERMANENT, unconditional.
    6. No-toxic-co-formulation blocked    -- for `:log-production-
                                              batch`, does the
                                              EFFECTIVE active resolve
                                              to `:sodium-hypochlorite`
                                              AND the EFFECTIVE
                                              `:co-ingredients` include
                                              an acid-bearing or
                                              ammonia-bearing member
                                              (`hygaccess.registry/
                                              toxic-co-formulation?`)?
                                              A toxic chlorine-gas/
                                              chloramine-gas household-
                                              chemical-mixing hazard is
                                              structurally
                                              unrepresentable -- HARD,
                                              PERMANENT, unconditional,
                                              mirroring `etzhayyim/
                                              com-etzhayyim-yakushi`'s
                                              own G22 exactly.
    7. Equipment not verified/registered  -- for `:schedule-
                                              maintenance`,
                                              INDEPENDENTLY verify the
                                              referenced equipment's
                                              own `:verified?` AND
                                              `:registered?` are both
                                              true.
    8. Already scheduled                  -- for `:schedule-
                                              maintenance`, refuses to
                                              schedule the SAME
                                              maintenance record twice.
    9. Batch not verified/registered      -- for `:coordinate-
                                              shipment`, INDEPENDENTLY
                                              verify the referenced
                                              batch's own `:verified?`
                                              AND `:registered?` are
                                              both true.
   10. Shipment weight exceeded           -- for `:coordinate-
                                              shipment`, INDEPENDENTLY
                                              recompute whether the
                                              batch's own recorded
                                              `:shipped-weight-kg` plus
                                              the proposal's own
                                              claimed `:weight-kg`
                                              would exceed the batch's
                                              own recorded
                                              `:weight-kg`.
   11. Invalid product-type               -- for `:log-production-
                                              batch`, if the patch
                                              declares a `:product-
                                              type` outside the closed
                                              known set, reject.
   12. Invalid active for product-type    -- for `:log-production-
                                              batch`, INDEPENDENTLY
                                              re-derive the EFFECTIVE
                                              active + product-type and
                                              check whether
                                              `[active product-type]`
                                              is even a recognized,
                                              authorized formulation
                                              combination
                                              (`hygaccess.registry/
                                              active-valid-for-product-
                                              type?`).
   13. Concentration outside efficacy
       window (G21 analog)                -- for `:log-production-
                                              batch`, when the
                                              `[active product-type]`
                                              pair IS recognized,
                                              INDEPENDENTLY re-verify
                                              the patch's own declared
                                              `:concentration-pct`
                                              stays within that pair's
                                              own closed efficacy
                                              window -- never taken on
                                              the advisor's self-report
                                              that the formulation
                                              'meets spec'. '濃ければ
                                              強い is FALSE' -- a
                                              concentration too HIGH is
                                              rejected exactly like one
                                              too LOW.
   14. Invalid off-spec rate              -- for `:log-production-
                                              batch`, if the patch
                                              declares an `:off-spec-
                                              rate-pct` that is not a
                                              physically plausible
                                              reading, reject.
   15. Invalid packaging format           -- for `:propose-packaging-
                                              design`, if the proposal
                                              declares a `:format`
                                              outside the closed BOP-
                                              appropriate set, reject.
   16. Market not approved                -- for `:propose-market-
                                              entry`, INDEPENDENTLY
                                              verify the referenced
                                              target country's own
                                              `market-entry-approvals`
                                              record is `:approved?`
                                              true -- never taken on
                                              the advisor's self-
                                              report. Any real
                                              deployment would still
                                              need the applicable local
                                              regulatory approval (e.g.
                                              India CDSCO/BIS, GCC
                                              conformity bodies, ASEAN
                                              member-state regulators)
                                              -- this actor does not
                                              itself grant that
                                              approval.
   17. Price above affordability ceiling  -- for `:propose-market-
                                              entry`, INDEPENDENTLY
                                              recompute whether the
                                              proposal's own `:price-
                                              minor` exceeds the
                                              product type's own closed
                                              affordability-ceiling
                                              table
                                              (`hygaccess.registry/
                                              price-ceiling-minor`) --
                                              a genuinely new domain-
                                              specific check for a BOP-
                                              market actor.
   18. Distribution-channel partner not
       licensed                            -- for `:propose-market-
                                              entry`, INDEPENDENTLY
                                              verify the referenced
                                              channel-partner exists,
                                              is `:licensed?` true, AND
                                              actually serves the
                                              proposal's own declared
                                              `:channel` -- never taken
                                              on the advisor's self-
                                              report.
   19. Marketing claim not substantiated  -- for `:propose-marketing-
                                              claim`, INDEPENDENTLY
                                              verify the proposal's own
                                              `:claim` string is a
                                              MEMBER of the product
                                              type's own closed
                                              substantiated-claims set
                                              -- never taken on the
                                              advisor's self-report
                                              that a claim is 'backed
                                              by data'. First-class
                                              ethical guardrail: blocks
                                              unsubstantiated 'cures
                                              disease X' style health
                                              claims against a
                                              vulnerable population.
   20. Raw-material lot not
       verified/registered (GMP)           -- for `:log-production-
                                              batch`, when the EFFECTIVE
                                              `:raw-material-lot-number`
                                              (patch's own value, else
                                              the batch's already-
                                              recorded value) is
                                              declared, INDEPENDENTLY
                                              verify that lot exists and
                                              is both `:verified?` AND
                                              `:registered?` -- never
                                              taken on the advisor's
                                              self-report. Mirrors the
                                              equipment/batch verified-
                                              gate pattern exactly,
                                              applied to a raw-material
                                              supply lot.
   21. Raw-material lot CoA not
       received (GMP)                      -- for `:log-production-
                                              batch`, when the EFFECTIVE
                                              raw-material lot IS
                                              verified/registered,
                                              INDEPENDENTLY verify that
                                              lot's own `:coa-received?`
                                              is true -- a lot without
                                              its own Certificate of
                                              Analysis on file may not
                                              back a production batch.
   22. Raw-material lot assay
       implausible (GMP)                   -- for `:log-production-
                                              batch`, when the EFFECTIVE
                                              raw-material lot HAS
                                              received its CoA,
                                              INDEPENDENTLY re-verify
                                              that lot's own `:coa-
                                              assay-pct` falls within
                                              its own active's closed
                                              plausibility window
                                              (`hygaccess.registry/
                                              raw-material-assay-
                                              plausible?`) -- a CoA
                                              assay wildly outside
                                              plausible purity for that
                                              active is fabricated/
                                              supplier-error data.
   23. Mixing-homogeneity CoV
       exceeds threshold (IPQC)            -- for `:log-production-
                                              batch`, when the EFFECTIVE
                                              `:ipqc` record declares a
                                              `:mixing-homogeneity-cov-
                                              pct`, INDEPENDENTLY
                                              re-verify it does not
                                              exceed `hygaccess.
                                              registry/homogeneity-cov-
                                              threshold-pct` (5.0%) --
                                              never taken on the
                                              advisor's self-report that
                                              mixing was homogeneous. In
                                              a real deployment this
                                              number would come from a
                                              physical IPQC sample or a
                                              CFD mixing-tank simulation
                                              (sibling repo `kotoba-
                                              lang/kami-app-hygaccess-
                                              plant`, name-only
                                              reference, no code
                                              dependency).
   24. Batch-release QC incomplete
       (CoA + IPQC sign-off)               -- for `:coordinate-
                                              shipment`, INDEPENDENTLY
                                              re-derive the referenced
                                              batch's OWN recorded
                                              `:coa`/`:ipqc` fields
                                              (never the shipment
                                              proposal's own self-
                                              report, which does not
                                              even carry these fields)
                                              and verify `hygaccess.
                                              registry/batch-release-qc-
                                              complete?` -- a batch may
                                              not be shipped without a
                                              passing Certificate of
                                              Analysis AND a mixing-
                                              homogeneity reading within
                                              threshold, the GMP 'batch
                                              release' QA sign-off gate.
  Checks 25-38 below were added by
  `docs/adr/0003-mes-regulatory-sales-extensions.md` (MES integration,
  regulatory-submission-status tracking, sales quote/order/fulfillment)
  -- additive only, none of checks 1-24 above were removed, renamed, or
  weakened.

   25. MES reading batch not
       verified/registered                 -- for `:record-mes-
                                              reading`, INDEPENDENTLY
                                              verify the referenced
                                              batch exists and is both
                                              `:verified?` AND
                                              `:registered?` -- mirrors
                                              check 9 exactly, applied
                                              to an MES/CFD telemetry
                                              reading instead of a
                                              shipment.
   26. MES reading pH implausible        -- for `:record-mes-reading`,
                                              when declared, the
                                              reading's own `:ph` must
                                              independently fall within
                                              `hygaccess.mes/ph-
                                              plausible-range` (0-14).
   27. MES reading temperature
       implausible                         -- for `:record-mes-
                                              reading`, when declared,
                                              the reading's own
                                              `:temperature-c` must
                                              independently fall within
                                              `hygaccess.mes/
                                              temperature-plausible-
                                              range-c`.
   28. MES reading mixing-RPM
       implausible                         -- for `:record-mes-
                                              reading`, when declared,
                                              the reading's own
                                              `:mixing-rpm` must
                                              independently fall within
                                              `hygaccess.mes/mixing-rpm-
                                              plausible-range`.
   29. MES reading homogeneity
       implausible                         -- for `:record-mes-
                                              reading`, when declared,
                                              the reading's own
                                              `:mixing-homogeneity-cov-
                                              pct` must independently
                                              fall within the PHYSICAL
                                              (not GMP-acceptance)
                                              bound `hygaccess.mes/
                                              homogeneity-cov-physical-
                                              range-pct` (0-100%).
   30. MES reading homogeneity
       mismatch vs batch self-report       -- the interesting one: for
                                              `:record-mes-reading`,
                                              when a PRIOR MES reading
                                              already exists on file for
                                              this batch, INDEPENDENTLY
                                              verify the batch's OWN
                                              currently-recorded IPQC
                                              self-report
                                              (`:ipqc :mixing-
                                              homogeneity-cov-pct`)
                                              matches that prior
                                              reading's own value within
                                              `hygaccess.mes/
                                              homogeneity-match-
                                              tolerance-pct` -- closes
                                              the loop checks 23/24 left
                                              open: those only ever
                                              re-derive the SELF-
                                              REPORTED number's own
                                              plausibility/threshold,
                                              never cross-validate it
                                              against an independent
                                              instrument/simulation
                                              reading.
   31. Regulatory-submission
       transition invalid                  -- for `:record-regulatory-
                                              submission-status`,
                                              INDEPENDENTLY look up the
                                              (market, product-type)
                                              submission's own CURRENTLY
                                              STORED status (or
                                              `:draft` if none exists
                                              yet) and re-verify the
                                              proposal's own claimed
                                              `:to-status` is a valid
                                              single-step transition in
                                              `hygaccess.regulatory/
                                              transitions` -- no
                                              skipping states, never
                                              taken on the proposal's
                                              own claim.
   32. Regulatory-submission
       evidence missing                    -- for `:record-regulatory-
                                              submission-status`, when
                                              the claimed `:to-status`
                                              is consequential
                                              (`:submitted`/`:approved`/
                                              `:rejected`), INDEPENDENTLY
                                              verify `:filed-by`/
                                              `:filing-date`/`:agency-
                                              reference` are ALL present
                                              as non-blank strings in
                                              the proposal's own value
                                              -- never defaulted or
                                              auto-generated; STATUS
                                              TRACKING ONLY, this actor
                                              never files anything with
                                              a real regulatory system.
   33. Sales-order target market
       not approved                        -- for `:propose-sales-
                                              order`, INDEPENDENTLY
                                              verify the order's own
                                              target market is
                                              `:approved?` in
                                              `market-entry-approvals`
                                              -- REUSES check 16's own
                                              ground-truth source/
                                              predicate, not a
                                              duplicated rule.
   34. Sales-order price mismatch
       vs registered SKU                   -- for `:propose-sales-
                                              order`, INDEPENDENTLY
                                              recompute whether the
                                              proposal's own claimed
                                              `:price-minor` matches the
                                              referenced SKU's own
                                              registered price in
                                              `hygaccess.registry/sku-
                                              catalog` -- never taken on
                                              the proposal's own self-
                                              report that the price is
                                              correct. A price is a
                                              plain reference number on
                                              a record; this check never
                                              moves, charges, or settles
                                              real money.
   35. Sales-order quantity
       implausible                         -- for `:propose-sales-
                                              order`, the proposal's own
                                              `:quantity` must be a
                                              positive number at or
                                              below `hygaccess.registry/
                                              max-plausible-order-
                                              quantity`.
   36. Fulfillment order not found       -- for `:update-fulfillment-
                                              status`, INDEPENDENTLY
                                              verify the referenced
                                              sales-order record
                                              actually exists -- never
                                              fabricate a fulfillment
                                              record for an order this
                                              actor has no independent
                                              record of.
   37. Fulfillment transition
       invalid                              -- for `:update-fulfillment-
                                              status`, INDEPENDENTLY
                                              re-derive the order's OWN
                                              currently-recorded
                                              `:fulfillment-status` (or
                                              `:pending` if absent) and
                                              re-verify the proposal's
                                              own claimed `:to-status`
                                              is a valid single-step
                                              transition in
                                              `hygaccess.registry/
                                              fulfillment-transitions`.
   38. Fulfillment shipment not
       on file                              -- for `:update-fulfillment-
                                              status`, a transition to
                                              `:shipped` must
                                              INDEPENDENTLY reference an
                                              existing, already-
                                              committed
                                              `:coordinate-shipment`
                                              record (`hygaccess.store/
                                              shipment`) -- never taken
                                              on the proposal's own
                                              claim that a shipment
                                              happened. You cannot mark
                                              an order shipped that has
                                              no corresponding real
                                              shipment record in the
                                              store.
   39. Confidence floor / high-stakes
       gate                                -- LLM confidence below
                                              threshold, OR the
                                              proposal's own `:stake`
                                              is in `high-stakes`
                                              (safety concerns ALWAYS,
                                              new market-entry
                                              proposals ALWAYS,
                                              marketing-claim proposals
                                              ALWAYS, and a market-
                                              entry price at/above 80%
                                              of its own ceiling) --
                                              escalate to a human
                                              go-to-market coordinator.
                                              SOFT: the human may
                                              approve.

  40. Control-loop alarm triggered
      (visibility + escalation, NEW this
      build)                               -- for `:record-mes-reading`
                                              AND `:log-production-
                                              batch`, INDEPENDENTLY
                                              re-derive the EFFECTIVE
                                              `:control-loop-alarm-
                                              triggered?` fact (the
                                              proposal's own `:value`
                                              for `:record-mes-reading`,
                                              else the EFFECTIVE
                                              `:ipqc` for `:log-
                                              production-batch`) and, if
                                              true, force escalation
                                              REGARDLESS of the
                                              proposal's own self-
                                              reported `:stake` -- this
                                              does NOT go through
                                              `stake-for`/`:stake`
                                              alone (which a mis-wired
                                              or compromised advisor
                                              could omit/forge), it is
                                              re-derived directly in
                                              `check` from the
                                              proposal's own EFFECTIVE
                                              data, the same ground-
                                              truth-not-self-report
                                              discipline every HARD
                                              check above already
                                              applies. A batch/reading
                                              whose real `hygaccess.mes`
                                              closed-loop control trace
                                              (PID + ISA-18.2 alarm +
                                              real CFD, see that ns)
                                              ever fired an alarm
                                              override during mixing
                                              must never silently look
                                              identical to one that
                                              converged cleanly under
                                              normal PID control. SOFT
                                              (not a HARD block, this
                                              build's own judgment call
                                              -- see `docs/adr` -- an
                                              alarm-overridden batch
                                              still physically converged
                                              in spec by the time
                                              mixing finished, so it is
                                              not itself a violation;
                                              it IS grave enough that a
                                              human go-to-market/QA
                                              coordinator must
                                              independently see it and
                                              decide, mirroring this
                                              gate's own existing 'ask
                                              a human, they may
                                              approve' posture for
                                              every other high-stakes
                                              signal).

  Plus ONE non-blocking, non-escalating WARN-only signal, `:warnings`
  in `check`'s return map (`hygaccess.regulatory/market-approval-
  without-submission-warnings`): surfaces, for every country
  independently `:approved?` in `market-entry-approvals`, whether at
  least one `:approved` regulatory-submission record backs it. This is
  NOT a 40th HARD/SOFT check -- it never blocks or escalates any
  proposal, existing or new (see `hygaccess.regulatory` ns docstring
  'NON-BREAKING WIRING NOTE' for why)."
  (:require [hygaccess.registry :as registry]
            [hygaccess.store :as store]
            [hygaccess.mes :as mes]
            [hygaccess.regulatory :as regulatory]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`. Eleven ops: the original
  seven (ADR-0001) plus four added by
  `docs/adr/0003-mes-regulatory-sales-extensions.md`
  (`:record-mes-reading` / `:record-regulatory-submission-status` /
  `:propose-sales-order` / `:update-fulfillment-status`)."
  #{:log-production-batch :schedule-maintenance :flag-safety-concern
    :coordinate-shipment :propose-packaging-design
    :propose-market-entry :propose-marketing-claim
    :record-mes-reading :record-regulatory-submission-status
    :propose-sales-order :update-fulfillment-status})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- eleven propose/transition-shaped effects (matching `allowed-ops`
  1:1), NEVER a direct formulation/filling-line-equipment-control
  effect, NEVER a certification-decision effect, and NEVER any real
  payment/fund-movement effect."
  #{:batch/upsert :maintenance/schedule :safety-concern/flag
    :shipment/propose :packaging-design/propose :market-entry/propose
    :marketing-claim/propose
    :mes-reading/record :regulatory-submission/transition
    :sales-order/propose :sales-order/fulfillment-transition})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns, new market-entry proposals, and marketing-claim
  proposals are the ops in this domain that always demand human eyes
  regardless of confidence; a market-entry price sitting near its own
  ceiling is an additional high-stakes signal on top of the
  always-high-stakes `:coordination/new-market-entry`.
  `:coordination/control-loop-alarm-triggered` (NEW this build, see
  check 40 above) flags a batch/reading whose real `hygaccess.mes`
  closed-loop control trace fired an ISA-18.2 alarm override during
  mixing -- forced independently in `check` below regardless of
  whether `stake-for` was even consulted, but ALSO derivable here for a
  well-behaved advisor that calls `stake-for` when drafting."
  #{:coordination/safety-concern
    :coordination/new-market-entry
    :coordination/marketing-claim-change
    :coordination/price-change-above-threshold
    :coordination/control-loop-alarm-triggered})

;; ----------------------------- helpers -----------------------------

(defn- effective [patch-key patch existing]
  (if (contains? patch patch-key) (get patch patch-key) (get existing patch-key)))

;; ----------------------------- baseline checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- proposal-effect-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct formulation/filling-line-equipment control, a
  fabricated actuation effect) is this actor's central scope
  boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :proposal-effect-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は配合/充填ラインの直接操作に該当する可能性があり、恒久的に禁止")}]))

;; ----------------------------- permanent blocks -----------------------------

(defn- line-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-maintenance` proposal
  whose own `:value` declares `:actuate-line? true` is attempting to
  directly actuate the formulation/filling line -- this actor may only
  ever propose/schedule a DRAFT maintenance window, never actuate the
  line directly. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-maintenance)
             (true? (:actuate-line? (:value proposal))))
    [{:rule :line-actuate-blocked
      :detail "配合/充填ラインの直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- certification-decision-blocked-violations
  "HARD, PERMANENT, unconditional: a proposal whose own `:value`/
  `:patch` declares `:decide-certification? true` is attempting to
  have this actor DECIDE or GRANT a chemical-safety, medical, or
  regulatory-market-access certification -- that decision is
  EXCLUSIVELY the applicable certification/regulatory authority's
  (e.g. India CDSCO/BIS, a GCC conformity body, an ASEAN member-state
  regulator) call, never this actor's. No phase or human approval
  override, ever."
  [proposal]
  (when (true? (:decide-certification? (:value proposal)))
    [{:rule :certification-decision-blocked
      :detail "化学品安全/医療/規制認証の可否判断・付与は認証・規制当局の専権事項であり、この actor が代行することは恒久的に禁止"}]))

(defn- no-toxic-co-formulation-blocked-violations
  "HARD, PERMANENT, unconditional: for `:log-production-batch`,
  INDEPENDENTLY re-derive the EFFECTIVE active (patch's own `:active`,
  else the batch's already-recorded value) and EFFECTIVE
  `:co-ingredients`, and check whether their combination is a toxic
  chlorine-gas (acid) or chloramine-gas (ammonia) hazard
  (`hygaccess.registry/toxic-co-formulation?`) -- structurally
  unrepresentable, mirroring `etzhayyim/com-etzhayyim-yakushi`'s own
  G22 EXACTLY. No phase and no human approval can ever override this."
  [{:keys [op subject]} proposal st]
  (when (= op :log-production-batch)
    (let [patch (:value proposal)
          existing (store/batch st subject)
          active (effective :active patch existing)
          co-ingredients (effective :co-ingredients patch existing)]
      (when (registry/toxic-co-formulation? active co-ingredients)
        [{:rule :no-toxic-co-formulation-blocked
          :detail (str subject " (active=" active ") の co-ingredients("
                       (pr-str co-ingredients)
                       ") が酸/アンモニア系と結合し塩素ガス/クロラミンガス発生の危険 -- 恒久的に表現不能")}]))))

;; ----------------------------- ground-truth gates: equipment/batch/shipment -----------------------------

(defn- equipment-not-verified-violations
  "For `:schedule-maintenance`, INDEPENDENTLY verify the referenced
  equipment exists and is both `:verified?` AND `:registered?` --
  never trust the advisor's own report."
  [{:keys [op]} proposal st]
  (when (= op :schedule-maintenance)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when-not (and eq (registry/equipment-ready? eq))
        [{:rule :equipment-not-verified
          :detail (str equipment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み設備記録が無い状態での保守作業予定提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-maintenance`, refuses to schedule the SAME
  maintenance record twice, off a dedicated `:scheduled?` fact."
  [{:keys [op subject]} st]
  (when (= op :schedule-maintenance)
    (when (store/maintenance-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- batch-not-verified-violations
  "For `:coordinate-shipment`, INDEPENDENTLY verify the referenced
  batch exists and is both `:verified?` AND `:registered?` -- never
  trust the advisor's own report."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [batch-id (:batch-id (:value proposal))
          b (and batch-id (store/batch st batch-id))]
      (when-not (and b (registry/batch-ready? b))
        [{:rule :batch-not-verified
          :detail (str batch-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みバッチ記録が無い状態での出荷調整提案")}]))))

(defn- shipment-weight-exceeded-violations
  "For `:coordinate-shipment`, INDEPENDENTLY recompute whether the
  batch's own recorded shipped-to-date weight plus the proposal's own
  claimed weight would exceed the batch's own recorded `:weight-kg`."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [{:keys [batch-id weight-kg]} (:value proposal)
          b (and batch-id (store/batch st batch-id))]
      (when (and b (registry/shipment-weight-exceeded? b weight-kg))
        [{:rule :shipment-weight-exceeded
          :detail (str batch-id " の記録済み生産量(" (:weight-kg b)
                       "kg)を、既存出荷実績(" (:shipped-weight-kg b 0.0)
                       "kg)+今回申請(" weight-kg "kg)が超過")}]))))

;; ----------------------------- formulation-batch validation -----------------------------

(defn- invalid-product-type-violations
  "For `:log-production-batch`, if the patch declares a
  `:product-type` outside the closed known set, reject."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [product-type (:product-type (:value proposal))]
      (when (and (some? product-type) (not (registry/product-type-valid? product-type)))
        [{:rule :invalid-product-type
          :detail (str product-type " は既知の product-type 値ではない")}]))))

(defn- invalid-active-for-product-type-violations
  "For `:log-production-batch`, INDEPENDENTLY re-derive the EFFECTIVE
  active and product type (patch's own values, else the batch's
  already-recorded values) and check whether `[active product-type]`
  is even a recognized, authorized formulation combination -- never
  taken on the advisor's self-report."
  [{:keys [op subject]} proposal st]
  (when (= op :log-production-batch)
    (let [patch (:value proposal)
          existing (store/batch st subject)
          active (effective :active patch existing)
          product-type (effective :product-type patch existing)]
      (when (and (some? active) (some? product-type)
                 (not (registry/active-valid-for-product-type? active product-type)))
        [{:rule :invalid-active-for-product-type
          :detail (str active " は product-type " product-type " に対して認可された配合ではない (未知の active または未認可の組合せ)")}]))))

(defn- concentration-outside-efficacy-window-violations
  "For `:log-production-batch`, when the EFFECTIVE `[active
  product-type]` pair IS a recognized formulation, INDEPENDENTLY
  re-verify the patch's own declared `:concentration-pct` stays within
  that pair's own closed efficacy window (G21 analog) -- never taken
  on the advisor's self-report that the formulation 'meets spec'.
  '濃ければ強い is FALSE' (more concentrated is not better)."
  [{:keys [op subject]} proposal st]
  (when (= op :log-production-batch)
    (let [patch (:value proposal)
          existing (store/batch st subject)
          active (effective :active patch existing)
          product-type (effective :product-type patch existing)
          concentration (:concentration-pct patch)]
      (when (and (some? concentration)
                 (registry/active-valid-for-product-type? active product-type)
                 (not (registry/concentration-within-efficacy-window? active product-type concentration)))
        [{:rule :concentration-outside-efficacy-window
          :detail (str subject " (active=" active " product-type=" product-type
                       ") の濃度(" concentration "%) が効力窓"
                       (pr-str (registry/efficacy-window-for active product-type))
                       "%の範囲外")}]))))

(defn- invalid-off-spec-rate-violations
  "For `:log-production-batch`, if the patch declares an `:off-spec-
  rate-pct` that is not a physically plausible reading, reject rather
  than let fabricated/sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [v (:off-spec-rate-pct (:value proposal))]
      (when (and (some? v) (not (registry/off-spec-rate-valid? v)))
        [{:rule :invalid-off-spec-rate
          :detail (str v "% は物理的に妥当な不良率の範囲外")}]))))

;; ----------------------------- packaging validation -----------------------------

(defn- invalid-packaging-format-violations
  "For `:propose-packaging-design`, if the proposal declares a
  `:format` outside the closed BOP-appropriate packaging-format set,
  reject rather than let an unaffordable/cold-chain/bulk format
  through."
  [{:keys [op]} proposal]
  (when (= op :propose-packaging-design)
    (let [format (:format (:value proposal))]
      (when-not (registry/packaging-format-valid? format)
        [{:rule :invalid-packaging-format
          :detail (str format " は BOP 向け梱包形式の許可リストに無い")}]))))

;; ----------------------------- market-entry validation -----------------------------

(defn- market-not-approved-violations
  "For `:propose-market-entry`, INDEPENDENTLY verify the referenced
  target country's own `market-entry-approvals` record is `:approved?`
  true -- never taken on the advisor's self-report. Any real
  deployment would still need the applicable local regulatory approval
  -- this actor does not itself grant that approval."
  [{:keys [op]} proposal st]
  (when (= op :propose-market-entry)
    (let [country (:country (:value proposal))
          approval (and country (store/market-approval st country))]
      (when-not (and approval (registry/market-approved? approval))
        [{:rule :market-not-approved
          :detail (str country " は市場参入未承認、もしくは登録が無い -- 現地規制当局の承認が確認された国のみ市場参入提案可能")}]))))

(defn- price-above-ceiling-violations
  "For `:propose-market-entry`, INDEPENDENTLY recompute whether the
  proposal's own `:price-minor` exceeds the product type's own closed
  affordability-ceiling table -- a genuinely new domain-specific check
  for a BOP-market actor."
  [{:keys [op]} proposal]
  (when (= op :propose-market-entry)
    (let [{:keys [product-type price-minor]} (:value proposal)]
      (when (registry/price-above-ceiling? product-type price-minor)
        [{:rule :price-above-ceiling
          :detail (str product-type " の価格(" price-minor " USDC-minor) が許容上限("
                       (registry/price-ceiling-for product-type) " USDC-minor)を超過")}]))))

(defn- channel-partner-not-licensed-violations
  "For `:propose-market-entry`, INDEPENDENTLY verify the referenced
  channel-partner exists, is `:licensed?` true, AND actually serves the
  proposal's own declared `:channel` -- never taken on the advisor's
  self-report (mirrors the equipment/batch verified-gate discipline,
  applied to a distribution-channel partner)."
  [{:keys [op]} proposal st]
  (when (= op :propose-market-entry)
    (let [{:keys [channel channel-partner-id]} (:value proposal)
          partner (and channel-partner-id (store/channel-partner st channel-partner-id))]
      (when-not (registry/channel-partner-ready? partner channel)
        [{:rule :channel-partner-not-licensed
          :detail (str channel-partner-id " はチャネル(" channel ")に対し未ライセンス・未登録、もしくは存在しない")}]))))

;; ----------------------------- marketing-claim validation -----------------------------

(defn- claim-not-substantiated-violations
  "For `:propose-marketing-claim`, INDEPENDENTLY verify the proposal's
  own `:claim` string is a MEMBER of the product type's own closed
  substantiated-claims set -- never taken on the advisor's self-report
  that a claim is 'backed by data'. First-class ethical guardrail:
  blocks unsubstantiated health claims against a vulnerable
  population."
  [{:keys [op]} proposal]
  (when (= op :propose-marketing-claim)
    (let [{:keys [product-type claim]} (:value proposal)]
      (when-not (registry/claim-substantiated? product-type claim)
        [{:rule :claim-not-substantiated
          :detail (str "\"" claim "\" は product-type " product-type " の承認済み実証済みクレーム集合に無い")}]))))

;; ----------------------------- GMP raw-material-lot validation -----------------------------

(defn- raw-material-lot-not-verified-violations
  "For `:log-production-batch`, when the EFFECTIVE `:raw-material-lot-
  number` (patch's own value, else the batch's already-recorded value)
  is declared, INDEPENDENTLY verify that lot exists and is both
  `:verified?` AND `:registered?` -- never trust the advisor's own
  report. Mirrors the equipment/batch verified-gate pattern exactly,
  applied to a raw-material supply lot."
  [{:keys [op subject]} proposal st]
  (when (= op :log-production-batch)
    (let [patch (:value proposal)
          existing (store/batch st subject)
          lot-number (effective :raw-material-lot-number patch existing)]
      (when (some? lot-number)
        (let [lot (store/raw-material-lot st lot-number)]
          (when-not (and lot (registry/raw-material-lot-ready? lot))
            [{:rule :raw-material-lot-not-verified
              :detail (str lot-number " は未検証または未登録、もしくは存在しない原材料ロット -- 検証済み・登録済みロット記録が無い状態でのバッチ記録")}]))))))

(defn- raw-material-lot-coa-not-received-violations
  "For `:log-production-batch`, when the EFFECTIVE raw-material lot IS
  verified/registered, INDEPENDENTLY verify that lot's own `:coa-
  received?` is true -- a lot without its own Certificate of Analysis
  on file may not back a production batch."
  [{:keys [op subject]} proposal st]
  (when (= op :log-production-batch)
    (let [patch (:value proposal)
          existing (store/batch st subject)
          lot-number (effective :raw-material-lot-number patch existing)]
      (when (some? lot-number)
        (let [lot (store/raw-material-lot st lot-number)]
          (when (and lot (registry/raw-material-lot-ready? lot)
                     (not (registry/raw-material-lot-coa-received? lot)))
            [{:rule :raw-material-lot-coa-not-received
              :detail (str lot-number " は Certificate of Analysis (CoA) 未受領")}]))))))

(defn- raw-material-lot-assay-implausible-violations
  "For `:log-production-batch`, when the EFFECTIVE raw-material lot HAS
  received its CoA, INDEPENDENTLY re-verify that lot's own `:coa-
  assay-pct` falls within its own active's closed plausibility window
  (`hygaccess.registry/raw-material-assay-plausible?`) -- a CoA assay
  wildly outside plausible purity for that active is fabricated/
  supplier-error data, never trusted as-is."
  [{:keys [op subject]} proposal st]
  (when (= op :log-production-batch)
    (let [patch (:value proposal)
          existing (store/batch st subject)
          lot-number (effective :raw-material-lot-number patch existing)]
      (when (some? lot-number)
        (let [lot (store/raw-material-lot st lot-number)]
          (when (and lot (registry/raw-material-lot-ready? lot)
                     (registry/raw-material-lot-coa-received? lot)
                     (not (registry/raw-material-assay-plausible? (:active lot) (:coa-assay-pct lot))))
            [{:rule :raw-material-lot-assay-implausible
              :detail (str lot-number " (active=" (:active lot) ") のCoA assay(" (:coa-assay-pct lot)
                           "%) が妥当性窓" (pr-str (registry/raw-material-assay-plausibility-window-for (:active lot)))
                           "%の範囲外")}]))))))

;; ----------------------------- in-process QC (IPQC) mixing-homogeneity validation -----------------------------

(defn- mixing-homogeneity-cov-exceeds-threshold-violations
  "For `:log-production-batch`, when the EFFECTIVE `:ipqc` record
  (patch's own value, else the batch's already-recorded value) declares
  a `:mixing-homogeneity-cov-pct`, INDEPENDENTLY re-verify it does not
  exceed `hygaccess.registry/homogeneity-cov-threshold-pct` -- never
  taken on the advisor's self-report that mixing was homogeneous. In a
  real deployment this coefficient-of-variation number would come from
  either a physical IPQC sample or a CFD mixing-tank simulation (see
  sibling repo `kotoba-lang/kami-app-hygaccess-plant`, referenced here
  by NAME ONLY -- a loose EDN-field-level convention, no code
  dependency on that repo)."
  [{:keys [op subject]} proposal st]
  (when (= op :log-production-batch)
    (let [patch (:value proposal)
          existing (store/batch st subject)
          ipqc (effective :ipqc patch existing)
          cov (:mixing-homogeneity-cov-pct ipqc)]
      (when (and (some? cov) (not (registry/homogeneity-within-threshold? cov)))
        [{:rule :mixing-homogeneity-cov-exceeds-threshold
          :detail (str subject " の IPQC 混合均一性CoV(" cov "%) が閾値("
                       registry/homogeneity-cov-threshold-pct "%)を超過")}]))))

;; ----------------------------- Certificate of Analysis (CoA) / batch-release sign-off validation -----------------------------

(defn- batch-release-qc-incomplete-violations
  "For `:coordinate-shipment`, INDEPENDENTLY re-derive the referenced
  batch's OWN recorded `:coa`/`:ipqc` fields (never the shipment
  proposal's own self-report, which does not even carry these fields)
  and verify `hygaccess.registry/batch-release-qc-complete?` -- a batch
  may not be shipped without a passing Certificate of Analysis AND a
  mixing-homogeneity reading within threshold, the GMP 'batch release'
  QA sign-off gate."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [batch-id (:batch-id (:value proposal))
          b (and batch-id (store/batch st batch-id))]
      (when (and b (not (registry/batch-release-qc-complete? b)))
        [{:rule :batch-release-qc-incomplete
          :detail (str batch-id " は CoA未合格または IPQC混合均一性CoVが閾値超過のため出荷判定(batch release)未完了")}]))))

;; ----------------------------- MES (Manufacturing Execution System) reading validation -----------------------------

(defn- mes-reading-batch-not-verified-violations
  "For `:record-mes-reading`, INDEPENDENTLY verify the referenced batch
  exists and is both `:verified?` AND `:registered?` -- never trust the
  advisor's own report. Mirrors `batch-not-verified-violations` exactly,
  applied to an MES/CFD-sourced telemetry reading instead of a
  shipment."
  [{:keys [op]} proposal st]
  (when (= op :record-mes-reading)
    (let [batch-id (:batch-id (:value proposal))
          b (and batch-id (store/batch st batch-id))]
      (when-not (and b (registry/batch-ready? b))
        [{:rule :mes-reading-batch-not-verified
          :detail (str batch-id " は未検証または未登録、もしくは存在しないバッチ -- 検証済み・登録済みバッチ記録が無い状態でのMES/CFDテレメトリ記録")}]))))

(defn- mes-reading-ph-implausible-violations
  "For `:record-mes-reading`, when declared, the reading's own `:ph`
  must independently fall within `hygaccess.mes/ph-plausible-range`."
  [{:keys [op]} proposal]
  (when (= op :record-mes-reading)
    (let [ph (:ph (:value proposal))]
      (when (and (some? ph) (not (mes/ph-plausible? ph)))
        [{:rule :mes-reading-ph-implausible
          :detail (str ph " は物理的に妥当なpH範囲" (pr-str mes/ph-plausible-range) "の外 -- 捏造またはセンサ異常値")}]))))

(defn- mes-reading-temperature-implausible-violations
  "For `:record-mes-reading`, when declared, the reading's own
  `:temperature-c` must independently fall within `hygaccess.mes/
  temperature-plausible-range-c`."
  [{:keys [op]} proposal]
  (when (= op :record-mes-reading)
    (let [t (:temperature-c (:value proposal))]
      (when (and (some? t) (not (mes/temperature-plausible? t)))
        [{:rule :mes-reading-temperature-implausible
          :detail (str t "C は物理的に妥当な温度範囲" (pr-str mes/temperature-plausible-range-c) "Cの外 -- 捏造またはセンサ異常値")}]))))

(defn- mes-reading-rpm-implausible-violations
  "For `:record-mes-reading`, when declared, the reading's own
  `:mixing-rpm` must independently fall within `hygaccess.mes/mixing-
  rpm-plausible-range`."
  [{:keys [op]} proposal]
  (when (= op :record-mes-reading)
    (let [rpm (:mixing-rpm (:value proposal))]
      (when (and (some? rpm) (not (mes/mixing-rpm-plausible? rpm)))
        [{:rule :mes-reading-rpm-implausible
          :detail (str rpm " RPM は物理的に妥当な攪拌速度範囲" (pr-str mes/mixing-rpm-plausible-range) "の外 -- 捏造またはセンサ異常値")}]))))

(defn- mes-reading-homogeneity-implausible-violations
  "For `:record-mes-reading`, when declared, the reading's own
  `:mixing-homogeneity-cov-pct` must independently fall within the
  PHYSICAL (not GMP-acceptance) plausibility bound `hygaccess.mes/
  homogeneity-cov-physical-range-pct` -- deliberately DIFFERENT from
  `hygaccess.registry/homogeneity-cov-threshold-pct` (the 5.0% GMP
  ACCEPTANCE threshold checked elsewhere); a reading above 5.0% is
  still real, physically-plausible telemetry worth recording here."
  [{:keys [op]} proposal]
  (when (= op :record-mes-reading)
    (let [cov (:mixing-homogeneity-cov-pct (:value proposal))]
      (when (and (some? cov) (not (mes/homogeneity-cov-physically-plausible? cov)))
        [{:rule :mes-reading-homogeneity-implausible
          :detail (str cov "% は物理的に妥当なCoV範囲" (pr-str mes/homogeneity-cov-physical-range-pct) "%の外 -- 捏造またはセンサ異常値")}]))))

(defn- mes-reading-homogeneity-mismatch-violations
  "The interesting one: for `:record-mes-reading`, if a PRIOR MES
  reading already exists on file for this batch (ground truth from the
  store, `hygaccess.store/mes-readings-for-batch`, latest-first-
  filtered to the most recent), INDEPENDENTLY verify the batch's own
  CURRENTLY-RECORDED IPQC self-report (`:ipqc :mixing-homogeneity-cov-
  pct`) matches that prior reading's own homogeneity value within
  `hygaccess.mes/homogeneity-match-tolerance-pct` -- never taken on the
  batch record's own self-report alone once independent MES/CFD ground
  truth exists for it. Closes the loop
  `mixing-homogeneity-cov-exceeds-threshold-violations`/
  `batch-release-qc-incomplete-violations` left open: those two only
  ever re-derive the SELF-REPORTED IPQC number's own plausibility/
  threshold, never cross-validate it against an independent instrument/
  simulation reading."
  [{:keys [op]} proposal st]
  (when (= op :record-mes-reading)
    (let [batch-id (:batch-id (:value proposal))
          prior (seq (store/mes-readings-for-batch st batch-id))]
      (when (seq prior)
        (let [latest (last prior)
              mes-cov (:mixing-homogeneity-cov-pct latest)
              b (store/batch st batch-id)
              self-reported-cov (get-in b [:ipqc :mixing-homogeneity-cov-pct])]
          (when (and (some? mes-cov) (some? self-reported-cov)
                     (not (mes/homogeneity-values-match? mes-cov self-reported-cov)))
            [{:rule :mes-reading-homogeneity-mismatch
              :detail (str batch-id " の自己申告IPQC混合均一性CoV(" self-reported-cov
                           "%) が既存MES/CFD記録の値(" mes-cov
                           "%) と許容誤差" mes/homogeneity-match-tolerance-pct
                           "%を超えて不一致 -- 自己申告のみを信用しない")}]))))))

;; ----------------------------- regulatory-submission-status state-machine validation -----------------------------

(defn- regulatory-transition-invalid-violations
  "For `:record-regulatory-submission-status`, INDEPENDENTLY look up
  the (market, product-type) submission's own CURRENTLY STORED status
  (or `:draft` if no record exists yet for this subject) and re-verify
  the proposal's own claimed `:to-status` is a valid SINGLE-STEP
  transition in `hygaccess.regulatory/transitions` -- never taken on
  the proposal's own claim that a jump is fine. No skipping states."
  [{:keys [op subject]} proposal st]
  (when (= op :record-regulatory-submission-status)
    (let [existing (store/regulatory-submission st subject)
          from (:status existing :draft)
          to (:to-status (:value proposal))]
      (when (or (nil? to) (not (regulatory/valid-transition? from to)))
        [{:rule :regulatory-transition-invalid
          :detail (str subject " の規制提出ステータス遷移 " from " -> " (pr-str to)
                       " は許可された単一ステップ遷移ではない (現在許可される遷移先: "
                       (pr-str (get regulatory/transitions from #{})) ")")}]))))

(defn- regulatory-evidence-missing-violations
  "For `:record-regulatory-submission-status`, when the proposal's own
  claimed `:to-status` is a consequential status (`:submitted`/
  `:approved`/`:rejected`), INDEPENDENTLY verify ALL THREE human-
  evidence fields (`:filed-by`/`:filing-date`/`:agency-reference`) are
  present as non-blank strings in the proposal's own value -- never
  defaulted or auto-generated by this actor. A human counsel/filer must
  supply this evidence; this actor never fabricates it, and never files
  anything with a real regulatory system itself."
  [{:keys [op subject]} proposal]
  (when (= op :record-regulatory-submission-status)
    (let [to (:to-status (:value proposal))]
      (when (and (contains? regulatory/consequential-statuses to)
                 (not (regulatory/evidence-complete? (:value proposal))))
        [{:rule :regulatory-evidence-missing
          :detail (str subject " の " to " への遷移には :filed-by/:filing-date/:agency-reference の"
                       "人間供給証跡が全て必須 (空白・欠落・自動生成は不可)")}]))))

;; ----------------------------- sales quote/order validation (NO payment, NO fund movement) -----------------------------

(defn- sales-order-market-not-approved-violations
  "For `:propose-sales-order`, INDEPENDENTLY verify the order's own
  target market is `:approved?` in `market-entry-approvals` -- REUSES
  the SAME ground-truth source and predicate `market-not-approved-
  violations` already uses, never a duplicated/re-derived rule."
  [{:keys [op]} proposal st]
  (when (= op :propose-sales-order)
    (let [market (:market (:value proposal))
          approval (and market (store/market-approval st market))]
      (when-not (and approval (registry/market-approved? approval))
        [{:rule :sales-order-market-not-approved
          :detail (str market " は市場参入未承認、もしくは登録が無い -- 承認済み市場のみ受注提案可能")}]))))

(defn- sales-order-price-mismatch-violations
  "For `:propose-sales-order`, INDEPENDENTLY recompute whether the
  proposal's own claimed `:price-minor` matches the referenced SKU's
  own registered price in `hygaccess.registry/sku-catalog` -- never
  taken on the proposal's own self-report that the price is correct. A
  price is a plain reference number on a record; this check never
  moves, charges, or settles real money."
  [{:keys [op]} proposal]
  (when (= op :propose-sales-order)
    (let [{:keys [sku price-minor]} (:value proposal)]
      (when (registry/sku-price-mismatch? sku price-minor)
        [{:rule :sales-order-price-mismatch
          :detail (str sku " の申告価格(" price-minor " USDC-minor) が登録価格("
                       (registry/sku-price-for sku) " USDC-minor)と不一致")}]))))

(defn- sales-order-quantity-invalid-violations
  "For `:propose-sales-order`, the proposal's own `:quantity` must be a
  positive number at or below `hygaccess.registry/max-plausible-order-
  quantity` -- a fabricated/data-entry-error reading, never let
  through as a real order."
  [{:keys [op]} proposal]
  (when (= op :propose-sales-order)
    (let [q (:quantity (:value proposal))]
      (when-not (registry/order-quantity-valid? q)
        [{:rule :sales-order-quantity-invalid
          :detail (str (pr-str q) " は物理的に妥当な発注数量ではない (正の数かつ上限"
                       registry/max-plausible-order-quantity "以下である必要)")}]))))

;; ----------------------------- fulfillment-status validation -----------------------------

(defn- fulfillment-order-not-found-violations
  "For `:update-fulfillment-status`, INDEPENDENTLY verify the referenced
  sales-order record actually exists -- never fabricate a fulfillment
  record for an order this actor has no independent record of."
  [{:keys [op subject]} st]
  (when (= op :update-fulfillment-status)
    (when-not (store/sales-order st subject)
      [{:rule :fulfillment-order-not-found
        :detail (str subject " という発注記録が存在しない -- 未登録の発注に対する配送状況更新")}])))

(defn- fulfillment-transition-invalid-violations
  "For `:update-fulfillment-status`, when the referenced order exists,
  INDEPENDENTLY re-derive the order's OWN currently-recorded
  `:fulfillment-status` (or `:pending` if absent) and re-verify the
  proposal's own claimed `:to-status` is a valid single-step transition
  in `hygaccess.registry/fulfillment-transitions`."
  [{:keys [op subject]} proposal st]
  (when (= op :update-fulfillment-status)
    (let [order (store/sales-order st subject)
          from (:fulfillment-status order :pending)
          to (:to-status (:value proposal))]
      (when (and order (or (nil? to) (not (registry/fulfillment-transition-valid? from to))))
        [{:rule :fulfillment-transition-invalid
          :detail (str subject " の配送状況遷移 " from " -> " (pr-str to)
                       " は許可された単一ステップ遷移ではない")}]))))

(defn- fulfillment-shipment-not-on-file-violations
  "For `:update-fulfillment-status`, a transition to `:shipped` must
  INDEPENDENTLY reference an existing, already-committed
  `:coordinate-shipment` record -- never taken on the proposal's own
  claim that a shipment happened. You cannot mark an order shipped that
  has no corresponding real shipment record in the store."
  [{:keys [op]} proposal st]
  (when (= op :update-fulfillment-status)
    (let [to (:to-status (:value proposal))
          shipment-id (:shipment-id (:value proposal))]
      (when (= to :shipped)
        (when-not (and shipment-id (store/shipment st shipment-id))
          [{:rule :fulfillment-shipment-not-on-file
            :detail (str shipment-id " という出荷調整記録(:coordinate-shipment)が見つからない -- "
                         "対応する実出荷記録の無い状態での出荷完了報告")}])))))

;; ----------------------------- stake derivation (advisor self-report, SOFT layer only) -----------------------------

(defn stake-for
  "Derive the `:stake` a proposal SHOULD carry, purely from the
  request/proposal's own declared op and (for market-entry) price --
  used by `hygaccess.advisor` when drafting a proposal. This is a SOFT
  signal only (see `high-stakes`/confidence gate below): even if a
  future caller omits or forges `:stake`, `hygaccess.phase`
  independently never adds `:flag-safety-concern`/`:propose-market-
  entry`/`:propose-marketing-claim` to any phase's `:auto` set, so
  escalation does not depend solely on this self-reported field. For
  `:record-mes-reading`, a `:control-loop-alarm-triggered? true` value
  ALSO derives `:coordination/control-loop-alarm-triggered` here (for a
  well-behaved advisor) -- but `check` below re-derives the SAME fact
  independently regardless of what this fn (or the advisor) produced,
  see `control-loop-alarm-stakes?`."
  [{:keys [op value]}]
  (case op
    :flag-safety-concern :coordination/safety-concern
    :propose-market-entry (if (registry/price-near-ceiling? (:product-type value) (:price-minor value))
                             :coordination/price-change-above-threshold
                             :coordination/new-market-entry)
    :propose-marketing-claim :coordination/marketing-claim-change
    :record-mes-reading (when (true? (:control-loop-alarm-triggered? value))
                           :coordination/control-loop-alarm-triggered)
    nil))

(defn- control-loop-alarm-stakes?
  "Check 40's independent re-derivation (NOT via `stake-for`/`:stake`):
  for `:record-mes-reading`, does the proposal's own `:value` declare
  `:control-loop-alarm-triggered? true`? For `:log-production-batch`,
  does the EFFECTIVE `:ipqc` (patch's own value, else the batch's
  already-recorded value -- same `effective` helper every other
  ground-truth check above uses) declare it? Either is grave enough to
  force escalation in `check` below REGARDLESS of what `:stake` the
  proposal happens to carry -- mirrors every HARD ground-truth check's
  own 'never taken on the advisor's self-report' discipline, applied
  here to a SOFT (escalate, not block) signal because an alarm-
  overridden batch still physically converged in spec by the time
  mixing finished (see docstring item 40 above for the full reasoning)."
  [{:keys [op subject]} proposal st]
  (case op
    :record-mes-reading
    (true? (:control-loop-alarm-triggered? (:value proposal)))
    :log-production-batch
    (let [patch (:value proposal)
          existing (store/batch st subject)
          ipqc (effective :ipqc patch existing)]
      (true? (:control-loop-alarm-triggered? ipqc)))
    false))

(defn check
  "Censors a HygieneAccessAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool :warnings [..]}. `:warnings` is a
  NON-BLOCKING, non-escalating informational signal (see
  `hygaccess.regulatory/market-approval-without-submission-warnings`) --
  it never affects `:ok?`/`:hard?`/`:escalate?`."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (proposal-effect-blocked-violations proposal)
                           (line-actuate-blocked-violations request proposal)
                           (certification-decision-blocked-violations proposal)
                           (no-toxic-co-formulation-blocked-violations request proposal st)
                           (equipment-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (batch-not-verified-violations request proposal st)
                           (shipment-weight-exceeded-violations request proposal st)
                           (invalid-product-type-violations request proposal)
                           (invalid-active-for-product-type-violations request proposal st)
                           (concentration-outside-efficacy-window-violations request proposal st)
                           (invalid-off-spec-rate-violations request proposal)
                           (invalid-packaging-format-violations request proposal)
                           (market-not-approved-violations request proposal st)
                           (price-above-ceiling-violations request proposal)
                           (channel-partner-not-licensed-violations request proposal st)
                           (claim-not-substantiated-violations request proposal)
                           (raw-material-lot-not-verified-violations request proposal st)
                           (raw-material-lot-coa-not-received-violations request proposal st)
                           (raw-material-lot-assay-implausible-violations request proposal st)
                           (mixing-homogeneity-cov-exceeds-threshold-violations request proposal st)
                           (batch-release-qc-incomplete-violations request proposal st)
                           (mes-reading-batch-not-verified-violations request proposal st)
                           (mes-reading-ph-implausible-violations request proposal)
                           (mes-reading-temperature-implausible-violations request proposal)
                           (mes-reading-rpm-implausible-violations request proposal)
                           (mes-reading-homogeneity-implausible-violations request proposal)
                           (mes-reading-homogeneity-mismatch-violations request proposal st)
                           (regulatory-transition-invalid-violations request proposal st)
                           (regulatory-evidence-missing-violations request proposal)
                           (sales-order-market-not-approved-violations request proposal st)
                           (sales-order-price-mismatch-violations request proposal)
                           (sales-order-quantity-invalid-violations request proposal)
                           (fulfillment-order-not-found-violations request st)
                           (fulfillment-transition-invalid-violations request proposal st)
                           (fulfillment-shipment-not-on-file-violations request proposal st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (or (boolean (high-stakes (:stake proposal)))
                    (control-loop-alarm-stakes? request proposal st))
        hard? (boolean (seq hard))
        warnings (regulatory/market-approval-without-submission-warnings
                  (map :country (filter registry/market-approved? (store/all-market-approvals st)))
                  (store/all-regulatory-submissions st))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?
     :warnings     warnings}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
