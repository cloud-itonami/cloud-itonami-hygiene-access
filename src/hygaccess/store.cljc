(ns hygaccess.store
  "SSoT for the low-cost hygiene/disinfectant active-ingredient
  commercialization actor, behind a `Store` protocol so the backend is
  a swap, not a rewrite -- the same seam every `cloud-itonami-isic-*`
  actor in this fleet uses.

  Scope note: like its closest chemical-process-plant analogs
  (`cloud-itonami-isic-2023`'s `soapmfg.store`,
  `cloud-itonami-isic-2029`'s `adhesivemfg.store`), this build ships a
  single `MemStore` backend only (atom of EDN) -- the deterministic
  default for dev/tests/demo, no deps.

  Entity kinds:
    - `batches`             -- the central formulation entity. A
                                production batch's product-type/
                                active/concentration-pct/weight/
                                off-spec-rate/co-ingredients record.
                                `:verified?` marks whether the batch's
                                own claims have actually been
                                QC-inspected; `:registered?` marks
                                whether it is on file in the plant's
                                production ledger; `:shipped-weight-kg`
                                tracks the batch's own
                                cumulative-shipped ground truth.
    - `equipment`            -- a formulation/filling-line unit's own
                                record. `:verified?`/`:registered?`
                                track whether it has actually been
                                inspected/commissioned and is on file.
    - `maintenance`          -- a scheduled formulation/filling-line
                                maintenance-window DRAFT against a
                                piece of equipment
                                (`hygaccess.registry/
                                register-maintenance`). Dedicated
                                `:scheduled?` double-schedule guard.
    - `shipments`            -- a proposed outbound hygiene-product
                                shipment DRAFT
                                (`hygaccess.registry/register-shipment`).
    - `packaging-designs`    -- a proposed packaging-format + net-
                                content DRAFT for a product type
                                (`hygaccess.registry/
                                register-packaging-design`).
    - `market-entries`       -- a proposed target-country +
                                price-point + distribution-channel +
                                channel-partner bundle DRAFT for a
                                product type (`hygaccess.registry/
                                register-market-entry`).
    - `marketing-claims`     -- a proposed substantiated marketing/
                                health claim DRAFT for a product type
                                (`hygaccess.registry/
                                register-marketing-claim`).
    - `market-entry-approvals` -- GROUND TRUTH, not self-report:
                                one entry per target-market ISO3166-
                                alpha-2 country code,
                                `{:country .. :approved? bool}`. A real
                                deployment would still need the
                                applicable local regulatory approval
                                (e.g. India CDSCO/BIS, GCC conformity
                                bodies, ASEAN member-state regulators)
                                -- this table records that fact, it
                                does not itself grant it.
    - `channel-partners`     -- GROUND TRUTH, not self-report: one
                                entry per distribution-channel partner,
                                `{:id .. :licensed? bool :channels
                                #{..}}`.
    - `raw-material-lots`    -- GROUND TRUTH, not self-report: one
                                entry per incoming raw-material SUPPLY
                                lot of an active ingredient,
                                `{:lot-number .. :active .. :supplier ..
                                :coa-received? bool :coa-assay-pct
                                <number> :verified? bool :registered?
                                bool}`. A `batches` record may cite one
                                by `:raw-material-lot-number`; a batch's
                                own `:ipqc` map (`:ph-check-pass?
                                :assay-mid-batch-pct :mixing-
                                homogeneity-cov-pct`) and `:coa` map
                                (`:coa-assay-result-pct :coa-tested-by
                                :coa-date :coa-pass?`) are the FINISHED
                                batch's own in-process-QC and
                                Certificate-of-Analysis / batch-release
                                sign-off records -- see
                                `hygaccess.registry`'s GMP raw-material-
                                lot-release / IPQC / CoA sections.
    - `mes-readings`         -- a logged MES/CFD-sourced equipment/batch
                                telemetry reading DRAFT tied to an
                                existing production batch
                                (`hygaccess.registry/register-mes-
                                reading`, `hygaccess.mes` for the
                                integration contract + mock). Each
                                reading is a NEW numbered record
                                (`MES-000000` ...), never an update to
                                an existing one.
    - `regulatory-submissions` -- GROUND TRUTH regulatory-submission-
                                STATUS TRACKING per (market, product-
                                type) pair, `{:market .. :product-type ..
                                :status .. :filed-by .. :filing-date ..
                                :agency-reference ..}` -- see
                                `hygaccess.regulatory` for the closed
                                transition table. STATUS TRACKING ONLY;
                                does not itself file anything with any
                                real regulatory authority.
    - `sales-orders`         -- a proposed quote/purchase-order DRAFT
                                (`hygaccess.registry/register-sales-
                                order`), buyer-reference + SKU +
                                quantity + price, plus its own
                                `:fulfillment-status`
                                (`:pending`/`:packed`/`:shipped`/
                                `:delivered`/`:cancelled`, see
                                `hygaccess.registry/fulfillment-
                                transitions`) updated in place by
                                `:update-fulfillment-status`. NEVER a
                                real sale, payment, or fund movement --
                                a price here is a plain reference number
                                on a record, nothing more.

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: which batch was logged, which
  maintenance was scheduled against a verified/registered equipment
  unit, which shipment was coordinated and at what
  independently-recomputed weight, which packaging design was
  proposed, which market-entry was proposed against an
  independently-approved country/ceiling-compliant price/licensed
  channel-partner, which marketing claim was proposed against the
  closed substantiated-claims set, approved by whom, which safety
  concern was flagged -- is always a query over an immutable log."
  (:require [hygaccess.registry :as registry]))

(defprotocol Store
  (batch [s id])
  (all-batches [s])
  (equipment-unit [s id])
  (all-equipment [s])
  (maintenance [s id])
  (all-maintenance [s])
  (shipment [s id])
  (packaging-design [s id])
  (market-entry [s id])
  (marketing-claim [s id])
  (market-approval [s country] "ground-truth market-entry-approval record for a country")
  (all-market-approvals [s] "every ground-truth market-entry-approval record on file")
  (channel-partner [s id] "ground-truth channel-partner record")
  (raw-material-lot [s lot-number] "ground-truth raw-material supply-lot record")
  (all-raw-material-lots [s])
  (mes-reading [s id] "a logged MES/CFD-sourced telemetry-reading record")
  (all-mes-readings [s])
  (mes-readings-for-batch [s batch-id] "every MES reading on file for a batch, oldest-first")
  (regulatory-submission [s id] "ground-truth regulatory-submission-status record")
  (all-regulatory-submissions [s])
  (sales-order [s id] "a proposed sales-order record, incl. its own :fulfillment-status")
  (all-sales-orders [s])
  (safety-concerns [s] "the append-only safety-concern log")
  (ledger [s])
  (maintenance-history [s] "the append-only maintenance-schedule history (hygaccess.registry drafts)")
  (shipment-history [s] "the append-only shipment-coordination history (hygaccess.registry drafts)")
  (packaging-design-history [s] "the append-only packaging-design history")
  (market-entry-history [s] "the append-only market-entry history")
  (marketing-claim-history [s] "the append-only marketing-claim history")
  (mes-reading-history [s] "the append-only MES/CFD-telemetry-reading history")
  (sales-order-history [s] "the append-only sales-order history")
  (next-maintenance-sequence [s])
  (next-shipment-sequence [s])
  (next-packaging-design-sequence [s])
  (next-market-entry-sequence [s])
  (next-marketing-claim-sequence [s])
  (next-mes-reading-sequence [s])
  (next-sales-order-sequence [s])
  (maintenance-already-scheduled? [s maintenance-id] "has this maintenance window already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-batches [s batches] "replace/seed the batch directory (map id->batch)")
  (with-equipment [s equipment] "replace/seed the equipment directory (map id->equipment)")
  (with-market-approvals [s approvals] "replace/seed the market-entry-approvals directory (map country->approval)")
  (with-channel-partners [s partners] "replace/seed the channel-partners directory (map id->partner)")
  (with-raw-material-lots [s lots] "replace/seed the raw-material-lots directory (map lot-number->lot)")
  (with-regulatory-submissions [s subs] "replace/seed the regulatory-submissions directory (map id->submission)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-batches []
  ;; batch-001/batch-002 additionally carry a full clean GMP trail
  ;; (raw-material-lot-number -> a verified/registered/CoA-received/
  ;; plausible-assay lot; :ipqc with a within-threshold mixing-
  ;; homogeneity CoV; :coa with a passing Certificate of Analysis) so
  ;; both may auto-commit :log-production-batch patches AND clear the
  ;; new `batch-release-qc-incomplete-violations` shipment gate by
  ;; default. batch-003 is deliberately left WITHOUT any of these three
  ;; new fields (nil) -- it is UNVERIFIED/unregistered and has not yet
  ;; been through raw-material/IPQC/CoA release at all, matching its
  ;; existing pre-QC status.
  {"batch-001" {:id "batch-001" :product-type :water-purification-drops
                :active :sodium-hypochlorite :concentration-pct 1.0
                :weight-kg 500.0 :off-spec-rate-pct 0.5
                :co-ingredients #{}
                :verified? true :registered? true
                :shipped-weight-kg 100.0
                :last-assessed "2026-07-01"
                :raw-material-lot-number "RM-LOT-NAOCL-001"
                :ipqc {:ph-check-pass? true :assay-mid-batch-pct 1.0
                       :mixing-homogeneity-cov-pct 2.1}
                :coa {:coa-assay-result-pct 1.0 :coa-tested-by "QA-lab-1"
                      :coa-date "2026-07-01" :coa-pass? true}}
   "batch-002" {:id "batch-002" :product-type :surface-disinfectant
                :active :sodium-hypochlorite :concentration-pct 0.1
                :weight-kg 800.0 :off-spec-rate-pct 1.0
                :co-ingredients #{}
                :verified? true :registered? true
                :shipped-weight-kg 750.0
                :last-assessed "2026-07-01"
                :raw-material-lot-number "RM-LOT-NAOCL-002"
                :ipqc {:ph-check-pass? true :assay-mid-batch-pct 0.1
                       :mixing-homogeneity-cov-pct 3.4}
                :coa {:coa-assay-result-pct 0.1 :coa-tested-by "QA-lab-1"
                      :coa-date "2026-07-01" :coa-pass? true}}
   "batch-003" {:id "batch-003" :product-type :antibacterial-soap
                :active :isopropylmethylphenol :concentration-pct 0.15
                :weight-kg 300.0 :off-spec-rate-pct 0.8
                :co-ingredients #{}
                :verified? false :registered? false
                :shipped-weight-kg 0.0
                :last-assessed "2026-06-20"}})

(defn- sample-equipment []
  {"line-001" {:id "line-001" :kind :formulation-mixing-tank
               :verified? true :registered? true
               :last-maintenance-date "2026-06-01"}
   "line-002" {:id "line-002" :kind :filling-line
               :verified? false :registered? false
               :last-maintenance-date nil}})

(defn- sample-market-approvals []
  ;; Ground truth, not self-report. A real deployment would still need
  ;; the applicable local regulatory approval (India CDSCO/BIS, GCC
  ;; conformity bodies, ASEAN member-state regulators) -- this table
  ;; records that fact, it does not itself grant it. Mix of approved
  ;; and not-yet-approved so governor tests exercise both paths.
  {"IN" {:country "IN" :approved? true}
   "BD" {:country "BD" :approved? true}
   "ID" {:country "ID" :approved? true}
   "PH" {:country "PH" :approved? true}
   "SA" {:country "SA" :approved? true}
   "AE" {:country "AE" :approved? true}
   "PK" {:country "PK" :approved? false}
   "EG" {:country "EG" :approved? false}})

(defn- sample-channel-partners []
  {"partner-ngo-1"    {:id "partner-ngo-1" :name "cross-border WASH NGO consortium"
                        :licensed? true :channels #{:ngo-who-program}}
   "partner-gov-1"    {:id "partner-gov-1" :name "national procurement office"
                        :licensed? true :channels #{:government-procurement}}
   "partner-mfi-1"    {:id "partner-mfi-1" :name "microfinance social-enterprise network"
                        :licensed? true :channels #{:microfinance-social-enterprise}}
   "partner-retail-1" {:id "partner-retail-1" :name "licensed informal-retail aggregator"
                        :licensed? true :channels #{:licensed-informal-retail-aggregator}}
   "partner-bulk-1"   {:id "partner-bulk-1" :name "institutional bulk buyer"
                        :licensed? true :channels #{:institutional-bulk}}
   "partner-retail-2" {:id "partner-retail-2" :name "unlicensed informal-retail aggregator (pending review)"
                        :licensed? false :channels #{:licensed-informal-retail-aggregator}}})

(defn- sample-raw-material-lots []
  ;; GROUND TRUTH, not self-report. Mirrors the equipment/market-
  ;; approval/channel-partner seed pattern: a mix of clean lots (feed
  ;; batch-001/batch-002 above) plus dedicated NOT-verified /
  ;; coa-not-received / assay-implausible lots for HARD-hold test
  ;; coverage of `hygaccess.governor`'s new raw-material-lot checks.
  {"RM-LOT-NAOCL-001" {:lot-number "RM-LOT-NAOCL-001" :active :sodium-hypochlorite
                        :supplier "Gulf Chlor-Alkali Co." :coa-received? true
                        :coa-assay-pct 12.5 :verified? true :registered? true}
   "RM-LOT-NAOCL-002" {:lot-number "RM-LOT-NAOCL-002" :active :sodium-hypochlorite
                        :supplier "Gulf Chlor-Alkali Co." :coa-received? true
                        :coa-assay-pct 11.8 :verified? true :registered? true}
   "RM-LOT-IPMP-001"   {:lot-number "RM-LOT-IPMP-001" :active :isopropylmethylphenol
                         :supplier "OTC Actives Trading Ltd." :coa-received? true
                         :coa-assay-pct 99.2 :verified? true :registered? true}
   "RM-LOT-NAOCL-003" {:lot-number "RM-LOT-NAOCL-003" :active :sodium-hypochlorite
                        :supplier "unverified spot-market supplier" :coa-received? true
                        :coa-assay-pct 12.0 :verified? false :registered? false}
   "RM-LOT-NAOCL-004" {:lot-number "RM-LOT-NAOCL-004" :active :sodium-hypochlorite
                        :supplier "Gulf Chlor-Alkali Co." :coa-received? false
                        :coa-assay-pct nil :verified? true :registered? true}
   "RM-LOT-NAOCL-005" {:lot-number "RM-LOT-NAOCL-005" :active :sodium-hypochlorite
                        :supplier "unverified spot-market supplier" :coa-received? true
                        :coa-assay-pct 40.0 :verified? true :registered? true}})

(defn- sample-regulatory-submissions []
  ;; ONE demonstration :approved regulatory-submission record (IN /
  ;; water-purification-drops, with full human-supplied evidence) so
  ;; `hygaccess.regulatory/market-approval-without-submission-warnings`
  ;; has at least one "backed" approved market to contrast against the
  ;; five OTHER approved markets (BD/ID/PH/SA/AE) that remain
  ;; intentionally UNBACKED -- demonstrating both branches of the new
  ;; non-breaking consistency WARNING (never a HARD block, see
  ;; `hygaccess.regulatory` ns docstring 'NON-BREAKING WIRING NOTE').
  {"REG-IN-water-purification-drops"
   {:id "REG-IN-water-purification-drops" :market "IN"
    :product-type :water-purification-drops :status :approved
    :filed-by "local regulatory counsel (placeholder)" :filing-date "2026-05-01"
    :agency-reference "CDSCO-REF-2026-0001-PLACEHOLDER"}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-maintenance!
  [s maintenance-id equipment-id]
  (let [seq-n (next-maintenance-sequence s)
        result (registry/register-maintenance maintenance-id equipment-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :maintenance-number (get result "maintenance_number")}}))

(defn- propose-shipment!
  [s shipment-id]
  (let [seq-n (next-shipment-sequence s)
        result (registry/register-shipment shipment-id seq-n)]
    {:result result
     :patch {:shipment-number (get result "shipment_number")}}))

(defn- propose-packaging-design!
  [s design-id]
  (let [seq-n (next-packaging-design-sequence s)
        result (registry/register-packaging-design design-id seq-n)]
    {:result result
     :patch {:design-number (get result "design_number")}}))

(defn- propose-market-entry!
  [s entry-id]
  (let [seq-n (next-market-entry-sequence s)
        result (registry/register-market-entry entry-id seq-n)]
    {:result result
     :patch {:entry-number (get result "entry_number")}}))

(defn- propose-marketing-claim!
  [s claim-id]
  (let [seq-n (next-marketing-claim-sequence s)
        result (registry/register-marketing-claim claim-id seq-n)]
    {:result result
     :patch {:claim-number (get result "claim_number")}}))

(defn- record-mes-reading!
  [s reading-id]
  (let [seq-n (next-mes-reading-sequence s)
        result (registry/register-mes-reading reading-id seq-n)]
    {:result result
     :patch {:reading-number (get result "reading_number")}}))

(defn- propose-sales-order!
  [s order-id]
  (let [seq-n (next-sales-order-sequence s)
        result (registry/register-sales-order order-id seq-n)]
    {:result result
     :patch {:order-number (get result "order_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (batch [_ id] (get-in @a [:batches id]))
  (all-batches [_] (sort-by :id (vals (:batches @a))))
  (equipment-unit [_ id] (get-in @a [:equipment id]))
  (all-equipment [_] (sort-by :id (vals (:equipment @a))))
  (maintenance [_ id] (get-in @a [:maintenance id]))
  (all-maintenance [_] (sort-by :id (vals (:maintenance @a))))
  (shipment [_ id] (get-in @a [:shipments id]))
  (packaging-design [_ id] (get-in @a [:packaging-designs id]))
  (market-entry [_ id] (get-in @a [:market-entries id]))
  (marketing-claim [_ id] (get-in @a [:marketing-claims id]))
  (market-approval [_ country] (get-in @a [:market-entry-approvals country]))
  (all-market-approvals [_] (vec (vals (:market-entry-approvals @a))))
  (channel-partner [_ id] (get-in @a [:channel-partners id]))
  (raw-material-lot [_ lot-number] (get-in @a [:raw-material-lots lot-number]))
  (all-raw-material-lots [_] (sort-by :lot-number (vals (:raw-material-lots @a))))
  (mes-reading [_ id] (get-in @a [:mes-readings id]))
  (all-mes-readings [_] (sort-by :reading-number (vals (:mes-readings @a))))
  (mes-readings-for-batch [_ batch-id]
    (->> (vals (:mes-readings @a))
         (filter #(= batch-id (:batch-id %)))
         (sort-by :reading-number)))
  (regulatory-submission [_ id] (get-in @a [:regulatory-submissions id]))
  (all-regulatory-submissions [_] (vec (vals (:regulatory-submissions @a))))
  (sales-order [_ id] (get-in @a [:sales-orders id]))
  (all-sales-orders [_] (sort-by :order-number (vals (:sales-orders @a))))
  (safety-concerns [_] (:safety-concerns @a))
  (ledger [_] (:ledger @a))
  (maintenance-history [_] (:maintenance-history @a))
  (shipment-history [_] (:shipment-history @a))
  (packaging-design-history [_] (:packaging-design-history @a))
  (market-entry-history [_] (:market-entry-history @a))
  (marketing-claim-history [_] (:marketing-claim-history @a))
  (mes-reading-history [_] (:mes-reading-history @a))
  (sales-order-history [_] (:sales-order-history @a))
  (next-maintenance-sequence [_] (:maintenance-sequence @a 0))
  (next-shipment-sequence [_] (:shipment-sequence @a 0))
  (next-packaging-design-sequence [_] (:packaging-design-sequence @a 0))
  (next-market-entry-sequence [_] (:market-entry-sequence @a 0))
  (next-marketing-claim-sequence [_] (:marketing-claim-sequence @a 0))
  (next-mes-reading-sequence [_] (:mes-reading-sequence @a 0))
  (next-sales-order-sequence [_] (:sales-order-sequence @a 0))
  (maintenance-already-scheduled? [_ maintenance-id]
    (boolean (get-in @a [:maintenance maintenance-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :batch/upsert)
      (swap! a update-in [:batches (first path)] merge (assoc value :id (first path)))

      (= effect :maintenance/schedule)
      (let [maintenance-id (first path)
            equipment-id (:equipment-id value)
            {:keys [result patch]} (schedule-maintenance! s maintenance-id equipment-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :maintenance-sequence (fnil inc 0))
                       (update-in [:maintenance maintenance-id] merge (assoc value :id maintenance-id) patch)
                       (update :maintenance-history registry/append result)
                       (update-in [:equipment equipment-id :last-scheduled-maintenance-date]
                                  (fn [_prev] (:scheduled-date value))))))
        result)

      (= effect :safety-concern/flag)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :safety-concerns conj concern)
        concern)

      (= effect :shipment/propose)
      (let [shipment-id (first path)
            batch-id (:batch-id value)
            {:keys [result patch]} (propose-shipment! s shipment-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :shipment-sequence (fnil inc 0))
                       (update-in [:shipments shipment-id] merge (assoc value :id shipment-id) patch)
                       (update :shipment-history registry/append result)
                       (update-in [:batches batch-id :shipped-weight-kg]
                                  (fn [prev]
                                    (+ (double (or prev 0.0))
                                       (double (or (:weight-kg value) 0.0))))))))
        result)

      (= effect :packaging-design/propose)
      (let [design-id (first path)
            {:keys [result patch]} (propose-packaging-design! s design-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :packaging-design-sequence (fnil inc 0))
                       (update-in [:packaging-designs design-id] merge (assoc value :id design-id) patch)
                       (update :packaging-design-history registry/append result))))
        result)

      (= effect :market-entry/propose)
      (let [entry-id (first path)
            {:keys [result patch]} (propose-market-entry! s entry-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :market-entry-sequence (fnil inc 0))
                       (update-in [:market-entries entry-id] merge (assoc value :id entry-id) patch)
                       (update :market-entry-history registry/append result))))
        result)

      (= effect :marketing-claim/propose)
      (let [claim-id (first path)
            {:keys [result patch]} (propose-marketing-claim! s claim-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :marketing-claim-sequence (fnil inc 0))
                       (update-in [:marketing-claims claim-id] merge (assoc value :id claim-id) patch)
                       (update :marketing-claim-history registry/append result))))
        result)

      (= effect :mes-reading/record)
      (let [reading-id (first path)
            {:keys [result patch]} (record-mes-reading! s reading-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :mes-reading-sequence (fnil inc 0))
                       (update-in [:mes-readings reading-id] merge (assoc value :id reading-id) patch)
                       (update :mes-reading-history registry/append result))))
        result)

      ;; STATUS TRACKING ONLY -- does not file anything with any real
      ;; regulatory system. Merge-into-existing-entity pattern (like
      ;; :batch/upsert), not a new numbered draft record: an ongoing
      ;; per-(market, product-type) status field, not a fresh proposal
      ;; artifact each time.
      (= effect :regulatory-submission/transition)
      (let [sub-id (first path)]
        (swap! a update-in [:regulatory-submissions sub-id]
               merge (assoc (dissoc value :to-status) :status (:to-status value) :id sub-id)))

      (= effect :sales-order/propose)
      (let [order-id (first path)
            {:keys [result patch]} (propose-sales-order! s order-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :sales-order-sequence (fnil inc 0))
                       (update-in [:sales-orders order-id] merge
                                  (assoc value :id order-id :fulfillment-status :pending) patch)
                       (update :sales-order-history registry/append result))))
        result)

      ;; Merge-into-existing-entity pattern, mirrors :batch/upsert --
      ;; updates the SAME sales-order record's own :fulfillment-status,
      ;; never a new numbered record.
      (= effect :sales-order/fulfillment-transition)
      (let [order-id (first path)]
        (swap! a update-in [:sales-orders order-id]
               merge (assoc (dissoc value :to-status) :fulfillment-status (:to-status value))))

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map.
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-batches [s batches] (when (seq batches) (swap! a assoc :batches batches)) s)
  (with-equipment [s equipment] (when (seq equipment) (swap! a assoc :equipment equipment)) s)
  (with-market-approvals [s approvals] (when (seq approvals) (swap! a assoc :market-entry-approvals approvals)) s)
  (with-channel-partners [s partners] (when (seq partners) (swap! a assoc :channel-partners partners)) s)
  (with-raw-material-lots [s lots] (when (seq lots) (swap! a assoc :raw-material-lots lots)) s)
  (with-regulatory-submissions [s subs] (when (seq subs) (swap! a assoc :regulatory-submissions subs)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:batches {} :equipment {} :maintenance {} :shipments {}
                      :packaging-designs {} :market-entries {} :marketing-claims {}
                      :market-entry-approvals {} :channel-partners {} :raw-material-lots {}
                      :mes-readings {} :regulatory-submissions {} :sales-orders {}
                      :records {} :safety-concerns []
                      :ledger [] :maintenance-sequence 0 :maintenance-history []
                      :shipment-sequence 0 :shipment-history []
                      :packaging-design-sequence 0 :packaging-design-history []
                      :market-entry-sequence 0 :market-entry-history []
                      :marketing-claim-sequence 0 :marketing-claim-history []
                      :mes-reading-sequence 0 :mes-reading-history []
                      :sales-order-sequence 0 :sales-order-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained batch +
  equipment + market-entry-approval + channel-partner set:
  - `batch-001` (water-purification-drops, NaOCl 1.0%, verified +
    registered, shipping headroom) / `batch-002` (surface-disinfectant,
    NaOCl 0.1%, verified + registered, nearly fully shipped so a small
    new shipment blows through its own logged weight -- HARD hold) /
    `batch-003` (antibacterial-soap, IPMP 0.15%, UNVERIFIED/unregistered
    -- blocks any shipment coordinated against it).
  - `line-001` (verified + registered formulation/mixing tank,
    schedulable) / `line-002` (UNVERIFIED/unregistered filling line --
    blocks any maintenance scheduling against it).
  - Six target-market countries `:approved? true` (IN/BD/ID/PH/SA/AE)
    and two `:approved? false` (PK/EG, pending local regulatory
    review) -- so governor tests exercise both the approved and the
    not-yet-approved market-entry path.
  - Five `:licensed? true` channel partners (one per distribution
    channel) and one `:licensed? false` partner (pending review) for
    HARD-hold test coverage.
  - Six `raw-material-lots`: three clean verified/registered/CoA-
    received/plausible-assay lots (`RM-LOT-NAOCL-001`/`-002` feed
    `batch-001`/`batch-002` above, `RM-LOT-IPMP-001` available for
    IPMP-active batches) plus three dedicated bad lots for HARD-hold
    test coverage (`RM-LOT-NAOCL-003` NOT verified/registered,
    `RM-LOT-NAOCL-004` CoA NOT received, `RM-LOT-NAOCL-005` an
    implausible CoA assay result).
  - One `regulatory-submissions` record (`REG-IN-water-purification-
    drops`, `:status :approved` with full evidence) -- see
    `sample-regulatory-submissions` for why only ONE of the six
    `:approved?` markets is backed by design (demonstrates both
    branches of the new non-breaking market-approval-without-
    submission WARNING).
  No `mes-readings`/`sales-orders` are seeded -- both are created via
  their own ops (`:record-mes-reading`/`:propose-sales-order`), not
  pre-existing ground truth like equipment/batches/lots.
  Returns `s` (thread-friendly with `->`)."
  [s]
  (with-batches s (sample-batches))
  (with-equipment s (sample-equipment))
  (with-market-approvals s (sample-market-approvals))
  (with-channel-partners s (sample-channel-partners))
  (with-raw-material-lots s (sample-raw-material-lots))
  (with-regulatory-submissions s (sample-regulatory-submissions))
  s)

;; ----------------------------- back-compat aliases -----------------------------

(defn get-ledger [s] (ledger s))
