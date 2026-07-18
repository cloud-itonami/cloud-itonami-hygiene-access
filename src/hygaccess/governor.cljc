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
   25. Confidence floor / high-stakes
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
                                              approve."
  (:require [hygaccess.registry :as registry]
            [hygaccess.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-production-batch :schedule-maintenance :flag-safety-concern
    :coordinate-shipment :propose-packaging-design
    :propose-market-entry :propose-marketing-claim})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all seven are propose-shaped drafts, NEVER a direct formulation/
  filling-line-equipment-control effect and NEVER a certification-
  decision effect."
  #{:batch/upsert :maintenance/schedule :safety-concern/flag
    :shipment/propose :packaging-design/propose :market-entry/propose
    :marketing-claim/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns, new market-entry proposals, and marketing-claim
  proposals are the ops in this domain that always demand human eyes
  regardless of confidence; a market-entry price sitting near its own
  ceiling is an additional high-stakes signal on top of the
  always-high-stakes `:coordination/new-market-entry`."
  #{:coordination/safety-concern
    :coordination/new-market-entry
    :coordination/marketing-claim-change
    :coordination/price-change-above-threshold})

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

;; ----------------------------- stake derivation (advisor self-report, SOFT layer only) -----------------------------

(defn stake-for
  "Derive the `:stake` a proposal SHOULD carry, purely from the
  request/proposal's own declared op and (for market-entry) price --
  used by `hygaccess.advisor` when drafting a proposal. This is a SOFT
  signal only (see `high-stakes`/confidence gate below): even if a
  future caller omits or forges `:stake`, `hygaccess.phase`
  independently never adds `:flag-safety-concern`/`:propose-market-
  entry`/`:propose-marketing-claim` to any phase's `:auto` set, so
  escalation does not depend solely on this self-reported field."
  [{:keys [op value]}]
  (case op
    :flag-safety-concern :coordination/safety-concern
    :propose-market-entry (if (registry/price-near-ceiling? (:product-type value) (:price-minor value))
                             :coordination/price-change-above-threshold
                             :coordination/new-market-entry)
    :propose-marketing-claim :coordination/marketing-claim-change
    nil))

(defn check
  "Censors a HygieneAccessAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
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
                           (batch-release-qc-incomplete-violations request proposal st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

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
