(ns akvo.flow.maps.boundary.consumer-wip
  (:require
    [franzy.clients.consumer.client :as consumer]
    [franzy.clients.consumer.protocols :as cp]
    [thdr.kfk.avro-bridge.core :as avro]
    [franzy.serialization.deserializers :as deserializers])
  (:import (io.confluent.kafka.serializers KafkaAvroDeserializer)
           (org.apache.kafka.clients.consumer OffsetCommitCallback)))

(def c (consumer/make-consumer
         {:bootstrap.servers       "kafka:29092"
          :group.id                "the-consumer-test"
          :client.id               "example-consumer_host_name_or_container_id"
          :auto.offset.reset       :earliest
          :enable.auto.commit      true
          :max.poll.records        10
          :auto.commit.interval.ms 10000}
         (deserializers/keyword-deserializer)
         (doto
           (KafkaAvroDeserializer.)
           (.configure {"schema.registry.url" "http://schema-registry:8081"} false))
         {:poll-timeout-ms        1000
          :offset-commit-callback (reify OffsetCommitCallback
                                    (onComplete [this map e]
                                      (println "offset commited!" map e)))}))


(cp/subscribe-to-partitions! c #"testing-.*")

(cp/clear-subscriptions! c)

(dotimes [_ 100]
  (let [records (cp/poll! c)
        batch (into [] (map (fn [r]
                               (update r :value avro/->clj))) records)]
    (println batch)
    (println (count batch))))

(.close c)