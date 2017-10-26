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

  (def DataPointSchema
    (avro/parse-schema
      (json/generate-string
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
                     {:name "last_update_date_time", :type "long"}]})))

  (with-open [rdr (clojure.java.io/reader "akvoflowsandbox.SurveyedLocale.edn")]
    (dorun (for [line (line-seq rdr)]
             (let [value (-> (read-string line)
                             (select-keys ["identifier" "surveyGroupId" "longitude" "latitude" "createdDateTime" "lastUpdateDateTime"])
                             (clojure.set/rename-keys {"surveyGroupId" "surveyId"})
                             (update "createdDateTime" #(.getTime %))
                             (update "lastUpdateDateTime" #(.getTime %))
                             )]
               (send-async!
                 p
                 {:topic (str "org.akvo.akvoflowsandbox.datapoint")
                  :key   (get value "surveyId")
                  :value (avro/->java DataPointSchema
                                      value)})))))

  (.close p))