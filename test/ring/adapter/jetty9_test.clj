(ns ring.adapter.jetty9-test
  (:require [clojure.test :refer :all]
            [ring.adapter.jetty9 :as jetty9]
            [clj-http.client :as client]
            [less.awful.ssl :as less-ssl]
            #_[gniazdo.core :as ws])
  (:import
    [org.eclipse.jetty.http2.server AbstractHTTP2ServerConnectionFactory]
    [org.eclipse.jetty.http2.frames Frame SettingsFrame]
    [org.eclipse.jetty.http2.parser WindowRateControl$Factory]
    [org.eclipse.jetty.http2 BufferingFlowControlStrategy FlowControlStrategy$Factory]))

(defn dummy-app [req]
  {:status 200})

(def websocket-handler
  {:on-connect (fn [ws])
   :on-close   (fn [ws status reason])
   :on-error   (fn [ws e])
   :on-text    (fn [ws msg]
                 (jetty9/send! ws msg))
   :on-byte    (fn [ws bytes offset length])})
(defmacro with-jetty
  [[sym [handler opts]] & body]
  `(let [~sym (->> (assoc ~opts :lifecycle-start (partial println "JETTY START")
                                :lifecycle-end   (partial println "JETTY END"))
                   (jetty9/run-jetty ~handler))]
     (try ~@body
          (finally (jetty9/stop-server ~sym)))))

(deftest jetty9-test
  (with-jetty [server [dummy-app {:port       50524
                                  :join?      false
                                  :websockets {"/path" websocket-handler}}]]
    (is server)
    (let [resp (client/get "http://localhost:50524/")]
      (is (= 200 (:status resp))))))

(defn ssl-context []
  (less-ssl/ssl-context "dev-resources/test/key.pem"
                        "dev-resources/test/cert.pem"
                        "dev-resources/test/cert.pem"))

(deftest keystore-and-truststore-test
  (with-jetty [server [dummy-app {:ssl-port        50524
                                  :http?           false
                                  :ssl             true
                                  :join?           false
                                  :websockets      {"/path" websocket-handler}
                                  :keystore        "dev-resources/test/my-keystore.jks"
                                  :key-password    "password"
                                  :keystore-type   "PKCS12"
                                  :truststore      "dev-resources/test/my-truststore.jks"
                                  :trust-password  "password"
                                  :truststore-type "PKCS12"}]]
    (is server)
    (let [resp (client/get "https://localhost:50524/" {:insecure? true})]
      (is (= 200 (:status resp))))))

(deftest ssl-context-test
  (with-jetty [server [dummy-app {:ssl-port        50524
                                  :http?           false
                                  :ssl?            true
                                  :join?           false
                                  :websockets      {"/path" websocket-handler}
                                  :ssl-context     (ssl-context)}]]
    (is server)
    (let [resp (client/get "https://localhost:50524/" {:insecure? true})]
      (is (= 200 (:status resp))))
    (let [resp (client/get "https://localhost:50524/"
                           {:keystore "dev-resources/test/my-keystore.jks"
                            :keystore-pass "password"
                            :trust-store "dev-resources/test/my-truststore.jks"
                            :trust-store-pass "password"})]
      (is (= 200 (:status resp))))
    (is (thrown-with-msg?
         Exception
         #"unable to find valid certification path to requested target"
         (client/get "https://localhost:50524/")))))

(defn- get-h2-factory-options
  [factory]
  (->> factory
       ((juxt
         #(.isConnectProtocolEnabled %)
         #(.getFlowControlStrategyFactory %)
         #(.getInitialSessionRecvWindow %)
         #(.getInitialStreamRecvWindow %)
         #(.getMaxConcurrentStreams %)
         #(.getMaxDynamicTableSize %)
         #(.getMaxFrameLength %)
         #(.getMaxHeaderBlockFragment %)
         #(.getMaxSettingsKeys %)
         #(.getRateControlFactory %)
         #(.getStreamIdleTimeout %)
         #(.isUseInputDirectByteBuffers %)
         #(.isUseOutputDirectByteBuffers %)))
       (zipmap  [:connect-protocol-enabled
                 :flow-control-strategy-factory
                 :initial-session-recv-window
                 :initial-stream-recv-window
                 :max-concurrent-streams
                 :max-dynamic-table-size
                 :max-frame-length
                 :max-header-block-fragment
                 :max-setting-keys
                 :rate-control-factory
                 :stream-idle-timeout
                 :use-input-direct-byte-buffers
                 :use-output-direct-byte-buffers])))

(deftest http2-options-test
  (let [flow-control-strategy-factory
        (proxy [FlowControlStrategy$Factory] []
          (newFlowControlStrategy [] (BufferingFlowControlStrategy. (float 0.7))))

        h2-options
        {:connect-protocol-enabled false
         :flow-control-strategy-factory flow-control-strategy-factory
         :initial-session-recv-window (* 2048 2048)
         :initial-stream-recv-window (* 512 1024)
         :max-concurrent-streams 256
         :max-dynamic-table-size 2048
         :max-frame-length Frame/MAX_MAX_LENGTH
         :max-header-block-fragment 1
         :max-setting-keys SettingsFrame/MAX_MAX_LENGTH
         :rate-control-factory (WindowRateControl$Factory. 100)
         :stream-idle-timeout 1000
         :use-input-direct-byte-buffers false
         :use-output-direct-byte-buffers false}]
    (with-jetty [server [dummy-app {:ssl-port        50524
                                    :port            50523
                                    :h2?              true
                                    :h2c?             true
                                    :h2-options      h2-options
                                    :ssl?            true
                                    :join?           false
                                    :ssl-context     (ssl-context)}]]
      (let [factories-options
            (->> (.getConnectors server)
                 (mapcat #(.getConnectionFactories %))
                 (filter #(isa? (type %) AbstractHTTP2ServerConnectionFactory))
                 (map get-h2-factory-options))]
        (doseq [fo factories-options]
          (is (= fo h2-options)))))))

#_(deftest websocket-test
    (with-jetty [server [dummy-app {:port       50524
                                    :join?      false
                                    :websockets {"/path" websocket-handler}}]]
      (is server)
      (let [resp-promise (promise)]
        (with-open [socket (ws/connect "ws://localhost:50524/path/"
                                       :on-receive #(deliver resp-promise %))]
          (ws/send-msg socket "hello")
          (is (= "hello" (deref resp-promise 20000 false)))))))
