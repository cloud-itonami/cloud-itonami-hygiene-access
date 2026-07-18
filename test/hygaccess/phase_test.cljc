(ns hygaccess.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-maintenance`, `:propose-market-entry`, and
  `:propose-marketing-claim` must NEVER be members of any phase's
  `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [hygaccess.phase :as phase]))

(deftest schedule-maintenance-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real maintenance schedule"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-maintenance))
          (str "phase " n " must not auto-commit :schedule-maintenance")))))

(deftest flag-safety-concern-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :flag-safety-concern))
        (str "phase " n " must not auto-commit :flag-safety-concern"))))

(deftest coordinate-shipment-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :coordinate-shipment))
        (str "phase " n " must not auto-commit :coordinate-shipment"))))

(deftest propose-market-entry-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :propose-market-entry))
        (str "phase " n " must not auto-commit :propose-market-entry"))))

(deftest propose-marketing-claim-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :propose-marketing-claim))
        (str "phase " n " must not auto-commit :propose-marketing-claim"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-production-batch and :record-mes-reading carry no physical/financial/regulatory risk -- both administrative logging of already-independently-verified data, auto-eligible; they are the ONLY two auto-eligible ops in this domain (docs/adr/0003-mes-regulatory-sales-extensions.md)"
    (is (= #{:log-production-batch :record-mes-reading} (:auto (get phase/phases 3))))))

(deftest regulatory-submission-status-sales-order-fulfillment-status-never-auto-at-any-phase
  (testing "structural invariant: :record-regulatory-submission-status/:propose-sales-order/:update-fulfillment-status are each a real consequential business/legal claim -- never auto-decided, mirrors :schedule-maintenance/:propose-market-entry/:propose-marketing-claim"
    (doseq [[n {:keys [auto]}] phase/phases
            op [:record-regulatory-submission-status :propose-sales-order :update-fulfillment-status]]
      (is (not (contains? auto op))
          (str "phase " n " must not auto-commit " op)))))

(deftest phase-3-writes-matches-write-ops-set
  (is (= phase/write-ops (:writes (get phase/phases 3)))))

(deftest schedule-maintenance-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :schedule-maintenance))
  (is (not (contains? (:writes (get phase/phases 2)) :schedule-maintenance)))
  (is (not (contains? (:writes (get phase/phases 1)) :schedule-maintenance))))

(deftest propose-packaging-design-enabled-from-phase-2
  (is (contains? (:writes (get phase/phases 2)) :propose-packaging-design))
  (is (not (contains? (:writes (get phase/phases 1)) :propose-packaging-design))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-production-batch} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-maintenance} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-safety-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-shipment} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :propose-packaging-design} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :propose-market-entry} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :propose-marketing-claim} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-production-batch} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-production-batch} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
