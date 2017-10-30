(ns akvo.flow.maps.boundary.producer-wip
  (:require
    [franzy.clients.producer.client :as producer]
    [franzy.clients.producer.defaults :as pd]
    [franzy.clients.producer.protocols :refer [send-sync! send-async!]]
    [franzy.clients.producer.types :refer [->ProducerRecord]]
    [thdr.kfk.avro-bridge.core :as avro]
    [cheshire.core :as json]
    [franzy.serialization.serializers :as serializers])
  (:import (io.confluent.kafka.serializers KafkaAvroSerializer)))

(comment
  (def p (producer/make-producer {:bootstrap.servers "kafka:29092"
                                  :acks              "all"
                                  :retries           1
                                  :client.id         "example-producer"
                                  }
                                 (serializers/long-serializer)
                                 (doto
                                   (KafkaAvroSerializer.)
                                   (.configure {"schema.registry.url" "http://schema-registry:8081"} false))))

  (def DataPointSchema-as-json (json/generate-string
                                 {:namespace "org.akvo.flow",
                                  :type      "record",
                                  :name      "DataPoint",
                                  :fields    [
                                              {:name "identifier", :type "string"}
                                              {:name "survey_id", :type ["null" "long"]}
                                              {:name "longitude", :type ["null" "double"]}
                                              {:name "latitude", :type ["null" "double"]}
                                              ;; TODO: avro-bridge is playing around with the cases
                                              {:name "created_date_time", :type "long"}
                                              {:name "last_update_date_time", :type "long"}]}))
  (def DataPointSchema (avro/parse-schema DataPointSchema-as-json))

  (defn parse-line [line]
    (-> (read-string line)
        (select-keys ["identifier" "surveyGroupId" "longitude" "latitude" "createdDateTime" "lastUpdateDateTime"])
        (clojure.set/rename-keys {"surveyGroupId" "surveyId"})
        (update "createdDateTime" #(.getTime %))
        (update "lastUpdateDateTime" #(.getTime %))))

  ;;; Push through Kafka
  (with-open [rdr (clojure.java.io/reader "akvoflowsandbox.SurveyedLocale.edn")]
    (dorun (for [line (line-seq rdr)]
             (let [value (parse-line line)]
               (send-async!
                 p
                 {:topic (str "org.akvo.akvoflowsandbox.datapoint")
                  :key   (get value "surveyId")
                  :value (avro/->java DataPointSchema
                                      value)})))))

  (.close p)

  ;;; Push through Rest proxy
  (defn ->avro-json [o]
    (let [bo (ByteArrayOutputStream.)
          enc (.jsonEncoder (EncoderFactory/get) DataPointSchema bo)]
      (.write (GenericDatumWriter. DataPointSchema)
              (avro/->java DataPointSchema o)
              enc)
      (.flush enc)
      (.close bo)
      (String. (.toByteArray bo) (Charset/forName "UTF-8"))))

  (with-open [rdr (clojure.java.io/reader "akvoflowsandbox.SurveyedLocale.edn")
              http-client (akvo.flow.maps.boundary.http-proxy/create-client {:connection-timeout 10000
                                                                             :request-timeout    20000
                                                                             :max-connections    2})]
    (dorun (for [line (line-seq rdr)]
             (let [value (parse-line line)]
               (let [r (akvo.flow.maps.boundary.http-proxy/proxy-request
                         http-client
                         {:method  :post
                          :headers {"Content-Type" "application/vnd.kafka.avro.v2+json"
                                    "Accept"       "application/vnd.kafka.v2+json"}
                          :url     "http://35.195.81.104:80/topics/org.akvo.akvoflowsandbox3.datapoint"
                          :body    (json/generate-string {:value_schema DataPointSchema-as-json
                                                          :records      [{:value (json/parse-string (->avro-json value))}]})})]
                 (if (or (:error r) (not= (:code (:status r)) 200))
                   (throw (ex-info "" (assoc r :value value)))
                   (println (:status r) (:body r))))))))

  )