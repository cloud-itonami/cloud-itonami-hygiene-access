(ns hygaccess.registry
  "Pure-function domain logic for the low-cost hygiene/disinfectant
  active-ingredient commercialization actor -- formulation-batch
  verification, shipment-weight recompute, product-type validation,
  active-ingredient efficacy-window validation, no-toxic-co-formulation
  validation, packaging-format validation, market-entry-approval
  validation, affordability-price-ceiling validation,
  marketing-claim-substantiation validation, distribution-channel-
  partner-licensing validation, GMP-style raw-material-lot release
  validation, in-process-QC (IPQC) mixing-homogeneity validation,
  Certificate-of-Analysis (CoA) / batch-release sign-off validation,
  and draft maintenance-schedule/shipment-coordination/packaging-
  design/market-entry/marketing-claim record construction. Also carries
  the SKU price-catalog ground truth and fulfillment-status state
  machine for the sales quote/order/fulfillment workflow added in
  `docs/adr/0003-mes-regulatory-sales-extensions.md` -- see
  `hygaccess.mes` for the Manufacturing Execution System integration
  contract and `hygaccess.regulatory` for the regulatory-submission-
  status state machine added in that same extension.

  Two active ingredients, sodium hypochlorite (NaOCl, 次亜塩素酸ナトリウム)
  and isopropylmethylphenol (IPMP / o-cymen-5-ol), formulated into three
  affordable product types (water-purification drops, surface
  disinfectant, antibacterial soap) for water-scarce / poor-sanitation-
  infrastructure markets (India, Gulf/Arabia/MENA, South & Southeast
  Asia). This is a COORDINATION/PROPOSAL-LAYER business actor -- NOT a
  real chemical-manufacturing control system, NOT a real regulatory or
  medical authority, and NOT literal real-world sales infrastructure.
  See README `What this actor does NOT do`.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations, regulatory, or sales system. It
  builds the DRAFT record a plant/go-to-market coordinator would keep
  (a scheduled maintenance window, a coordinated shipment, a packaging
  design, a market-entry proposal, a marketing-claim proposal), not the
  act of actuating a formulation/filling line, dispatching a real
  freight carrier, granting a chemical-safety/medical certification, or
  executing a real sale -- this actor NEVER does any of these (see
  README `What this actor does NOT do`).

  'Ground truth, not self-report' discipline -- the same discipline
  every sibling `cloud-itonami-isic-*` actor's own registry establishes
  (`adhesivemfg.registry`/`soapmfg.registry`'s equipment/batch
  verification and shipment-weight recompute; here extended to two
  genuinely new domain-specific gates a BOP go-to-market actor needs:
  market-entry-country approval and affordability-price-ceiling
  compliance) and `etzhayyim/com-etzhayyim-yakushi`'s Wave 2
  disinfectant-formulation gates G21 (efficacy-window) and G22
  (no-toxic-gas-formulation): never trust a proposal's own
  self-reported weight/status/concentration/claim-substantiation/
  market-approval/channel-partner-licensing when the inputs needed to
  recompute it independently are already on record.

  ENGINE REWIRE (this build): the fulfillment-status transition table's
  core ordered-stage validity now DELEGATES to `kotoba.crm.pipeline`
  (`kotoba-lang/crm`)'s generic ordered-stage/exit-stage engine -- the
  same technical commons `cloud-itonami.regulatory-tracker.core` (see
  `hygaccess.regulatory`) is itself built on -- instead of a bespoke
  hand-rolled table. See `fulfillment-transition-valid?` below for the
  one additional domain-specific layer kept on top."
  (:require [clojure.set :as set]
            [kotoba.crm.pipeline :as pipeline]))

;; ----------------------------- active ingredients -----------------------------

(def known-actives
  "The closed set of active ingredients this actor formulates.
  `:sodium-hypochlorite` (NaOCl, 次亜塩素酸ナトリウム) -- a household bleach
  active used here in two use-classes at two DIFFERENT concentration
  windows (see `efficacy-window-pct`). `:isopropylmethylphenol` (IPMP /
  o-cymen-5-ol) -- an OTC antibacterial-soap active. Anything else is a
  fabricated/unrecognized active -- the governor HARD-holds rather than
  let an invented active pass through."
  #{:sodium-hypochlorite :isopropylmethylphenol})

(def valid-product-types
  "The closed set of product-type values a production-batch, packaging-
  design, market-entry, or marketing-claim record may declare."
  #{:water-purification-drops :surface-disinfectant :antibacterial-soap})

(def efficacy-window-pct
  "The closed `[active product-type] -> [min max]` efficacy-window
  table (percent w/v stock/ready-to-use concentration) -- G21 in
  `etzhayyim/com-etzhayyim-yakushi`'s own terms ('濃ければ強い is FALSE',
  more concentrated is not better; out-of-window is structurally
  blocked). This is also the closed authorization table for WHICH
  active may be formulated into WHICH product type -- an
  `[active product-type]` pair absent from this table is not a
  recognized formulation at all, independent of concentration.

  - `[:sodium-hypochlorite :water-purification-drops]` 0.5-1.5% --
    representative of the WHO/CDC 'Safe Water System' household
    point-of-use water-treatment precedent: a dilute NaOCl STOCK
    solution, a few drops dosed per liter of drinking water by the end
    user (the stock solution itself, not the diluted dose the end user
    drinks).
  - `[:sodium-hypochlorite :surface-disinfectant]` 0.05-0.5% --
    matches `etzhayyim/com-etzhayyim-yakushi`'s own already-declared
    Wave 2 window for its `sodium-hypochlorite` surface-use-class
    product (README.md `効力窓 (G21)` table / CLAUDE.md `G21
    efficacy-window`) -- reused verbatim here for cross-fleet
    consistency rather than re-derived.
  - `[:isopropylmethylphenol :antibacterial-soap]` 0.1-0.3% --
    representative of real-world OTC medicated/antibacterial soap
    formulation concentrations."
  {[:sodium-hypochlorite :water-purification-drops] [0.5 1.5]
   [:sodium-hypochlorite :surface-disinfectant]      [0.05 0.5]
   [:isopropylmethylphenol :antibacterial-soap]      [0.1 0.3]})

(def acid-or-ammonia-co-ingredients
  "The closed set of acid-bearing or ammonia-bearing co-ingredient
  values that make a sodium-hypochlorite formulation a toxic-gas
  hazard (chlorine gas when mixed with an acid, chloramine gas when
  mixed with ammonia) -- household-chemical-mixing accident chemistry,
  the same hazard `etzhayyim/com-etzhayyim-yakushi`'s own G22
  'no-toxic-gas-formulation' gate blocks ('sodium-hypochlorite + any
  acid (Cl2) or ammonia (chloramine) is constitutionally
  unrepresentable'). A batch record may never declare
  `:sodium-hypochlorite` as its active AND cite any of these as a
  `:co-ingredient` -- see `no-toxic-co-formulation?` below, PERMANENT,
  unconditional, no phase or human-approval override (mirrors
  `etzhayyim/com-etzhayyim-yakushi` G22 exactly)."
  #{:hydrochloric-acid :sulfuric-acid :phosphoric-acid :acetic-acid
    :citric-acid :toilet-bowl-acid-cleaner :rust-remover-acid
    :ammonia :ammonium-hydroxide :ammonium-chloride :urine-based-cleaner})

(def valid-output-forms
  "The closed set of physical output forms this plant's own filling
  line may package -- liquid/tablet/bar, the shape this actor's own
  three product types actually take."
  #{:liquid :tablet :bar})

(def off-spec-rate-min-percent 0.0)
(def off-spec-rate-max-percent
  "Physical ceiling for a batch's own off-spec/reject-rate reading -- a
  batch cannot reject more than 100% of its own output. A reading
  above this is implausible sensor/QC data, not a real batch."
  100.0)

;; ----------------------------- packaging -----------------------------

(def valid-packaging-formats
  "The closed BOP/hot-climate/water-scarce/informal-retail-appropriate
  packaging-format set -- chosen to explicitly REJECT anything needing
  cold-chain or bulk >1L (see README `Packaging scope`). A production
  bar-soap SKU is represented via `:sachet` (individually-wrapped
  bar-equivalent unit) since 'bar' is a physical shape, not a
  distribution-packaging format, and this closed set governs
  distribution packaging only."
  #{:sachet :tablet-strip :small-bottle-50ml :small-bottle-100ml})

;; ----------------------------- target markets -----------------------------

(def valid-market-countries
  "A small representative closed set of ISO3166-alpha-2 codes spanning
  the three target regions: India (`IN`); Gulf/Arabia/MENA (`SA` Saudi
  Arabia, `AE` UAE, `EG` Egypt); South & Southeast Asia (`BD`
  Bangladesh, `PK` Pakistan, `ID` Indonesia, `PH` Philippines). Each
  country must ALSO be independently marked `:approved?` in the
  store's `market-entry-approvals` table (ground truth, not
  self-report) before a `:propose-market-entry` proposal may commit
  against it -- see `hygaccess.store`."
  #{"IN" "SA" "AE" "EG" "BD" "PK" "ID" "PH"})

;; ----------------------------- affordability price ceiling -----------------------------

(def price-ceiling-minor
  "The closed per-product-type affordability PRICE CEILING table,
  USDC-minor (6dp, matching `etzhayyim/com-etzhayyim-yakushi`'s own
  currency convention: 1 USDC = 1,000,000 minor units). Representative
  of real-world social-marketing hygiene-product pricing in
  water-scarce / poor-sanitation-infrastructure markets (e.g. the WHO
  Safe Water System / PSI-style social-marketing precedent of
  dilute-bleach water-treatment bottles and antibacterial soap bars
  sold at a small fraction of a US dollar per unit so BOP households
  can actually afford routine repurchase, not a one-time aid handout):
  - `:water-purification-drops` 500000 (USDC 0.50) per small
    (50ml) stock-solution bottle -- a WHO SWS-style bottle typically
    treats on the order of 1,000L of drinking water at a few drops
    per liter, so a household repurchases infrequently; representative
    social-marketing pricing for this product class sits well under
    USDC 1.
  - `:surface-disinfectant` 1000000 (USDC 1.00) per 100ml
    ready-to-use bottle -- a slightly larger unit/higher per-bottle
    cost than the drops product, still affordable for informal
    retail/institutional buyers.
  - `:antibacterial-soap` 300000 (USDC 0.30) per bar/sachet or small
    liquid-soap bottle -- matches typical BOP antibacterial-soap-bar
    social-marketing price points (roughly USDC 0.15-0.30), the
    product type with the highest repurchase frequency in this
    catalog so the ceiling is set lowest.
  A `:propose-market-entry` proposal whose own `:price-minor` exceeds
  its product-type's own ceiling is HARD-blocked -- see
  `price-above-ceiling?` below."
  {:water-purification-drops 500000
   :surface-disinfectant     1000000
   :antibacterial-soap       300000})

;; ----------------------------- distribution channels -----------------------------

(def valid-distribution-channels
  "The closed set of distribution-channel values a `:propose-market-
  entry` proposal may cite -- each proposal citing a channel must ALSO
  reference a channel-partner independently marked `:licensed?` true
  AND serving that channel in the store (ground truth, not
  self-report) -- see `channel-partner-ready?` below."
  #{:ngo-who-program :government-procurement
    :microfinance-social-enterprise :licensed-informal-retail-aggregator
    :institutional-bulk})

;; ----------------------------- marketing claims -----------------------------

(def substantiated-claims
  "The closed per-product-type set of WHO/CDC-style SUBSTANTIATED
  marketing/health claims -- a `:propose-marketing-claim` proposal's
  own `:claim` string must be a MEMBER of its product type's own
  closed set here, never taken on the advisor's self-report that a
  claim is 'backed by data'. This is a first-class ethical guardrail,
  not an afterthought: it exists specifically to block unsubstantiated
  'cures disease X' style health claims against a vulnerable
  population (see README `Marketing-claim substantiation`)."
  {:water-purification-drops
   #{"reduces waterborne bacterial and viral pathogens when used per labeled dosing (WHO Safe Water System-style point-of-use treatment)"
     "for household drinking-water treatment only -- not a substitute for pre-filtering highly turbid water"}
   :surface-disinfectant
   #{"disinfects hard non-porous surfaces"
     "for environmental/surface use only -- not for drinking water or open wounds"}
   :antibacterial-soap
   #{"reduces transient bacteria on hands with proper handwashing technique"
     "supports hand-hygiene behavior alongside adequate water access"}})

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  formulation/filling-line equipment registry)?"
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its product-type/active/concentration/weight claims have
  actually been QC-inspected, not merely logged from an unverified
  intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger?"
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-weight-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal: would
  `shipped-to-date-kg` + `new-weight-kg` exceed `batch`'s own recorded
  `:weight-kg` (the batch's own logged production weight)? Needs no
  proposal inspection or stored-verdict lookup -- its inputs are
  permanent fields already on the batch's own record."
  [batch new-weight-kg]
  (let [capacity (:weight-kg batch)
        so-far (:shipped-weight-kg batch 0.0)]
    (and (number? capacity)
         (number? new-weight-kg)
         (> (+ (double so-far) (double new-weight-kg)) (double capacity)))))

(defn product-type-valid?
  "Is `product-type` one of the closed, known product-type values
  (water-purification-drops, surface-disinfectant,
  antibacterial-soap)? nil/blank is treated as invalid (a
  production-batch patch must declare a real product type, not omit
  it silently)."
  [product-type]
  (contains? valid-product-types product-type))

(defn off-spec-rate-valid?
  "Is `percent` a physically plausible batch off-spec/reject-rate
  reading? Rejects nil, non-numbers, negative values, and values
  beyond `off-spec-rate-max-percent` -- a fabricated or sensor-error
  reading, never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) off-spec-rate-min-percent)
       (<= (double percent) off-spec-rate-max-percent)))

;; ----------------------------- efficacy-window checks (G21 analog) -----------------------------

(defn efficacy-window-for
  "The closed `[min max]` concentration window for `active` formulated
  into `product-type`, or nil if this `[active product-type]` pair is
  not a recognized formulation at all."
  [active product-type]
  (get efficacy-window-pct [active product-type]))

(defn active-valid-for-product-type?
  "Ground-truth check: is `[active product-type]` a recognized,
  authorized formulation combination -- i.e. does `efficacy-window-for`
  return a window at all? Rejects both an unknown `active` (outside
  `known-actives`) AND a known active formulated into a product type it
  is not authorized for (e.g. IPMP into a water-purification-drops
  product) -- the two are the same failure mode: no closed-table
  entry, no authorized formulation."
  [active product-type]
  (boolean (efficacy-window-for active product-type)))

(defn concentration-within-efficacy-window?
  "Ground-truth check (G21 analog): is `concentration-pct` a number
  falling within `[active product-type]`'s own closed efficacy window
  (inclusive)? Only meaningful when `active-valid-for-product-type?` is
  already true -- callers check that separately.
  '濃ければ強い is FALSE' (more concentrated is not better) --
  a concentration ABOVE the window is rejected exactly like one
  below it."
  [active product-type concentration-pct]
  (boolean
   (and (number? concentration-pct)
        (let [[lo hi] (efficacy-window-for active product-type)]
          (and (some? lo)
               (>= (double concentration-pct) (double lo))
               (<= (double concentration-pct) (double hi)))))))

;; ----------------------------- no-toxic-co-formulation check (G22 analog) -----------------------------

(defn no-toxic-co-formulation?
  "Ground-truth check (G22 analog, mirrors
  `etzhayyim/com-etzhayyim-yakushi`'s own G22
  'no-toxic-gas-formulation' EXACTLY): a batch record may NEVER
  declare BOTH `:sodium-hypochlorite` as its active AND cite any
  member of `acid-or-ammonia-co-ingredients` among its
  `:co-ingredients` -- this combination is a toxic chlorine-gas
  (acid) or chloramine-gas (ammonia) household-chemical-mixing hazard,
  structurally unrepresentable. Returns `true` when the formulation is
  SAFE (no toxic co-formulation); `false`/hazardous combinations are
  caught by `toxic-co-formulation?` below, the negation used by the
  governor."
  [active co-ingredients]
  (not (and (= active :sodium-hypochlorite)
            (seq (set/intersection
                  (set co-ingredients) acid-or-ammonia-co-ingredients)))))

(defn toxic-co-formulation?
  "Negation of `no-toxic-co-formulation?`, the form the governor calls
  directly. PERMANENT, unconditional -- no phase and no human approval
  can ever override this (see `hygaccess.governor`'s
  `no-toxic-co-formulation-blocked-violations`)."
  [active co-ingredients]
  (not (no-toxic-co-formulation? active co-ingredients)))

;; ----------------------------- packaging-format checks -----------------------------

(defn packaging-format-valid?
  "Is `format` one of the closed BOP-appropriate packaging-format
  values? nil/blank or anything needing cold-chain/bulk->1L (never a
  member of `valid-packaging-formats` in the first place) is
  rejected."
  [format]
  (contains? valid-packaging-formats format))

;; ----------------------------- market-entry checks -----------------------------

(defn market-country-known?
  "Is `country` one of the closed representative ISO3166-alpha-2
  target-market codes this actor recognizes at all?"
  [country]
  (contains? valid-market-countries country))

(defn market-approved?
  "Ground-truth check: has `approval`'s own record (the store's
  independent `market-entry-approvals` table entry for a country) been
  marked `:approved?` true? Never taken on the advisor's self-report
  that a market 'should be fine' -- a country must be independently on
  file as approved, mirroring the equipment/batch verified-gate
  pattern. Any real deployment would still need the applicable local
  regulatory approval (e.g. India CDSCO/BIS, GCC conformity bodies,
  ASEAN member-state regulators) -- this actor's own `:approved?` flag
  records that fact, it does not itself grant it."
  [approval]
  (true? (:approved? approval)))

(defn price-ceiling-for [product-type]
  (get price-ceiling-minor product-type))

(defn price-above-ceiling?
  "Ground-truth check: does `price-minor` exceed `product-type`'s own
  closed affordability price ceiling (`price-ceiling-minor`)? A
  `:propose-market-entry` proposal whose price-point exceeds its
  product-type's ceiling is HARD-blocked -- a genuinely new
  domain-specific check for a BOP-market actor (see
  `price-ceiling-minor` docstring for the reasoning behind each
  number)."
  [product-type price-minor]
  (boolean
   (and (number? price-minor)
        (let [ceiling (price-ceiling-for product-type)]
          (and (some? ceiling) (> (double price-minor) (double ceiling)))))))

(defn price-near-ceiling?
  "SOFT signal (not a HARD block): does `price-minor` sit at or above
  80% of `product-type`'s own affordability ceiling -- 'a ... price
  change above some threshold' the governor's confidence/high-stakes
  gate escalates on, distinct from (and lower than) the HARD
  `price-above-ceiling?` block itself."
  [product-type price-minor]
  (boolean
   (and (number? price-minor)
        (let [ceiling (price-ceiling-for product-type)]
          (and (some? ceiling) (>= (double price-minor) (* 0.8 (double ceiling))))))))

;; ----------------------------- marketing-claim checks -----------------------------

(defn claims-for [product-type]
  (get substantiated-claims product-type))

(defn claim-substantiated?
  "Ground-truth check: is `claim` a MEMBER of `product-type`'s own
  closed substantiated-claims set? Never taken on the advisor's
  self-report that a claim is 'backed by data' -- mirrors the
  `soapmfg.registry/fragrance-allergen-labeling-incomplete?`
  ground-truth-not-self-report discipline, applied here to prevent
  unsubstantiated health claims against a vulnerable population."
  [product-type claim]
  (boolean (contains? (or (claims-for product-type) #{}) claim)))

;; ----------------------------- distribution-channel checks -----------------------------

(defn channel-valid?
  [channel]
  (contains? valid-distribution-channels channel))

(defn channel-partner-licensed?
  "Ground-truth check: has `partner`'s own record been marked
  `:licensed?` true? Never taken on the advisor's self-report --
  mirrors the equipment/batch verified-gate ground-truth discipline,
  applied here to a distribution-channel partner (the closest analog
  the task's own reference materials describe as a
  'vendor-eligibility'-style HARD check)."
  [partner]
  (true? (:licensed? partner)))

(defn channel-partner-serves-channel?
  "Ground-truth check: does `partner`'s own record cite `channel`
  among the channels it actually serves? A partner licensed for
  `:institutional-bulk` does not thereby become eligible to be cited
  on a `:ngo-who-program` market-entry proposal."
  [partner channel]
  (contains? (set (:channels partner)) channel))

(defn channel-partner-ready?
  "Combined ground-truth gate: `partner` must exist, be
  `:licensed?` true, AND actually serve the proposal's own declared
  `:channel` before ANY market-entry proposal may cite it."
  [partner channel]
  (and (some? partner)
       (channel-partner-licensed? partner)
       (channel-partner-serves-channel? partner channel)))

;; ----------------------------- GMP raw-material-lot release checks -----------------------------
;;
;; A production batch is only as trustworthy as the incoming
;; raw-material SUPPLY LOT it was formulated from. This is a genuinely
;; new GMP (Good Manufacturing Practice) -style gate this build adds on
;; top of the existing equipment/batch verified-gate pattern: a
;; `:log-production-batch` proposal that cites a `:raw-material-lot-
;; number` must have that lot independently resolve to a real,
;; verified/registered lot that has actually RECEIVED its own
;; Certificate of Analysis (CoA) and whose CoA assay result is itself a
;; PLAUSIBLE purity reading for that active -- never taken on the
;; batch's/advisor's own self-report. See `hygaccess.governor`'s
;; `raw-material-lot-not-verified-violations` /
;; `raw-material-lot-coa-not-received-violations` /
;; `raw-material-lot-assay-implausible-violations`.

(def raw-material-assay-plausibility-pct
  "Closed per-ACTIVE (not `[active product-type]` -- a raw-material lot
  is an incoming SUPPLY lot of the active itself, shared across
  whichever finished product types that active later feeds, not tied to
  one finished-product's own concentration window) plausibility window
  for a raw-material lot's own Certificate-of-Analysis (CoA) assay
  result. This is DELIBERATELY DIFFERENT from `efficacy-window-pct`
  (the FINISHED, formulated product's own concentration window) -- a
  raw-material lot is typically supplied as a concentrated stock the
  plant later dilutes down into the finished product's own efficacy
  window:
  - `:sodium-hypochlorite` 10.0-15.0% -- representative of commercial
    bulk/industrial-grade liquid sodium hypochlorite ('liquid chlorine')
    as commonly supplied for on-site dilution (a widely-cited real-world
    commercial-strength band; actual supplier-to-supplier variance
    exists -- this is a representative plausibility band for catching
    fabricated/nonsensical data, not a precise specification floor or
    ceiling).
  - `:isopropylmethylphenol` 98.0-100.5% -- IPMP is supplied as a
    technical-grade solid active (not a dilute solution), so its own
    CoA assay is expected close to 100% purity; the 100.5% upper bound
    allows for ordinary assay-methodology rounding/measurement
    tolerance, not a claim that >100% purity is itself physically
    meaningful.
  A raw-material lot's own `:coa-assay-pct` far outside this window
  (e.g. a sodium-hypochlorite lot 'assayed' at 40%) is implausible
  supplier/fabricated data, not a real CoA result -- see
  `raw-material-assay-plausible?` below."
  {:sodium-hypochlorite   [10.0 15.0]
   :isopropylmethylphenol [98.0 100.5]})

(defn raw-material-assay-plausibility-window-for [active]
  (get raw-material-assay-plausibility-pct active))

(defn raw-material-assay-plausible?
  "Ground-truth check: is `assay-pct` a number falling within `active`'s
  own closed CoA-assay plausibility window (inclusive)? nil/non-number,
  an unrecognized active, or a value outside the window is implausible
  -- rejected exactly like an out-of-window finished-product
  concentration (`concentration-within-efficacy-window?`)."
  [active assay-pct]
  (boolean
   (and (number? assay-pct)
        (let [[lo hi] (raw-material-assay-plausibility-window-for active)]
          (and (some? lo)
               (>= (double assay-pct) (double lo))
               (<= (double assay-pct) (double hi)))))))

(defn raw-material-lot-verified?
  "Ground-truth check: has `lot`'s own record been marked verified?"
  [lot]
  (true? (:verified? lot)))

(defn raw-material-lot-registered?
  "Ground-truth check: is `lot`'s own record on file in the plant's
  raw-material-lot registry?"
  [lot]
  (true? (:registered? lot)))

(defn raw-material-lot-ready?
  "Combined ground-truth gate: `lot` must be both `:verified?` AND
  `:registered?` before ANY production batch may cite it -- mirrors the
  equipment/batch verified-gate pattern exactly, applied to a
  raw-material supply lot."
  [lot]
  (and (raw-material-lot-verified? lot) (raw-material-lot-registered? lot)))

(defn raw-material-lot-coa-received?
  "Ground-truth check: has `lot`'s own record actually RECEIVED its own
  Certificate of Analysis (`:coa-received?` true)? A lot without a CoA
  on file may not back a production batch, regardless of how plausible
  any claimed assay number looks."
  [lot]
  (true? (:coa-received? lot)))

(defn raw-material-lot-release-eligible?
  "Combined ground-truth gate for GMP raw-material release: `lot` must
  exist, be `:verified?` AND `:registered?`, have actually RECEIVED its
  own CoA, AND that CoA's own `:coa-assay-pct` must fall within its own
  active's closed plausibility window -- never taken on the batch's/
  advisor's own self-report."
  [lot]
  (and (some? lot)
       (raw-material-lot-ready? lot)
       (raw-material-lot-coa-received? lot)
       (raw-material-assay-plausible? (:active lot) (:coa-assay-pct lot))))

;; ----------------------------- in-process QC (IPQC) mixing-homogeneity checks -----------------------------
;;
;; `:mixing-homogeneity-cov-pct` is the coefficient-of-variation (%) of
;; active-ingredient concentration across a batch at end-of-mixing. In
;; a real deployment this number would come from EITHER a physical
;; in-process QC (IPQC) sample drawn from several points in the mixing
;; tank, OR a CFD (computational fluid dynamics) mixing-tank simulation
;; -- see the sibling repo `kotoba-lang/kami-app-hygaccess-plant`
;; (referenced here by NAME ONLY, a loose EDN-field-level convention
;; this fleet uses between independently-governed actors/repos; this
;; repo does NOT fetch, read, or depend on that repo's code).

(def homogeneity-cov-threshold-pct
  "The maximum acceptable coefficient-of-variation (CoV, %) of
  active-ingredient concentration across a batch at end-of-mixing
  ('mixing homogeneity') this actor will accept before a batch's own
  in-process-QC (IPQC) record is treated as non-conforming. 5.0% is
  this actor's own REPRESENTATIVE acceptance threshold for a simple
  liquid-liquid dilution mixing process -- general pharmaceutical/
  chemical blend-uniformity practice treats single-digit-percent CoV as
  a reasonable 'well-mixed' bar for this class of process. This is NOT
  a claim that 5.0% is itself drawn from a specific regulatory citation
  for these exact products; it is this actor's own documented,
  representative choice, open to revision if a real deployment's
  applicable GMP guidance sets a different number."
  5.0)

(defn homogeneity-within-threshold?
  "Ground-truth check: is `cov-pct` a non-negative number at or below
  `homogeneity-cov-threshold-pct`? nil/non-number/negative is treated
  as not-within-threshold (a fabricated or missing IPQC reading is
  never treated as passing)."
  [cov-pct]
  (boolean
   (and (number? cov-pct)
        (>= (double cov-pct) 0.0)
        (<= (double cov-pct) (double homogeneity-cov-threshold-pct)))))

;; ----------------------------- Certificate of Analysis (CoA) / batch-release sign-off checks -----------------------------

(defn coa-pass?
  "Ground-truth check: does `batch`'s own recorded `:coa` map declare
  `:coa-pass? true`? Never taken on a shipment proposal's own
  self-report -- the shipment proposal does not even carry this field;
  it is read from the batch's OWN already-committed record."
  [batch]
  (true? (:coa-pass? (:coa batch))))

(defn batch-release-qc-complete?
  "Combined ground-truth gate (GMP 'batch release' sign-off): `batch`
  must independently carry BOTH a passing Certificate of Analysis
  (`:coa {:coa-pass? true ...}`) AND an in-process mixing-homogeneity
  reading within `homogeneity-cov-threshold-pct` -- both re-derived
  from the batch's OWN recorded fields, never from a shipment
  proposal's own self-report. This is the 'batch release' sign-off a
  real GMP QA function performs before ANY batch may ship -- see
  `hygaccess.governor/batch-release-qc-incomplete-violations`."
  [batch]
  (and (coa-pass? batch)
       (homogeneity-within-threshold? (:mixing-homogeneity-cov-pct (:ipqc batch)))))

;; ----------------------------- commercial catalog (SKU price ground truth) -----------------------------
;;
;; `:propose-sales-order` NEVER moves real money -- see README `What
;; this actor does NOT do`. A price here is a plain reference number on
;; a record, nothing more: no payment gateway, no processor, no
;; balance/wallet/ledger-of-funds concept. This table exists purely so
;; a proposed order's own claimed price can be independently checked
;; against a ground-truth catalog number, the same "ground truth, not
;; self-report" discipline as every other check in this repo.

(def sku-catalog
  "Closed SKU -> `{:product-type .. :price-minor ..}` ground-truth
  catalog, MIRRORING `products.edn` at repo root (`:product/id`
  `:product/price-minor` `:hygaccess.product/product-type`) -- kept
  here as `.cljc` pure data (matching every other closed table in this
  namespace) rather than read from the EDN file at runtime, since
  `src/` is portable JVM/cljs/nbb and this repo's own established
  convention is closed-table-as-code, not file-I/O-at-runtime (see
  `price-ceiling-minor`/`efficacy-window-pct`).
  `hygaccess.governor/sales-order-price-mismatch-violations` reads THIS
  table, never a `:propose-sales-order` proposal's own self-reported
  price, as ground truth. `registry-test.cljc` asserts this table stays
  in sync with `products.edn`'s own four SKUs."
  {"int.hygaccess.water-purification-drops"
   {:product-type :water-purification-drops :price-minor 350000}
   "int.hygaccess.surface-disinfectant"
   {:product-type :surface-disinfectant :price-minor 700000}
   "int.hygaccess.antibacterial-soap-bar"
   {:product-type :antibacterial-soap :price-minor 150000}
   "int.hygaccess.antibacterial-liquid-soap"
   {:product-type :antibacterial-soap :price-minor 250000}})

(defn sku-known? [sku] (contains? sku-catalog sku))
(defn sku-price-for [sku] (:price-minor (get sku-catalog sku)))
(defn sku-product-type-for [sku] (:product-type (get sku-catalog sku)))

(defn sku-price-mismatch?
  "Ground-truth check: does `price-minor` (a `:propose-sales-order`
  proposal's own claimed price) differ from `sku`'s own registered
  price in `sku-catalog`? An unknown SKU is treated as a mismatch -- no
  ground-truth price exists to confirm against, so it can never be
  taken on faith."
  [sku price-minor]
  (let [registered (sku-price-for sku)]
    (or (nil? registered) (not= registered price-minor))))

(def max-plausible-order-quantity
  "100000 units -- this actor's own documented REPRESENTATIVE ceiling
  for a single BOP go-to-market order. An NGO/government/institutional
  bulk buyer placing an order for more than 100,000 individual
  bottles/sachets/bars in ONE order is implausible for this program's
  own scale and is treated as fabricated/data-entry-error data, not a
  real order."
  100000)

(defn order-quantity-valid?
  "Physically plausible order quantity -- a positive number at or below
  `max-plausible-order-quantity`, mirrors `off-spec-rate-valid?`'s own
  'reject a physically implausible reading' discipline."
  [quantity]
  (boolean
   (and (number? quantity)
        (pos? quantity)
        (<= (double quantity) (double max-plausible-order-quantity)))))

;; ----------------------------- fulfillment-status state machine -----------------------------
;;
;; ENGINE REWIRE (this build): the core ordered-stage/exit-stage
;; validity now DELEGATES to `kotoba.crm.pipeline` (`kotoba-lang/crm`)'s
;; generic pipeline engine -- the SAME technical commons
;; `cloud-itonami.regulatory-tracker.core` (see `hygaccess.regulatory`)
;; itself reuses -- instead of a bespoke hand-rolled transition table.

(def fulfillment-ordered-stages
  "`:pending` -> `:packed` -> `:shipped` -> `:delivered`, the linear
  happy-path chain handed to `kotoba.crm.pipeline` as its own
  `ordered-stages` argument. `:delivered` is `kotoba.crm.pipeline`'s
  own 'reached the end, successfully' terminal stage (its last entry)."
  [:pending :packed :shipped :delivered])

(def fulfillment-exit-stages
  "`:cancelled` -- an ABANDONMENT outcome, handed to `kotoba.crm.
  pipeline` as its own `exit-stages` argument. Per that library's own
  semantics, an exit-stage is reachable from ANY non-terminal ordered
  stage, which on its own would also permit `:shipped` -> `:cancelled`
  (`:shipped` is not itself terminal -- it still transitions forward to
  `:delivered`). This actor's own domain rule is STRICTER than that
  generic default (see `fulfillment-transition-valid?` below for the
  additional layer enforcing it) -- once `:shipped`, the physical
  parcel is already in transit; 'cancelling' after dispatch is a
  real-world return/refusal process, not a same-op cancellation."
  #{:cancelled})

(def fulfillment-statuses
  #{:pending :packed :shipped :delivered :cancelled})

(def fulfillment-transitions
  "Closed transition table, DERIVED from `kotoba.crm.pipeline/next-
  stages` over `fulfillment-ordered-stages`/`fulfillment-exit-stages` --
  kept as a map for backward-compat/introspection, but the ENGINE
  decision now lives in the shared library. NOTE: this raw derived
  table alone still reflects the shared library's own generic 'exit
  from any non-terminal stage' default (i.e. it lists `:shipped ->
  #{:cancelled :delivered}`) -- the STRICTER 'no cancellation once
  shipped' domain rule this actor actually enforces is NOT encoded in
  this table; it is layered on top by `fulfillment-transition-valid?`
  below (the fn every caller/governor check actually calls), so read
  this map as 'structurally reachable per the shared engine', not as
  this actor's own final word on validity."
  (into {} (map (fn [s] [s (pipeline/next-stages fulfillment-ordered-stages fulfillment-exit-stages s)]))
        fulfillment-statuses))

(defn fulfillment-transition-valid?
  "Is `from` -> `to` an allowed single-step fulfillment-status
  transition? Delegates the core ordered-stage/exit-stage structural
  validity to `kotoba.crm.pipeline/valid-transition?` (ADDITIVE reuse,
  not a rewrite of that engine), then layers ONE additional,
  hygiene-access-specific domain constraint on top: `:cancelled` is
  valid ONLY from a PRE-SHIPPED state (`:pending`/`:packed`), narrower
  than `kotoba.crm.pipeline`'s own generic 'any non-terminal stage'
  exit-stage default (see `fulfillment-exit-stages` docstring for why).
  This mirrors exactly how `cloud-itonami.regulatory-tracker.core`'s own
  docs describe a caller staying stricter than the shared engine: 'a
  caller whose own ... track genuinely needs the stricter rule can layer
  that ADDITIONAL check in its own governor' -- unlike
  `hygaccess.regulatory` (which accepted the shared library's own looser
  default, see that ns docstring), THIS domain rule is kept strict
  because existing test coverage
  (`fulfillment-transition-cancelled-only-from-pre-shipped-states` in
  `registry_test.cljc`) already asserts it, and a dispatched parcel is a
  genuinely different real-world state from an undispatched order."
  [from to]
  (boolean
   (and (pipeline/valid-transition? fulfillment-ordered-stages fulfillment-exit-stages from to)
        (or (not= to :cancelled)
            (contains? #{:pending :packed} from)))))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant coordinator's/market-entry approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  formulation/filling-line maintenance window against a verified,
  registered piece of equipment. Pure function -- does not actuate the
  formulation/filling line or execute any maintenance; it builds the
  RECORD a plant coordinator would keep."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound hygiene-product shipment against a verified, registered
  production batch. Pure function -- does not dispatch any real
  freight carrier; it builds the RECORD a plant coordinator would
  keep."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn register-packaging-design
  "Validate + construct the PACKAGING-DESIGN DRAFT -- a proposed
  BOP-appropriate packaging format + net-content declaration for a
  product type. Pure function -- does not commission any real
  packaging line."
  [design-id sequence]
  (when-not (and design-id (not= design-id ""))
    (throw (ex-info "packaging-design: design_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "packaging-design: sequence must be >= 0" {})))
  (let [design-number (str "PKG-" (zero-pad sequence 6))
        record {"record_id" design-number
                "kind" "packaging-design-draft"
                "design_id" design-id
                "immutable" true}]
    {"record" record "design_number" design-number
     "certificate" (unsigned-certificate "PackagingDesign" design-number design-number)}))

(defn register-market-entry
  "Validate + construct the MARKET-ENTRY DRAFT -- a proposed bundle of
  target-country + price-point + distribution-channel + channel-
  partner for a product type. Pure function -- does not itself grant
  any real regulatory market-access approval (see README `What this
  actor does NOT do`) and does not execute any real sale."
  [entry-id sequence]
  (when-not (and entry-id (not= entry-id ""))
    (throw (ex-info "market-entry: entry_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "market-entry: sequence must be >= 0" {})))
  (let [entry-number (str "MKT-" (zero-pad sequence 6))
        record {"record_id" entry-number
                "kind" "market-entry-draft"
                "entry_id" entry-id
                "immutable" true}]
    {"record" record "entry_number" entry-number
     "certificate" (unsigned-certificate "MarketEntry" entry-number entry-number)}))

(defn register-marketing-claim
  "Validate + construct the MARKETING-CLAIM DRAFT -- a proposed
  substantiated marketing/health claim for a product type. Pure
  function -- does not itself grant any real regulatory/medical
  claim-approval authority (see README `What this actor does NOT
  do`)."
  [claim-id sequence]
  (when-not (and claim-id (not= claim-id ""))
    (throw (ex-info "marketing-claim: claim_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "marketing-claim: sequence must be >= 0" {})))
  (let [claim-number (str "CLM-" (zero-pad sequence 6))
        record {"record_id" claim-number
                "kind" "marketing-claim-draft"
                "claim_id" claim-id
                "immutable" true}]
    {"record" record "claim_number" claim-number
     "certificate" (unsigned-certificate "MarketingClaim" claim-number claim-number)}))

(defn register-mes-reading
  "Validate + construct the MES/CFD-TELEMETRY-READING DRAFT -- a logged
  equipment/batch telemetry reading (mock in this build -- see
  `hygaccess.mes`) tied to an existing production batch. Pure function
  -- does not poll any real plant MES; it builds the RECORD a plant
  coordinator would keep."
  [reading-id sequence]
  (when-not (and reading-id (not= reading-id ""))
    (throw (ex-info "mes-reading: reading_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "mes-reading: sequence must be >= 0" {})))
  (let [reading-number (str "MES-" (zero-pad sequence 6))
        record {"record_id" reading-number
                "kind" "mes-reading-draft"
                "reading_id" reading-id
                "immutable" true}]
    {"record" record "reading_number" reading-number
     "certificate" (unsigned-certificate "MESReading" reading-number reading-number)}))

(defn register-sales-order
  "Validate + construct the SALES-ORDER (quote/purchase-order) DRAFT --
  a proposed buyer-reference + SKU + quantity + price bundle. Pure
  function -- does NOT execute any real sale, payment, or invoice
  settlement (see README `What this actor does NOT do`); it builds the
  DRAFT record a go-to-market coordinator would keep."
  [order-id sequence]
  (when-not (and order-id (not= order-id ""))
    (throw (ex-info "sales-order: order_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "sales-order: sequence must be >= 0" {})))
  (let [order-number (str "ORD-" (zero-pad sequence 6))
        record {"record_id" order-number
                "kind" "sales-order-draft"
                "order_id" order-id
                "immutable" true}]
    {"record" record "order_number" order-number
     "certificate" (unsigned-certificate "SalesOrder" order-number order-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
