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

(def p (producer/make-producer {:bootstrap.servers "kafka:29092"
                                :acks              "all"
                                :retries           1
                                :client.id         "example-producer"
                                }
                               (serializers/keyword-serializer)
                               (doto
                                 (KafkaAvroSerializer.)
                                 (.configure {"schema.registry.url" "http://schema-registry:8081"} false))))

(dotimes [i 100]
  (dotimes [j 10]
    (send-async!
      p
      {:topic (str "testing-" j)
       :key   (str i)
       :value (avro/->java (avro/parse-schema
                             (json/generate-string
                               {:namespace "example.avro",
                                :type      "record",
                                :name      "User",
                                :fields    [{:name "client", :type "string"}
                                            {:name "now" :type ["long" "null"] :default 0}]}))
                           {:client (str "client-" j)
                            :now    (System/currentTimeMillis)})})))

(.close p)