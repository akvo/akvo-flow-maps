(ns akvo.flow.maps.consumer.kafka.core
  (:require
    [franzy.clients.consumer.client :as consumer]
    [franzy.clients.consumer.protocols :as cp]
    [thdr.kfk.avro-bridge.core :as avro]
    [franzy.serialization.deserializers :as deserializers]
    [integrant.core :as ig]
    [akvo.flow.maps.consumer.kafka.datapoint-processing :as dp]
    [clojure.tools.logging :refer [info debug error]])
  (:import (io.confluent.kafka.serializers KafkaAvroDeserializer)))

(def client-id
  (if (System/getenv "POD_NAME")
    (str (System/getenv "POD_NAMESPACE") "_" (System/getenv "POD_NAME"))
    (str "local-consumer-" (System/currentTimeMillis))))

(defn create-consumer [schema-registry consumer-properties]
  (consumer/make-consumer
    (merge {:group.id                "akvo-flow-maps-consumer"
            :client.id               client-id
            :auto.offset.reset       :earliest
            :enable.auto.commit      false
            :auto.commit.interval.ms 10000}
           consumer-properties)
    (deserializers/long-deserializer)
    (doto
      (KafkaAvroDeserializer.)
      (.configure {"schema.registry.url" schema-registry} false))
    {:poll-timeout-ms 1000}))

(defmethod ig/init-key ::consumer [_ {:keys [db schema-registry consumer-properties metrics-collector die-on-uncaught-exception]}]
  (info "Initializing Kafka Consumer...")
  (let [consumer (create-consumer schema-registry consumer-properties)
        stop-flag (atom false)]
    (cp/subscribe-to-partitions! consumer #".*datapoint.*")
    (info "Subscribing to .*datapoint.*")
    (future
      (try
        (while (not @stop-flag)
          (let [records (cp/poll! consumer)
                batch (into [] (map (fn [r]
                                      (update r :value avro/->clj))) records)]
            (debug "Read " (count batch) " records from Kafka")
            (dp/process-messages db metrics-collector batch)
            (cp/commit-offsets-sync! consumer)))
        (info "Kafka consumer has been stopped")
        (catch Throwable e
          (error e "Kafka consumer died unexpectedly. No messages will be consumed")
          (when die-on-uncaught-exception
            (error "Exiting service as consumer died.")
            (System/exit 1)))
        (finally
          (.close consumer))))
    {:stop-flag stop-flag
     :consumer  consumer}))

(defmethod ig/halt-key! ::consumer [_ {:keys [stop-flag]}]
  (reset! stop-flag true))