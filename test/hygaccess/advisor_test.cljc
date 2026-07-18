(ns hygaccess.advisor-test
  "`hygaccess.advisor`'s murakumo-main resolution logic (task: wire
  `llm-advisor` to a real LLM endpoint per this workspace's fleet-wide
  `murakumo-main` alias convention), as executable tests -- ALL of them
  exercise `resolve-murakumo-endpoint`'s pure decision logic with either
  no `http-fn` injected or a fake in-process one; NONE make a live
  network call. `mock-advisor` (untouched by this task) remains the
  default everywhere in this repo's own test suite -- see
  `governor-contract-test`/`operation-test`, which exercise the full
  actor graph through it."
  (:require [clojure.test :refer [deftest is testing]]
            [hygaccess.advisor :as advisor]))

;; ----------------------------- resolve-murakumo-endpoint: pure resolution logic -----------------------------

(deftest override-endpoint-and-model-win-with-no-io-attempted
  (testing "step 1: explicit override -- never even looks at http-fn, so a nil/absent http-fn is fine here"
    (is (= {:endpoint "http://localhost:11434/v1/chat/completions" :model "local-qwen"}
           (advisor/resolve-murakumo-endpoint
            {:override-endpoint "http://localhost:11434/v1/chat/completions"
             :override-model "local-qwen"})))))

(deftest no-http-fn-falls-back-to-the-stable-gateway-with-the-literal-alias-model
  (testing "step 3 (endpoint-only fallback) when no host-injected http-fn is available at all -- never a concrete model id"
    (is (= {:endpoint advisor/murakumo-messages-url :model "murakumo-main"}
           (advisor/resolve-murakumo-endpoint {})))))

(deftest alias-resolution-success-targets-the-resolved-endpoint-and-alias-for-model
  (testing "step 2: a successful GET against murakumo-alias-url resolves to the discovered {:endpoint :alias-for}"
    (let [http-fn (fn [{:keys [url method]}]
                    (is (= advisor/murakumo-alias-url url))
                    (is (= :get method))
                    {:status 200 :body "irrelevant-in-this-fake"})
          json-read (fn [_body] {:endpoint "https://qwen-gad.gftd.ai/v1/chat/completions"
                                  :alias-for "qwen3.6-35b-a3b"})]
      (is (= {:endpoint "https://qwen-gad.gftd.ai/v1/chat/completions" :model "qwen3.6-35b-a3b"}
             (advisor/resolve-murakumo-endpoint {:http-fn http-fn :json-read json-read}))))))

(deftest alias-resolution-without-alias-for-still-falls-back-to-the-literal-alias-name
  (testing "resolved endpoint present but no :alias-for in the response -- :model stays the literal alias, never fabricated"
    (let [http-fn (fn [_] {:status 200 :body "x"})
          json-read (fn [_] {:endpoint "https://resolved.example/v1/chat/completions"})]
      (is (= {:endpoint "https://resolved.example/v1/chat/completions" :model "murakumo-main"}
             (advisor/resolve-murakumo-endpoint {:http-fn http-fn :json-read json-read}))))))

(deftest alias-resolution-http-error-status-falls-back-to-the-stable-gateway
  (let [http-fn (fn [_] {:status 503 :body "unavailable"})
        json-read (fn [_] (throw (ex-info "should not be reached" {})))]
    (is (= {:endpoint advisor/murakumo-messages-url :model "murakumo-main"}
           (advisor/resolve-murakumo-endpoint {:http-fn http-fn :json-read json-read})))))

(deftest alias-resolution-network-exception-falls-back-to-the-stable-gateway
  (testing "unreachable host / thrown exception from http-fn -- caught, never propagates, falls back cleanly"
    (let [http-fn (fn [_] (throw (ex-info "connection refused" {})))
          json-read (fn [_] {})]
      (is (= {:endpoint advisor/murakumo-messages-url :model "murakumo-main"}
             (advisor/resolve-murakumo-endpoint {:http-fn http-fn :json-read json-read}))))))

(deftest alias-resolution-missing-endpoint-in-response-falls-back-to-the-stable-gateway
  (let [http-fn (fn [_] {:status 200 :body "x"})
        json-read (fn [_] {:alias-for "some-model" #_"no :endpoint key"})]
    (is (= {:endpoint advisor/murakumo-messages-url :model "murakumo-main"}
           (advisor/resolve-murakumo-endpoint {:http-fn http-fn :json-read json-read})))))

;; ----------------------------- murakumo-chat-model: constructs a real ChatModel, no I/O at construction time -----------------------------

(deftest murakumo-chat-model-constructs-without-any-network-call
  (testing "constructing the ChatModel itself performs no I/O -- only -generate (never invoked by this test) would"
    (let [http-fn (fn [_] {:status 200 :body "x"})
          json-read (fn [_] {:endpoint "https://resolved.example/v1/messages" :alias-for "some-model"})
          json-write (fn [m] (str m))
          model (advisor/murakumo-chat-model {:http-fn http-fn :json-read json-read :json-write json-write})]
      (is (some? model)))))

(deftest llm-advisor-accepts-a-murakumo-backed-chat-model-satisfying-the-same-advisor-protocol-as-mock-advisor
  (testing "llm-advisor + murakumo-chat-model together satisfy the same Advisor protocol mock-advisor does -- opt-in swap, no shape change"
    (let [http-fn (fn [_] {:status 503 :body "unavailable"}) ;; forces the endpoint-only fallback, still no real call
          json-read (fn [_] {})
          json-write (fn [m] (str m))
          model (advisor/murakumo-chat-model {:http-fn http-fn :json-read json-read :json-write json-write})
          adv (advisor/llm-advisor model)]
      (is (satisfies? advisor/Advisor adv))
      (is (satisfies? advisor/Advisor (advisor/mock-advisor))
          "mock-advisor remains the safe, unaffected default"))))
