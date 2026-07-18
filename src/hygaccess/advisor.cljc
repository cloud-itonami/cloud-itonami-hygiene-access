(ns hygaccess.advisor
  "HygieneAccessAdvisor -- the *contained intelligence node* for the
  low-cost hygiene/disinfectant active-ingredient commercialization
  actor (sodium hypochlorite + isopropylmethylphenol, formulated into
  water-purification drops / surface disinfectant / antibacterial soap
  for water-scarce / poor-sanitation-infrastructure markets).

  It normalizes production-batch patches (product-type/active/
  concentration/weight/off-spec-rate data), drafts a formulation/
  filling-line maintenance scheduling proposal against a piece of
  equipment, drafts a safety-concern flag, drafts an outbound shipment
  coordination proposal against a production batch, drafts a
  packaging-design proposal, drafts a market-entry proposal (target-
  country + price-point + distribution-channel + channel-partner
  bundle), and drafts a marketing-claim proposal. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a real formulation/filling-line actuation, freight dispatch,
  chemical-safety/medical/regulatory certification decision, or real
  sale. Every output is censored downstream by `hygaccess.governor`
  before anything touches the SSoT -- see README `What this actor does
  NOT do`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `hygaccess.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed seven
                                 ; propose-shaped effects, NEVER a
                                 ; direct formulation/filling-line-
                                 ; control effect and NEVER a
                                 ; certification-decision effect
     :value      map            ; the patch/value the effect would apply
     :stake      kw|nil         ; see `hygaccess.governor/stake-for`
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) --
  `hygaccess.governor` HARD-holds any request that doesn't, so a
  mis-wired caller can never reach a commit path even if this advisor
  were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [hygaccess.registry :as registry]
            [hygaccess.governor :as governor]
            [hygaccess.store :as store]
            [langchain.model :as model]))

(defn- log-production-batch
  "Production-batch intake upsert -- the advisor only normalizes/
  validates the patch; it does not invent the batch's product-type,
  active, concentration, weight, or verification status. High
  confidence, low stakes -- administrative logging, not an operational
  decision."
  [_db {:keys [patch]}]
  {:summary    (str "配合バッチ記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :batch/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- schedule-maintenance
  "Draft a formulation/filling-line maintenance-window scheduling
  proposal against a piece of equipment. The advisor reports what it
  can see (equipment verified?/registered?) in its rationale, but
  `hygaccess.governor` NEVER trusts this report -- it independently
  re-derives verified?/registered? from the equipment's own stored
  fields before any commit is possible."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (store/equipment-unit db equipment-id)
        ready? (and eq (registry/equipment-ready? eq))]
    {:summary    (str subject " 向け保守作業予定提案 (" (:maintenance-type value) ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (if eq
                   (str "equipment-verified?=" (registry/equipment-verified? eq)
                        " equipment-registered?=" (registry/equipment-registered? eq)
                        " actuate-line?=" (boolean (:actuate-line? value)))
                   (str equipment-id " が見つかりません"))
     :cites      (if eq [equipment-id] [])
     :effect     :maintenance/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? (not (:actuate-line? value))) 0.9 0.3)}))

(defn- flag-safety-concern
  "Draft a chemical-hazard/toxic-co-formulation/contamination concern.
  ALWAYS `:stake :coordination/safety-concern` -- a safety concern is
  NEVER a proposal the advisor may quietly downgrade to low-stakes, and
  it is never gated on the referenced equipment/batch being verified (a
  concern can be raised about ANY equipment or batch, verified or
  not)."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (and equipment-id (store/equipment-unit db equipment-id))]
    {:summary    (str subject " 向け安全懸念報告 (" (:severity value) ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (str "severity=" (:severity value) " description=" (:description value))
     :cites      (if eq [equipment-id] [])
     :effect     :safety-concern/flag
     :value      value
     :stake      (governor/stake-for {:op :flag-safety-concern})
     :confidence 0.9}))

(defn- coordinate-shipment
  "Draft an outbound hygiene-product shipment coordination proposal
  against a production batch. The advisor passes through the caller's
  own claimed weight -- it does NOT invent one, and `hygaccess.
  governor` NEVER trusts it: it independently recomputes whether the
  batch's own cumulative-shipped weight plus this claim would exceed
  the batch's own recorded weight before any commit is possible."
  [db {:keys [subject value]}]
  (let [batch-id (:batch-id value)
        b (store/batch db batch-id)
        ready? (and b (registry/batch-ready? b))
        over-weight? (and b (registry/shipment-weight-exceeded?
                             b (:weight-kg value)))]
    {:summary    (str subject " 向け出荷調整提案 ("
                      (:weight-kg value) " kg)"
                      (when b (str " batch=" batch-id)))
     :rationale  (if b
                   (str "batch-verified?=" (registry/batch-verified? b)
                        " batch-registered?=" (registry/batch-registered? b)
                        " over-weight?=" over-weight?)
                   (str batch-id " が見つかりません"))
     :cites      (if b [batch-id] [])
     :effect     :shipment/propose
     :value      value
     :stake      nil
     :confidence (if (and ready? (not over-weight?)) 0.9 0.3)}))

(defn- propose-packaging-design
  "Draft a BOP-appropriate packaging-format + net-content proposal for
  a product type. The advisor reports the format it proposes, but
  `hygaccess.governor` NEVER trusts that it is BOP-appropriate -- it
  independently re-verifies membership in the closed
  `valid-packaging-formats` set."
  [_db {:keys [subject value]}]
  (let [format (:format value)
        valid? (registry/packaging-format-valid? format)]
    {:summary    (str subject " 向け梱包設計提案 (" format ", "
                      (:net-content value) ")")
     :rationale  (str "packaging-format-valid?=" valid?)
     :cites      [:format :net-content]
     :effect     :packaging-design/propose
     :value      value
     :stake      nil
     :confidence (if valid? 0.9 0.3)}))

(defn- propose-market-entry
  "Draft a market-entry proposal bundling target-country + price-point
  + distribution-channel + channel-partner for a product type. The
  advisor reports what it can see (market approved?, price within
  ceiling?, channel-partner licensed?) in its rationale, but
  `hygaccess.governor` NEVER trusts this report -- it independently
  re-derives all three from the store's own ground-truth records
  before any commit is possible. ALWAYS a high-stakes proposal (a
  go-to-market decision) -- see `hygaccess.governor/stake-for`."
  [db {:keys [subject value]}]
  (let [{:keys [product-type country price-minor channel channel-partner-id]} value
        approval (and country (store/market-approval db country))
        approved? (and approval (registry/market-approved? approval))
        within-ceiling? (not (registry/price-above-ceiling? product-type price-minor))
        partner (and channel-partner-id (store/channel-partner db channel-partner-id))
        partner-ready? (registry/channel-partner-ready? partner channel)]
    {:summary    (str subject " 向け市場参入提案 (" product-type " -> " country
                      ", price=" price-minor ", channel=" channel ")")
     :rationale  (str "market-approved?=" approved?
                      " price-within-ceiling?=" within-ceiling?
                      " channel-partner-ready?=" partner-ready?)
     :cites      (cond-> []
                   country (conj country)
                   channel-partner-id (conj channel-partner-id))
     :effect     :market-entry/propose
     :value      value
     :stake      (governor/stake-for {:op :propose-market-entry :value value})
     :confidence (if (and approved? within-ceiling? partner-ready?) 0.9 0.3)}))

(defn- propose-marketing-claim
  "Draft a marketing/health-claim proposal for a product type. The
  advisor reports whether it believes the claim is substantiated, but
  `hygaccess.governor` NEVER trusts this report -- it independently
  re-verifies membership in the closed per-product-type substantiated-
  claims set. ALWAYS a high-stakes proposal (a public health claim
  against a vulnerable population) -- see `hygaccess.governor/
  stake-for`."
  [_db {:keys [subject value]}]
  (let [{:keys [product-type claim]} value
        substantiated? (registry/claim-substantiated? product-type claim)]
    {:summary    (str subject " 向けマーケティングクレーム提案 (" product-type ")")
     :rationale  (str "claim-substantiated?=" substantiated? " claim=\"" claim "\"")
     :cites      [:claim]
     :effect     :marketing-claim/propose
     :value      value
     :stake      (governor/stake-for {:op :propose-marketing-claim})
     :confidence (if substantiated? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-production-batch      (log-production-batch db request)
    :schedule-maintenance       (schedule-maintenance db request)
    :flag-safety-concern        (flag-safety-concern db request)
    :coordinate-shipment        (coordinate-shipment db request)
    :propose-packaging-design   (propose-packaging-design db request)
    :propose-market-entry       (propose-market-entry db request)
    :propose-marketing-claim    (propose-marketing-claim db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは低価格な衛生・消毒有効成分(次亜塩素酸ナトリウム / IPMP)を"
       "水不足・衛生インフラ不足市場向けに商業化する助言者です。与えられた"
       "事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:batch/upsert|:maintenance/schedule|"
       ":safety-concern/flag|:shipment/propose|"
       ":packaging-design/propose|:market-entry/propose|"
       ":marketing-claim/propose) "
       ":value(操作固有のパッチ/値) "
       ":stake(:coordination/safety-concern|:coordination/new-market-entry|"
       ":coordination/marketing-claim-change|"
       ":coordination/price-change-above-threshold か nil) "
       ":confidence(0..1)。\n"
       "重要: 未検証または未登録の設備・バッチに対する作業を提案しては"
       "いけません。配合/充填ラインの直接操作(actuate)を絶対に提案しては"
       "いけません(この actor は提案のみを行い、実行は一切行いません)。"
       "出荷量を偽って報告してはいけません。化学品安全/医療/規制認証の"
       "可否を判断・付与してはいけません(この actor は認証・規制当局では"
       "ありません)。次亜塩素酸ナトリウムを酸またはアンモニア系成分と"
       "同時配合する提案を絶対にしてはいけません(塩素ガス/クロラミンガス"
       "発生の危険)。濃度が効力窓の範囲外のバッチを適合と偽って報告しては"
       "いけません。未承認国への市場参入、上限超過の価格設定、未実証の"
       "マーケティングクレーム、未ライセンスの流通チャネルパートナーを"
       "提案してはいけません。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-production-batch       {:batch (store/batch st subject)}
    :schedule-maintenance       {:equipment (store/equipment-unit st (:equipment-id value))}
    :flag-safety-concern        {:equipment (and (:equipment-id value)
                                                  (store/equipment-unit st (:equipment-id value)))}
    :coordinate-shipment        {:batch (store/batch st (:batch-id value))}
    :propose-packaging-design   {}
    :propose-market-entry       {:market-approval (and (:country value) (store/market-approval st (:country value)))
                                  :channel-partner (and (:channel-partner-id value)
                                                        (store/channel-partner st (:channel-partner-id value)))}
    :propose-marketing-claim    {:substantiated-claims (registry/claims-for (:product-type value))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `hygaccess.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule maintenance,
  auto-flag a concern, auto-coordinate a shipment, auto-propose a
  market entry, or auto-propose a marketing claim."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :hygaccess-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
