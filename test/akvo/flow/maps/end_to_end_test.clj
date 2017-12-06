(ns akvo.flow.maps.end-to-end-test
  {:integration true}
  (:require
    [franzy.clients.producer.client :as producer]
    [franzy.clients.producer.protocols :refer [send-sync! send-async!]]
    [thdr.kfk.avro-bridge.core :as avro]
    [cheshire.core :as json]
    [franzy.serialization.serializers :as serializers]
    [akvo.flow.maps.map-creation.http-proxy :as http]
    [clojure.tools.logging :refer [info debug]]
    [clojure.test :refer :all]
    [clojure.java.jdbc :as jdbc]
    [clojure.test :as test])
  (:import (io.confluent.kafka.serializers KafkaAvroSerializer)
           (java.util UUID)
           (java.net Socket)
           (com.fasterxml.jackson.core JsonParseException)))

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
  (wait-for-server "keycloak" 8080)
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

(defn full-topic [topic]
  (str topic ".datapoint"))

(defn push-data-point [data-point topic]
  (with-open [producer (create-producer)]
    (send-sync!
      producer
      {:topic (full-topic topic)
       :key   (get data-point "surveyId")
       :value (avro/->java DataPointSchema data-point)})))

(defonce http-client (http/create-client {:connection-timeout 10000
                                          :request-timeout    10000
                                          :max-connections    10}))

(defn json-request [req]
  (let [res (-> (http/proxy-request http-client
                                    (update req :headers (fn [req-headers]
                                                           (merge {"content-type" "application/json"} req-headers))))
                (update :status :code)
                (update :body (fn [body]
                                (try
                                  (json/parse-string body true)
                                  (catch JsonParseException _ (throw
                                                                (RuntimeException. (str "expecting json response, was:'" body "'"))))))))]
    (debug "resp -> " res)
    (when (:error res)
      (throw (ex-info "Error in response" res)))
    res))

(defn access-token [{:keys [url user password]}]
  (-> (json-request {:method  :post
                     :url     (str url "/realms/akvo/protocol/openid-connect/token")
                     :headers {"content-type" "application/x-www-form-urlencoded"}
                     :auth    {:type       :basic
                               :user       user
                               :password   password
                               :preemptive true}
                     :body    {:grant_type "client_credentials"}})
      :body
      :access_token))

(defn create-map-request [url datapoint-id topic]
  {:method :post
   :url    url
   :body   (json/generate-string
             {:topic (full-topic topic)
              :map   {:version "1.5.0",
                      :layers  [{:type    "mapnik",
                                 :options {:sql              (str "select * from datapoint where identifier='" datapoint-id "'"),
                                           :geom_column      "geom",
                                           :srid             4326,
                                           :cartocss         "#s { marker-width: 10; marker-fill: #e00050; }",
                                           :cartocss_version "2.0.0",
                                           :interactivity    "identifier"}}]}})})

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
  (->> tile :body :data vals (map :identifier) set))

(defn create-map-and-get-tile [{:keys [create-map-url tiles-url keycloak]} datapoint topic]
  (let [request (create-map-request create-map-url (:identifier datapoint) topic)
        request-with-auth (update request
                                  :headers
                                  merge {"Authorization" (str "Bearer " (access-token keycloak))})
        response (json-request request-with-auth)
        layer-group (-> response :body :layergroupid)]
    (assert (= 200 (:status response)) "create map request failing")
    (assert (not (clojure.string/blank? layer-group)) "no layer group id?")
    (info "layer and datapoint" layer-group datapoint)
    (let [tile (json-request
                 {:method :get
                  :url    (str tiles-url "/layergroup/" layer-group "/0/0/0/0.grid.json")})]
      (assert (= 200 (:status tile)) "tile request failing")
      (ids-in-tile tile))))

(defn map-has [config datapoint topic]
  (try-for
    "Maps not working!" 60
    (assert (= (:identifier datapoint)
               (first (create-map-and-get-tile config datapoint topic)))
            "data point not found in map")
    :datapoint-found!))

(defn map-has-not [config datapoint topic]
  (assert (empty? (create-map-and-get-tile config datapoint topic)) "data point found in map"))

(def config {:create-map-url "http://nginx/create-map"
             :tiles-url      "http://nginx"
             :keycloak       {:url      (System/getenv "KEYCLOAK_URL")
                              :user     "akvo-flow"
                              :password "3918fbb4-3bc3-445a-8445-76826603b227"}})

(deftest do-not-mix-data-from-different-topics
  (let [datapoint (random-data-point)
        datapoint-topic-a (assoc datapoint :identifier (random-id))
        datapoint-topic-b (assoc datapoint :identifier (random-id))
        _ (info (push-data-point datapoint-topic-a "topic-a"))
        _ (info (push-data-point datapoint-topic-b "topic-b"))]
    (map-has config datapoint-topic-a "topic-a")
    (map-has config datapoint-topic-b "topic-b")
    (map-has-not config datapoint-topic-b "topic-a")
    (map-has-not config datapoint-topic-a "topic-b")))

(deftest map-creation-is-protected
  (let [_ (info (push-data-point (random-data-point) "topic-a"))
        request-without-auth (create-map-request (:create-map-url config) "any-datapoint-id" "topic-a")]
    (try-for
      "Maps are not secure" 60
      (assert (= 401
                 (:status
                   (json-request request-without-auth))))
      :401-is-great!)))