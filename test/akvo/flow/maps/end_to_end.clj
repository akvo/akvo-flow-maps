(ns akvo.flow.maps.end-to-end
  {:integration true}
  (:require
    [franzy.clients.producer.client :as producer]
    [franzy.clients.producer.protocols :refer [send-sync! send-async!]]
    [thdr.kfk.avro-bridge.core :as avro]
    [cheshire.core :as json]
    [franzy.serialization.serializers :as serializers]
    [akvo.flow.maps.boundary.http-proxy :as http]
    [clojure.tools.logging :refer [info debug]]
    [clojure.test :refer :all]
    [clojure.java.jdbc :as jdbc]
    [clojure.test :as test])
  (:import (io.confluent.kafka.serializers KafkaAvroSerializer)
           (java.util UUID)
           (java.net Socket)))

(defmacro try-for [msg how-long & body]
  `(let [start-time# (System/currentTimeMillis)]
     (loop []
       (let [[status# return#] (try
                                 (let [result# (do ~@body)]
                                   [(if result# ::ok ::fail) result#])
                                 (catch Throwable e# [::error e#]))
             more-time# (> (* ~how-long 1000)
                           (- (System/currentTimeMillis) start-time#))]
         (cond
           (= status# ::ok) return#
           more-time# (do (Thread/sleep 1000) (recur))
           (= status# ::fail) (throw (ex-info (str "Failed: " ~msg) {:last-result return#}))
           (= status# ::error) (throw (RuntimeException. (str "Failed: " ~msg) return#)))))))

(defn wait-for-server [host port]
  (try-for (str "Nobody listening at " host ":" port) 60
           (with-open [_ (Socket. host (int port))]
             true)))

(defn check-db-is-up [f]
  (try-for "DB not ready"
           60
           (jdbc/with-db-connection
             [conn {:connection-uri (System/getenv "DATABASE_URL")}]
             (jdbc/query conn ["select * from datapoint"])))
  (f))

(defn check-servers-up [f]
  (wait-for-server "windshaft" 4000)
  (wait-for-server "flow-maps" 3000)
  (wait-for-server "redis" 6379)
  (wait-for-server "schema-registry" 8081)
  (wait-for-server "kafka" 29092)
  (f))

(test/use-fixtures :once check-db-is-up)
(test/use-fixtures :once check-servers-up)

(def DataPointSchema-as-json
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
                 {:name "last_update_date_time", :type "long"}]}))

(def DataPointSchema (avro/parse-schema DataPointSchema-as-json))

(defn create-producer []
  (producer/make-producer
    {:bootstrap.servers (System/getenv "KAFKA_SERVERS")
     :acks              "all"
     :retries           1
     :client.id         "test-producer"}
    (serializers/long-serializer)
    (doto
      (KafkaAvroSerializer.)
      (.configure {"schema.registry.url" (System/getenv "KAFKA_SCHEMA_REGISTRY")} false))))

(defn push-data-point [data-point topic]
  (with-open [producer (create-producer)]
    (send-sync!
      producer
      {:topic (str topic "_datapoint")
       :key   (get data-point "surveyId")
       :value (avro/->java DataPointSchema data-point)})))

(defonce http-client (http/create-client {:connection-timeout 10000
                                          :request-timeout    10000
                                          :max-connections    10}))

(defn json-request [req]
  (let [res (-> (http/proxy-request http-client
                                    (update req :headers merge {"content-type" "application/json"}))
                (update :status :code)
                (update :body (fn [body] (json/parse-string body true))))]
    (debug "resp -> " res)
    (when (:error res)
      (throw (ex-info "Error in response" res)))
    res))

(defn create-map [datapoint-id topic]
  (json-request {:method :post
                 :url    "http://flow-maps:3000/create-map"
                 :body   (json/generate-string
                           {:topic topic
                            :map   {:version "1.5.0",
                                    :layers  [{:type    "mapnik",
                                               :options {:sql              (str "select * from datapoint where id='" datapoint-id "'"),
                                                         :geom_column      "geom",
                                                         :srid             4326,
                                                         :cartocss         "#s { marker-width: 10; marker-fill: #e00050; }",
                                                         :cartocss_version "2.0.0",
                                                         :interactivity    "id"}}]}})}))

(defn random-id []
  (str (UUID/randomUUID)))

(defn random-data-point []
  {:identifier            (random-id)
   :latitude              (- (rand (- 160 0.00001)) 80)
   :longitude             (- (rand (- 360 0.00001)) 180)
   :survey-id             20
   :created-date-time     (System/currentTimeMillis)
   :last-update-date-time (System/currentTimeMillis)})

(defn ids-in-tile [tile]
  (->> tile :body :data vals (map :id) set))

(defn map-has [datapoint topic]
  (let [response (create-map (:identifier datapoint) topic)
        layer-group (-> response :body :layergroupid)]
    (assert (= 200 (:status response)) "create map request failing")
    (assert (not (clojure.string/blank? layer-group)) "no layer group id?")
    (info "layer and datapoint" layer-group datapoint)
    (try-for "No data point"
             30
             (let [tile (json-request
                          {:method :get
                           :url    (str "http://windshaft:4000/layergroup/" layer-group "/0/0/0/0.grid.json")})]
               (assert (= 200 (:status tile)) "tile request failing")
               (assert (= (:identifier datapoint) (first (ids-in-tile tile))) "data point not found in map")
               tile))))

(defn map-has-not [datapoint topic]
  (let [response (create-map (:identifier datapoint) topic)
        layer-group (-> response :body :layergroupid)]
    (assert (= 200 (:status response)) "create map request failing")
    (assert (not (clojure.string/blank? layer-group)) "no layer group id?")
    (info "layer and datapoint" layer-group datapoint)
    (let [tile (json-request
                 {:method :get
                  :url    (str "http://windshaft:4000/layergroup/" layer-group "/0/0/0/0.grid.json")})]
      (assert (= 200 (:status tile)) "tile request failing")
      (assert (empty? (ids-in-tile tile)) "data point found in map")
      tile)))

(deftest do-not-mix-data-from-different-topics
  (let [datapoint (random-data-point)
        datapoint-topic-a (assoc datapoint :identifier (random-id))
        datapoint-topic-b (assoc datapoint :identifier (random-id))
        _ (info (push-data-point datapoint-topic-a "topic_a"))
        _ (info (push-data-point datapoint-topic-b "topic_b"))]
    (map-has datapoint-topic-a "topic_a")
    (map-has datapoint-topic-b "topic_b")
    (map-has-not datapoint-topic-b "topic_a")
    (map-has-not datapoint-topic-a "topic_b")))