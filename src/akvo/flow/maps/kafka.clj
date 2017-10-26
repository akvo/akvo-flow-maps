(ns akvo.flow.maps.kafka
  (:require
    [franzy.clients.consumer.client :as consumer]
    [franzy.clients.consumer.protocols :as cp]
    [thdr.kfk.avro-bridge.core :as avro]
    [franzy.serialization.deserializers :as deserializers]
    [integrant.core :as ig]
    [akvo.flow.maps.boundary.db :as db]
    [clojure.tools.logging :refer [info debug]])
  (:import (io.confluent.kafka.serializers KafkaAvroDeserializer)))

(defmethod ig/init-key ::consumer [_ config]
  (info "Initializing Kafka Consumer...")
  (let [consumer (consumer/make-consumer
                   {:bootstrap.servers       "kafka:29092"
                    :group.id                "the-consumer-test-101"
                    :client.id               "example-consumer_host_name_or_container_id"
                    :auto.offset.reset       :earliest
                    :enable.auto.commit      true
                    :max.poll.records        100
                    :auto.commit.interval.ms 10000}
                   (deserializers/long-deserializer)
                   (doto
                     (KafkaAvroDeserializer.)
                     (.configure {"schema.registry.url" "http://schema-registry:8081"} false))
                   {:poll-timeout-ms 1000})
        stop (atom false)]
    (cp/subscribe-to-partitions! consumer #".*datapoint.*")
    (info "Subscribing to .*datapoint.*")
    (future
      (while (not @stop)
        (let [records (cp/poll! consumer)
              batch (into [] (map (fn [r]
                                    (update r :value avro/->clj))) records)]
          (debug "Read " (count batch) " records from Kafka")
          (db/insert-batch (map :value batch))))
      (.close consumer)
      (info "Kafka consumer has been stopped"))
    {:stop     stop
     :consumer consumer}))

(defmethod ig/halt-key! ::consumer [_ {:keys [stop]}]
  (reset! stop true))