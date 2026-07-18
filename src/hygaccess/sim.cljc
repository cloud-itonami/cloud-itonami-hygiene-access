(ns hygaccess.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean commercialization
  program through intake (formulation-batch logging, auto-commit) ->
  maintenance scheduling (escalate/approve) -> safety-concern flag
  (escalate/approve) -> shipment coordination (escalate/approve) ->
  packaging-design proposal (escalate/approve) -> market-entry proposal
  (escalate/approve) -> marketing-claim proposal (escalate/approve),
  then shows every HARD-hold scenario: a mis-wired request whose own
  `:effect` is not `:propose`, an unrecognized op, maintenance
  scheduled against an UNVERIFIED/unregistered formulation/filling-line
  equipment unit, a shipment coordinated against an UNVERIFIED/
  unregistered batch, a shipment proposal that would exceed the
  batch's own logged production weight, a proposal that tries to
  ACTUATE the formulation/filling line directly (permanently blocked,
  no override), a proposal that tries to DECIDE a certification
  directly (permanently blocked, no override), a batch that declares
  sodium hypochlorite alongside an acid co-ingredient (toxic
  chlorine-gas hazard, permanently blocked, no override), a
  double-schedule of the same maintenance window, a production-batch
  patch with a fabricated product type, a production-batch patch whose
  active is not authorized for its product type, a production-batch
  patch whose concentration falls outside its own efficacy window, an
  implausible off-spec-rate reading, a packaging-design proposal with a
  non-BOP-appropriate format, a market-entry proposal targeting a
  not-yet-approved country, a market-entry proposal priced above its
  product type's own affordability ceiling, a marketing-claim proposal
  citing an unsubstantiated claim, and a market-entry proposal citing
  an unlicensed distribution-channel partner.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario -- the
  SAME 'exercise the failure mode directly, never only via a
  happy-path actuation' discipline every sibling since establishes."
  (:require [langgraph.graph :as g]
            [hygaccess.store :as store]
            [hygaccess.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :hygiene-access-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "\n== HAPPY PATHS ==\n")

    (println "== log-production-batch batch-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:off-spec-rate-pct 0.3 :last-assessed "2026-07-18"}}
                       coordinator))

    (println "== schedule-maintenance mnt-1 on line-001 (verified, registered -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                       :value {:equipment-id "line-001" :maintenance-type :nozzle-inspection
                               :scheduled-date "2026-08-01" :actuate-line? false}}
                      coordinator)]
      (println r)
      (println "-- human coordinator approves --")
      (println (approve! actor "t2")))

    (println "== flag-safety-concern concern-1 on line-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:equipment-id "line-001" :severity :moderate
                               :description "配合工程周辺の次亜塩素酸蒸気曝露リスク上昇"}}
                      coordinator)]
      (println r)
      (println "-- human coordinator approves --")
      (println (approve! actor "t3")))

    (println "== coordinate-shipment ship-1 on batch-001 (verified, registered, within weight -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :coordinate-shipment :effect :propose :subject "ship-1"
                       :value {:batch-id "batch-001" :weight-kg 50.0
                               :destination "ngo-distribution-hub-north"}}
                      coordinator)]
      (println r)
      (println "-- human coordinator approves --")
      (println (approve! actor "t4")))

    (println "== propose-packaging-design pkg-1 (small-bottle-50ml, valid format -- escalates, approve) ==")
    (let [r (exec-op actor "t5"
                      {:op :propose-packaging-design :effect :propose :subject "pkg-1"
                       :value {:product-type :water-purification-drops
                               :format :small-bottle-50ml :net-content "50ml"}}
                      coordinator)]
      (println r)
      (println "-- human coordinator approves --")
      (println (approve! actor "t5")))

    (println "== propose-market-entry mkt-1 (IN approved, price within ceiling, licensed NGO partner -- escalates, approve) ==")
    (let [r (exec-op actor "t6"
                      {:op :propose-market-entry :effect :propose :subject "mkt-1"
                       :value {:product-type :water-purification-drops :country "IN"
                               :price-minor 350000 :channel :ngo-who-program
                               :channel-partner-id "partner-ngo-1"}}
                      coordinator)]
      (println r)
      (println "-- human coordinator approves --")
      (println (approve! actor "t6")))

    (println "== propose-marketing-claim clm-1 (substantiated WHO SWS-style claim -- escalates, approve) ==")
    (let [r (exec-op actor "t7"
                      {:op :propose-marketing-claim :effect :propose :subject "clm-1"
                       :value {:product-type :water-purification-drops
                               :claim "reduces waterborne bacterial and viral pathogens when used per labeled dosing (WHO Safe Water System-style point-of-use treatment)"}}
                      coordinator)]
      (println r)
      (println "-- human coordinator approves --")
      (println (approve! actor "t7")))

    (println "\n== HARD-hold scenarios (every one settles without exception) ==\n")

    (println "== log-production-batch with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "h1"
                       {:op :log-production-batch :effect :direct-write :subject "batch-001"
                        :patch {:off-spec-rate-pct 0.3}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "h2"
                       {:op :actuate-mixer :effect :propose :subject "batch-001"}
                       coordinator))

    (println "== schedule-maintenance mnt-2 on line-002 (UNVERIFIED/unregistered filling line -> HARD hold) ==")
    (println (exec-op actor "h3"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                        :value {:equipment-id "line-002" :maintenance-type :seal-inspection
                                :scheduled-date "2026-08-01" :actuate-line? false}}
                       coordinator))

    (println "== coordinate-shipment ship-2 on batch-003 (UNVERIFIED/unregistered batch -> HARD hold) ==")
    (println (exec-op actor "h4"
                       {:op :coordinate-shipment :effect :propose :subject "ship-2"
                        :value {:batch-id "batch-003" :weight-kg 50.0
                                :destination "informal-retail-south"}}
                       coordinator))

    (println "== coordinate-shipment ship-3 on batch-002 (100kg would exceed weight 800 vs shipped 750 -> HARD hold) ==")
    (println (exec-op actor "h5"
                       {:op :coordinate-shipment :effect :propose :subject "ship-3"
                        :value {:batch-id "batch-002" :weight-kg 100.0
                                :destination "institutional-buyer-east"}}
                       coordinator))

    (println "== schedule-maintenance mnt-4 on line-001 with :actuate-line? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "h6"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-4"
                        :value {:equipment-id "line-001" :maintenance-type :force-run
                                :scheduled-date "2026-09-01" :actuate-line? true}}
                       coordinator))

    (println "== log-production-batch batch-001 with :decide-certification? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "h7"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:decide-certification? true}}
                       coordinator))

    (println "== log-production-batch batch-001 declaring an acid co-ingredient alongside sodium-hypochlorite -> HARD hold, PERMANENT (toxic chlorine-gas hazard), never reaches a human ==")
    (println (exec-op actor "h8"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:co-ingredients #{:hydrochloric-acid}}}
                       coordinator))

    (println "== schedule-maintenance mnt-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "h9"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "line-001" :maintenance-type :nozzle-inspection
                                :scheduled-date "2026-08-01" :actuate-line? false}}
                       coordinator))

    (println "== log-production-batch batch-001 with a fabricated product-type -> HARD hold ==")
    (println (exec-op actor "h10"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:product-type :unobtainium-disinfectant}}
                       coordinator))

    (println "== log-production-batch batch-001 with an active not authorized for its (effective) product-type -> HARD hold ==")
    (println (exec-op actor "h11"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:active :isopropylmethylphenol}}
                       coordinator))

    (println "== log-production-batch batch-001 with concentration far outside its efficacy window -> HARD hold ==")
    (println (exec-op actor "h12"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:concentration-pct 5.0}}
                       coordinator))

    (println "== log-production-batch batch-001 with an implausible off-spec-rate reading -> HARD hold ==")
    (println (exec-op actor "h13"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:off-spec-rate-pct 150.0}}
                       coordinator))

    (println "== propose-packaging-design pkg-2 with a non-BOP-appropriate bulk format -> HARD hold ==")
    (println (exec-op actor "h14"
                       {:op :propose-packaging-design :effect :propose :subject "pkg-2"
                        :value {:product-type :surface-disinfectant
                                :format :bulk-drum-200l :net-content "200L"}}
                       coordinator))

    (println "== propose-market-entry mkt-2 targeting PK (not yet approved) -> HARD hold ==")
    (println (exec-op actor "h15"
                       {:op :propose-market-entry :effect :propose :subject "mkt-2"
                        :value {:product-type :surface-disinfectant :country "PK"
                                :price-minor 700000 :channel :licensed-informal-retail-aggregator
                                :channel-partner-id "partner-retail-1"}}
                       coordinator))

    (println "== propose-market-entry mkt-3 priced above the antibacterial-soap affordability ceiling -> HARD hold ==")
    (println (exec-op actor "h16"
                       {:op :propose-market-entry :effect :propose :subject "mkt-3"
                        :value {:product-type :antibacterial-soap :country "IN"
                                :price-minor 500000 :channel :microfinance-social-enterprise
                                :channel-partner-id "partner-mfi-1"}}
                       coordinator))

    (println "== propose-marketing-claim clm-2 citing an unsubstantiated health claim -> HARD hold ==")
    (println (exec-op actor "h17"
                       {:op :propose-marketing-claim :effect :propose :subject "clm-2"
                        :value {:product-type :water-purification-drops
                                :claim "cures cholera and typhoid"}}
                       coordinator))

    (println "== propose-market-entry mkt-4 citing an UNLICENSED channel partner -> HARD hold ==")
    (println (exec-op actor "h18"
                       {:op :propose-market-entry :effect :propose :subject "mkt-4"
                        :value {:product-type :antibacterial-soap :country "IN"
                                :price-minor 200000 :channel :licensed-informal-retail-aggregator
                                :channel-partner-id "partner-retail-2"}}
                       coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft maintenance records ==")
    (doseq [r (store/maintenance-history db)] (println r))

    (println "\n== draft shipment records ==")
    (doseq [r (store/shipment-history db)] (println r))

    (println "\n== draft packaging-design records ==")
    (doseq [r (store/packaging-design-history db)] (println r))

    (println "\n== draft market-entry records ==")
    (doseq [r (store/market-entry-history db)] (println r))

    (println "\n== draft marketing-claim records ==")
    (doseq [r (store/marketing-claim-history db)] (println r))))
