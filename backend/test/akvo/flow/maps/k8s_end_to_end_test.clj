(ns akvo.flow.maps.k8s-end-to-end-test
  {:kubernetes-test true}
  (:require
    [thdr.kfk.avro-bridge.core :as avro]
    [cheshire.core :as json]
    [clojure.tools.logging :refer [info debug]]
    [clojure.test :refer :all]
    [akvo.flow.maps.end-to-end-test :as end-to-end]
    [akvo.flow.maps.map-creation.http-proxy :as http])
  (:import
    (java.nio.charset Charset)
    (org.apache.avro.generic GenericDatumWriter)
    (org.apache.avro.io EncoderFactory)
    (java.io ByteArrayOutputStream FileInputStream)
    (javax.net.ssl SSLContext TrustManagerFactory KeyManagerFactory)
    (java.security KeyStore SecureRandom)))

(defn env [k]
  (or (System/getenv k)
      (throw (RuntimeException. (str "No system property " k)))))

(defn create-ssl-context []
  (let [jks-password (.toCharArray (env "KAFKA_KEYSTORE_PASSWORD"))
        key-password (.toCharArray (env "KAFKA_KEY_PASSWORD"))

        ks (with-open [is (FileInputStream. (env "KAFKA_KEYSTORE_PATH"))]
             (doto
               (KeyStore/getInstance "JKS")
               (.load is jks-password)))

        kmf (doto
              (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init ks key-password))

        tmf (doto
              (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
              (.init ks))]
    (doto
      (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers kmf) (.getTrustManagers tmf) (SecureRandom.)))))

(defn ->avro-json [o]
  (let [bo (ByteArrayOutputStream.)
        enc (.jsonEncoder (EncoderFactory/get) end-to-end/DataPointSchema bo)]
    (.write (GenericDatumWriter. end-to-end/DataPointSchema)
            (avro/->java end-to-end/DataPointSchema o)
            enc)
    (.flush enc)
    (.close bo)
    (String. (.toByteArray bo) (Charset/forName "UTF-8"))))

(defonce secure-http-client (http/create-client {:connection-timeout 10000
                                                 :request-timeout    10000
                                                 :max-connections    10
                                                 :ssl-context        (create-ssl-context)}))

(defn push-data-point [data-point topic]
  (let [value data-point]
    (end-to-end/json-request
      secure-http-client
      {:method  :post
       :headers {"content-type" "application/vnd.kafka.avro.v2+json"
                 "Accept"       "application/vnd.kafka.v2+json"}
       :url     (str "https://kafka-rest-proxy.akvotest.org/topics/" (end-to-end/full-topic topic))
       :body    (json/generate-string {:value_schema end-to-end/DataPointSchema-as-json
                                       :records      [{:value (json/parse-string (->avro-json value))}]})})))

(deftest shows-data-from-one-dp
  (let [config {:create-map-url "https://flowmaps.akvotest.org/create-map"
                :tiles-url      "https://flowmaps.akvotest.org"
                :keycloak       {:url      "https://kc.akvotest.org/auth"
                                 :user     "akvo-flow-maps-ci-client"
                                 :password (env "KEYCLOAK_TEST_PASSWORD")}}
        datapoint (end-to-end/random-data-point)
        datapoint-topic-a (assoc datapoint :identifier (end-to-end/random-id))
        _ (info (push-data-point datapoint-topic-a "k8s-test"))]
    (end-to-end/map-has config datapoint-topic-a "k8s-test")))