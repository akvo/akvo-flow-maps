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
           (java.util UUID)))

(defmacro try-for [how-long & body]
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
           (= status# ::fail) (throw (ex-info "Failed" {:last-result return#}))
           (= status# ::error) (throw return#))))))

(defn check-db-is-up [f]
  (try-for 60
           (jdbc/with-db-connection
             [conn {:connection-uri (System/getenv "DATABASE_URL")}]
             (jdbc/query conn ["select * from datapoint"])))
  (f))

(test/use-fixtures :once check-db-is-up)

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

(defn push-data-point [data-point]
  (with-open [producer (producer/make-producer
                         {:bootstrap.servers (System/getenv "KAFKA_SERVERS")
                          :acks              "all"
                          :retries           1
                          :client.id         "test-producer"}
                         (serializers/long-serializer)
                         (doto
                           (KafkaAvroSerializer.)
                           (.configure {"schema.registry.url" (System/getenv "KAFKA_SCHEMA_REGISTRY")} false)))]
    (send-sync!
      producer
      {:topic (str "org.akvo.akvoflowsandbox.datapoint")
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

(defn create-map [datapoint-id]
  (json-request {:method :post
                 :url    "http://flow-maps:3000/create-map"
                 :body   (json/generate-string
                           {:version "1.5.0",
                            :layers  [{:type    "mapnik",
                                       :options {:sql              (str "select * from datapoint where id='" datapoint-id "'"),
                                                 :geom_column      "geom",
                                                 :srid             4326,
                                                 :cartocss         "#s { marker-width: 10; marker-fill: #e00050; }",
                                                 :cartocss_version "2.0.0",
                                                 :interactivity    "id"}}]})}))

(deftest happy-path
         (let [datapoint-id (str (UUID/randomUUID))
               _ (info (push-data-point {:identifier            datapoint-id
                                         :survey-id             20
                                         :latitude              (- (rand (- 160 0.00001)) 80)
                                         :longitude             (- (rand (- 360 0.00001)) 180)
                                         :created-date-time     (System/currentTimeMillis)
                                         :last-update-date-time (System/currentTimeMillis)}))
               layer-group (try-for 60
                                    (let [response (create-map datapoint-id)
                                          layer-group (-> response :body :layergroupid)]
                                      (and
                                        (= 200 (:status response))
                                        (not (clojure.string/blank? layer-group))
                                        layer-group)))]

           (try-for 10
                    (let [tile (json-request
                                 {:method :get
                                  :url    (str "http://windshaft:4000/layergroup/" layer-group "/0/0/0/0.grid.json")})]
                      (and (= 200 (:status tile))
                           (= datapoint-id (get-in (json-request
                                                     {:method :get
                                                      :url    (str "http://windshaft:4000/layergroup/" layer-group "/0/0/0/0.grid.json")})
                                                   [:body :data :2 :id])))))))