(ns hygaccess.phase
  "Phase 0->3 staged rollout for the low-cost hygiene/disinfectant
  active-ingredient commercialization actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-intake      -- production-batch logging allowed,
                                      every write needs human approval.
    Phase 2  assisted-coordinate  -- adds safety-concern flags,
                                      shipment-coordination proposals,
                                      and packaging-design proposals,
                                      still approval.
    Phase 3  supervised-auto      -- adds maintenance scheduling
                                      (still always approval -- see
                                      below), market-entry proposals,
                                      and marketing-claim proposals
                                      (both still always approval);
                                      governor-clean, high-confidence
                                      `:log-production-batch` (no
                                      physical/financial/regulatory
                                      risk -- administrative logging)
                                      may auto-commit.

  `:schedule-maintenance` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Scheduling real maintenance against
  a piece of formulation/filling-line equipment is the one act in this
  domain with physical consequence; it is always a human plant
  coordinator's call.

  `:propose-market-entry` and `:propose-marketing-claim` are likewise
  ABSENT from every phase's `:auto` set. A market-entry proposal
  bundles target-country + price-point + distribution-channel +
  channel-partner into a single go-to-market decision, and a
  marketing-claim proposal is a public health claim against a
  vulnerable population -- both always require human sign-off,
  regardless of how governor-clean the proposal is. `hygaccess.
  governor`'s `high-stakes` set independently marks both `:coordination/
  new-market-entry` and `:coordination/marketing-claim-change` as
  always-escalate, and `no-toxic-co-formulation-blocked-violations`/
  `certification-decision-blocked-violations` HARD-block unconditionally
  regardless of phase -- multiple independent layers agree on where
  this actor's authority ends.

  Like every sibling actor's phase-3 `:auto` set, this domain originally
  had only ONE member (`:log-production-batch`) -- no separate no-risk
  lifecycle distinct from ordinary record logging.
  `docs/adr/0003-mes-regulatory-sales-extensions.md` adds a SECOND
  `:auto`-eligible op, `:record-mes-reading`: an MES/CFD telemetry
  reading is, like `:log-production-batch`, ADMINISTRATIVE LOGGING of
  an already-independently-verified instrument/simulation value, not a
  go-to-market/financial/regulatory decision -- and the new HARD checks
  (`hygaccess.governor` 25-30) already gate it tightly (batch must be
  verified/registered, every sensor value physically plausible, and any
  mismatch against a prior MES reading's own ground truth HARD-blocks)
  before it can ever reach this auto-commit path. The other three new
  ops added by that same ADR --
  `:record-regulatory-submission-status` / `:propose-sales-order` /
  `:update-fulfillment-status` -- are, like `:schedule-maintenance` /
  `:propose-market-entry` / `:propose-marketing-claim`, deliberately
  ABSENT from every phase's `:auto` set: a regulatory-submission
  transition, a sales quote/order, and a fulfillment-status update are
  each a real consequential business/legal claim, never auto-decided
  regardless of confidence or how governor-clean the proposal is.")

(def write-ops
  #{:log-production-batch :schedule-maintenance :flag-safety-concern
    :coordinate-shipment :propose-packaging-design
    :propose-market-entry :propose-marketing-claim
    :record-mes-reading :record-regulatory-submission-status
    :propose-sales-order :update-fulfillment-status})

;; NOTE the invariant: `:schedule-maintenance`, `:propose-market-entry`,
;; `:propose-marketing-claim`, `:record-regulatory-submission-status`,
;; `:propose-sales-order`, and `:update-fulfillment-status` are all
;; members of `write-ops` (governor-gated like any write) but NEVER
;; members of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"
      :writes #{}
      :auto #{}}
   1 {:label "assisted-intake"
      :writes #{:log-production-batch}
      :auto #{}}
   2 {:label "assisted-coordinate"
      :writes #{:log-production-batch :flag-safety-concern
                :coordinate-shipment :propose-packaging-design}
      :auto #{}}
   3 {:label "supervised-auto"
      :writes write-ops
      :auto #{:log-production-batch :record-mes-reading}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:schedule-maintenance`/`:propose-market-entry`/`:propose-marketing-
    claim` are never auto-eligible at any phase, so they always
    escalate once the governor clears them (or hold if it doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Hygiene Access Operations Governor verdict to a base
  disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
