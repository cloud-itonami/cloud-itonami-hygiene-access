(ns hygaccess.modbus-integration-test
  "Genuine external-observability proof for `hygaccess.modbus-demo`: starts
  the Modbus server bound to the SAME `dcs.ports/IFieldIO` the real closed-
  loop control simulation (`hygaccess.mes/run-homogeneity-control-loop`,
  already backed by a real CFD solve -- ADR-0004/0005) drives, then --
  ticking the loop itself -- independently polls the server via a REAL,
  separate Modbus TCP client connection at EVERY iteration and asserts the
  externally-read values match the control loop's own internal state for
  that exact tick. Not an internal function call assertion: the poll goes
  out over a real loopback TCP socket through com.digitalpetri.modbus's
  own `ModbusTcpClient`, the same real client `kotoba-lang/dcs`'s own
  `dcs.modbus` wire-protocol test uses.

  JVM only (.clj) -- mirrors `hygaccess.modbus-demo` itself."
  (:require [clojure.test :refer [deftest is testing]]
            [dcs.ports :as dcs-ports]
            [hygaccess.mes :as mes]
            [hygaccess.modbus-demo :as modbus-demo]
            [kami-app-hygaccess-plant.process :as process])
  (:import
   (com.digitalpetri.modbus.client ModbusTcpClient)
   (com.digitalpetri.modbus.tcp.client NettyTcpClientTransport)
   (com.digitalpetri.modbus.pdu ReadInputRegistersRequest)
   (java.nio ByteBuffer)
   (java.util.function Consumer)))

;; Small mesh, same fixture `mes-test.cljc` itself uses to keep a directly-
;; invoked `run-homogeneity-control-loop` fast -- this integration test is
;; not exercising CFD fidelity, only the external-observability wiring.
(def ^:private small-tank
  (assoc (process/default-tank) :tank/mesh-resolution [10 10]))

;; Distinct from dcs's own test-suite ports (15020/15021/15022/15034) and
;; from dcs.modbus/default-port (15020) -- this repo's own process, but
;; keep well clear regardless.
(def ^:private test-port 15026)

(defn- real-client ^ModbusTcpClient [port]
  (let [transport (NettyTcpClientTransport/create
                    (reify Consumer
                      (accept [_this b]
                        (set! (.hostname b) "127.0.0.1")
                        (set! (.port b) (int port)))))]
    (ModbusTcpClient/create transport)))

(defn- read-word [^ModbusTcpClient client addr]
  (let [resp (.readInputRegisters client 1 (ReadInputRegistersRequest. addr 1))
        bb (ByteBuffer/wrap (.registers resp))]
    (bit-and (int (.getShort bb)) 0xFFFF)))

(defn- close-enough? [a b tolerance]
  (<= (Math/abs (- (double a) (double b))) tolerance))

(deftest external-modbus-poll-matches-internal-control-loop-state-at-every-tick
  (testing "a real, independent Modbus TCP client polling the demo server sees the SAME values
           the control loop's own internal trace holds, at every single iteration -- not just at
           the end, and not merely by internal assertion"
    (let [io (mes/mock-plant-io {})
          handle (modbus-demo/start-server! io {:port test-port})
          poll-count (atom 0)]
      (try
        (let [client (real-client test-port)]
          (.connect client)
          (try
            (let [result
                  (mes/run-homogeneity-control-loop
                   {:tank small-tank
                    :max-iterations 4
                    :on-iteration
                    (fn [trace-entry]
                      (swap! poll-count inc)
                      (modbus-demo/poll-tags! io trace-entry)
                      ;; --- the genuine external-observability check ---
                      (let [expected-cov (get-in trace-entry [:sensor-reading :mixing-homogeneity-cov-pct])
                            expected-rpm (:command-issued trace-entry)
                            expected-ph (:ph trace-entry)
                            expected-temp (:temperature-c trace-entry)
                            expected-alarm (boolean (:alarm-active? trace-entry))

                            observed-cov (/ (read-word client 0) 100.0)
                            observed-rpm (/ (read-word client 1) 10.0)
                            observed-ph (/ (read-word client 2) 100.0)
                            observed-temp (/ (read-word client 3) 100.0)
                            observed-alarm (not (zero? (read-word client 4)))]
                        (is (close-enough? expected-cov observed-cov 0.01)
                            (str "iteration " (:iteration trace-entry) ": CoV external=" observed-cov
                                 " internal=" expected-cov))
                        (is (close-enough? expected-rpm observed-rpm 0.1)
                            (str "iteration " (:iteration trace-entry) ": RPM external=" observed-rpm
                                 " internal=" expected-rpm))
                        (is (close-enough? expected-ph observed-ph 0.01)
                            (str "iteration " (:iteration trace-entry) ": pH external=" observed-ph
                                 " internal=" expected-ph))
                        (is (close-enough? expected-temp observed-temp 0.01)
                            (str "iteration " (:iteration trace-entry) ": temp external=" observed-temp
                                 " internal=" expected-temp))
                        (is (= expected-alarm observed-alarm)
                            (str "iteration " (:iteration trace-entry) ": alarm-active external="
                                 observed-alarm " internal=" expected-alarm))))})]

              (testing "the loop actually ran, and the on-iteration callback actually fired every tick"
                (is (= (:iterations-run result) @poll-count))
                (is (>= @poll-count 2) "max-iterations 4 -> at least a few real polls happened"))

              (testing "the FINAL externally-polled state also matches the loop's own final reading"
                (is (close-enough? (:final-cov result) (/ (read-word client 0) 100.0) 0.01))))
            (finally
              (.disconnect client))))
        (finally
          (modbus-demo/stop-server! handle))))))

(deftest demo-registers-are-read-only-observability-only-never-remote-control
  (testing "no FC06/16 writer is registered for any of this demo's registers -- a real Modbus
           master can watch the control loop, it cannot steer it"
    (let [io (mes/mock-plant-io {})
          rm (modbus-demo/register-map)]
      (is (empty? (:dcs/holding rm)) "every register is an :dcs/input (FC04, read-only) register")
      (is (= 5 (count (:dcs/input rm))))
      ;; sanity: the register map's tag ids line up with hygaccess.mes's own tag ids, not
      ;; ad-hoc strings that happen to look similar
      (is (= #{mes/homogeneity-tag-id mes/mixing-rpm-tag-id mes/ph-tag-id mes/temperature-tag-id
               modbus-demo/alarm-active-tag-id}
             (set (map :dcs/tag (vals (:dcs/input rm))))))
      (dcs-ports/write-tag! io mes/ph-tag-id 6.5)
      (is (= 6.5 (dcs-ports/read-tag io mes/ph-tag-id))))))
