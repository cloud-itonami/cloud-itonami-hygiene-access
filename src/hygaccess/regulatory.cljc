(ns hygaccess.regulatory
  "Regulatory-submission-STATUS TRACKING state machine for the low-cost
  hygiene/disinfectant commercialization actor.

  CRITICAL SCOPE BOUNDARY: this namespace tracks the REAL-WORLD status
  of the three regulatory dossier DRAFTS already prepared under
  `docs/regulatory/` (`india-cdsco-dossier.md`, `gcc-gso-dossier.md`,
  `asean-cosmetic-dossier.md`, per the SKU-to-dossier mapping in
  `docs/regulatory/README.md`) per (market, product-type) pair. It does
  NOT file anything with any real government/regulatory system -- there
  is no HTTP client here, no submission API, no auto-generated
  filing -- STATUS TRACKING ONLY. A human must independently supply the
  evidence (filer name, date, agency reference) for any consequential
  transition (`:submitted`/`:approved`/`:rejected`); this namespace and
  `hygaccess.governor` NEVER auto-generate, default, or infer that
  evidence. `:record-regulatory-submission-status` is NEVER a member of
  any phase's `:auto` set (see `hygaccess.phase`) -- always
  human-approval-gated via the existing `interrupt-before` mechanism,
  mirroring the permanent-manual pattern `:schedule-maintenance`
  already establishes, regardless of confidence.

  ENGINE REWIRE (this build): the stage-transition table is no longer
  hand-rolled here -- it now DELEGATES to the shared, extracted library
  `cloud-itonami.regulatory-tracker.core` (`cloud-itonami/cloud-itonami-
  regulatory-tracker`, itself built on `kotoba.crm.pipeline`'s generic
  ordered-stage/exit-stage engine). This namespace keeps the SAME public
  surface (`transitions`, `valid-transition?`, `consequential-statuses`,
  `evidence-keys`, `evidence-complete?`) so `hygaccess.governor`'s own
  two check fns (`regulatory-transition-invalid-violations` /
  `regulatory-evidence-missing-violations`) need no changes at all --
  only the ENGINE under those names changed, not the contract.

  ONE deliberate, documented behavior deviation from the original
  hand-written table, forced by the shared library's own actual
  semantics (see that library's ns docstring for the full reasoning):
  `:rejected`/`:withdrawn` are now reachable from ANY non-terminal
  stage (`:draft`/`:counsel-review`/`:submitted`/`:agency-review`), not
  only from `:agency-review` as this repo's original bespoke table
  required. `:approved` is UNCHANGED -- still reachable only by walking
  the full `:draft -> :counsel-review -> :submitted -> :agency-review ->
  :approved` chain. DECISION: this build ACCEPTS the slightly-looser
  early-exit behavior rather than layering a stricter hygiene-access-
  side constraint back on top, for two reasons: (1) it is a strict
  RELAXATION, not a weakening of any HARD-block discipline this repo
  actually depends on -- no existing test in this repo ever asserted the
  stricter 'exit only from :agency-review' behavior (verified by
  inspection: `governor_contract_test.cljc`'s regulatory-status tests
  only exercise `:draft`->`:submitted` skip-ahead and missing-evidence,
  neither of which this relaxation touches), so nothing regresses; (2)
  early rejection/withdrawal (a submission counsel decides not to
  pursue, or a filer withdraws before ever reaching formal agency
  review) is itself a realistic real-world outcome this actor's own
  original table simply never modeled, not a rule this domain has any
  independent reason to forbid -- adopting the shared engine's own
  broader default is a genuine improvement, not merely an accepted
  cost. A caller with a genuine domain reason to be stricter than the
  shared library remains free to layer an additional check in its own
  governor (the shared library explicitly does not preclude this); this
  build's own governor does not, because there is no such reason here."
  (:require [cloud-itonami.regulatory-tracker.core :as reg]))

;; ----------------------------- state machine (delegates to cloud-itonami.regulatory-tracker.core) -----------------------------

(def statuses
  "The closed set of regulatory-submission-status values -- unchanged
  from the original hand-written table (the shared library's own
  `ordered-stages` + `exit-stages` cover the identical seven values)."
  #{:draft :counsel-review :submitted :agency-review :approved :rejected :withdrawn})

(def terminal-statuses
  "No transition leaves any of these three -- a decided/withdrawn
  submission stays decided. Derived from the shared library's own
  `reg/terminal-stage?` rather than hand-listed, so this can never drift
  from the engine's actual behavior."
  (into #{} (filter reg/terminal-stage?) statuses))

(def transitions
  "The closed state-machine transition table, DERIVED from the shared
  `cloud-itonami.regulatory-tracker.core` engine's own `reg/next-stages`
  rather than hand-written -- kept as a map for backward compatibility
  (`hygaccess.governor`'s own detail-message formatting reads this
  directly), but the ENGINE decision now lives entirely in the shared
  library. See ns docstring for the one documented behavior deviation
  from the original hand-written version of this table (`:rejected`/
  `:withdrawn` reachable from any non-terminal stage, not only
  `:agency-review`)."
  (into {} (map (fn [s] [s (reg/next-stages s)])) statuses))

(defn valid-transition?
  "Is `from` -> `to` an allowed single-step transition? Delegates
  entirely to `cloud-itonami.regulatory-tracker.core/valid-transition?`
  -- no parallel stage-validator maintained here."
  [from to]
  (reg/valid-transition? from to))

;; ----------------------------- human-evidence discipline -----------------------------

(def consequential-statuses
  "Transitions INTO these three statuses require independently-supplied
  human evidence (`:filed-by`/`:filing-date`/`:agency-reference`) -- a
  real human counsel/filer's own claim of what happened, never
  defaulted or auto-generated by this actor. Matches the shared
  library's own `reg/consequential-stages` exactly."
  reg/consequential-stages)

(def evidence-keys reg/evidence-keys)

(defn evidence-complete?
  "Ground-truth check: for a transition INTO a consequential status, are
  ALL THREE evidence fields present as non-blank strings in the
  proposal's own `:value`? Never defaulted -- an absent, nil, or
  blank/whitespace-only field is treated as missing evidence, not
  silently synthesized. Delegates to
  `cloud-itonami.regulatory-tracker.core/evidence-complete?`."
  [value]
  (reg/evidence-complete? value))

;; ----------------------------- non-breaking market-approval consistency WARNING -----------------------------

(defn market-approval-without-submission-warnings
  "Non-breaking, WARN-only (NEVER HARD-blocking, never escalating)
  consistency scan: for every country independently marked
  `:approved? true` in `hygaccess.store/market-entry-approvals`
  (`approved-countries`), does at least one regulatory-submission
  record on file (`regulatory-submissions`, any product-type) for that
  market carry `:status :approved`? See ns docstring 'NON-BREAKING
  WIRING NOTE' for why this is a warning, not a new HARD check, and for
  what a real deployment is intended to eventually enforce.

  `approved-countries` -- seq/set of ISO3166-alpha-2 codes independently
  marked `:approved? true` (the store's own ground truth, NOT a
  proposal's self-report).
  `regulatory-submissions` -- seq of all regulatory-submission records
  on file (each at least `{:market .. :status ..}`).

  Returns a vector of `{:rule :market-approved-without-regulatory-
  submission :market <code> :detail <str>}` maps, one per unbacked
  approved market, sorted by country code for deterministic output; an
  empty vector when every approved market already has at least one
  `:approved` submission record backing it."
  [approved-countries regulatory-submissions]
  (let [approved-by-market (set (map :market (filter #(= :approved (:status %)) regulatory-submissions)))]
    (vec
     (for [country (sort (set approved-countries))
           :when (not (contains? approved-by-market country))]
       {:rule :market-approved-without-regulatory-submission
        :market country
        :detail (str country " は market-entry-approvals で :approved? true だが、"
                     "対応する :approved 状態の regulatory-submission 記録が無い -- "
                     "実運用ではこの記録が承認の実証根拠となるべき (SOFT warning, 非block, 非escalate)")}))))
