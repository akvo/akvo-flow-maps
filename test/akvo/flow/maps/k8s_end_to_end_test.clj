(ns akvo.flow.maps.k8s-end-to-end-test
  {:kubernetes-test true}
  (:require
    [thdr.kfk.avro-bridge.core :as avro]
    [cheshire.core :as json]
    [clojure.tools.logging :refer [info debug]]
    [clojure.test :refer :all]
    [akvo.flow.maps.end-to-end-test :as end-to-end])
  (:import
    (java.nio.charset Charset)
           (org.apache.avro.generic GenericDatumWriter)
           (org.apache.avro.io EncoderFactory)
           (java.io ByteArrayOutputStream)))

(defn check-servers-up [f]
  ;; wait for the version to be deployed
  (f))

(use-fixtures :once check-servers-up)

(defn full-topic [topic]
  (str topic ".datapoint"))


(defn ->avro-json [o]
  (let [bo (ByteArrayOutputStream.)
        enc (.jsonEncoder (EncoderFactory/get) end-to-end/DataPointSchema bo)]
    (.write (GenericDatumWriter. end-to-end/DataPointSchema)
            (avro/->java end-to-end/DataPointSchema o)
            enc)
    (.flush enc)
    (.close bo)
    (String. (.toByteArray bo) (Charset/forName "UTF-8"))))

(defn push-data-point [data-point topic]
  (let [value data-point]
    (end-to-end/json-request {:method  :post
                              :headers {"content-type" "application/vnd.kafka.avro.v2+json"
                                        "Accept"       "application/vnd.kafka.v2+json"}
                              :url     (str "http://35.195.81.104:80/topics/" (full-topic topic))
                              :body    (json/generate-string {:value_schema end-to-end/DataPointSchema-as-json
                                                              :records      [{:value (json/parse-string (->avro-json value))}]})})))

(defn create-map [datapoint-id topic]
  (end-to-end/json-request {:method :post
                            :url    "http://flowmaps.akvotest.org/create-map"
                            :body   (json/generate-string
                                      {:topic (full-topic topic)
                                       :map   {:version "1.5.0",
                                               :layers  [{:type    "mapnik",
                                                          :options {:sql              (str "select * from datapoint where identifier='" datapoint-id "'"),
                                                                    :geom_column      "geom",
                                                                    :srid             4326,
                                                                    :cartocss         "#s { marker-width: 10; marker-fill: #e00050; }",
                                                                    :cartocss_version "2.0.0",
                                                                    :interactivity    "identifier"}}]}})}))

(defn create-map-and-get-tile [datapoint topic]
  (let [response (create-map (:identifier datapoint) topic)
        layer-group (-> response :body :layergroupid)]
    (assert (= 200 (:status response)) "create map request failing")
    (assert (not (clojure.string/blank? layer-group)) "no layer group id?")
    (info "layer and datapoint" layer-group datapoint)
    (let [tile (end-to-end/json-request
                 {:method :get
                  :url    (str "http://flowmaps.akvotest.org/layergroup/" layer-group "/0/0/0/0.grid.json")})]
      (assert (= 200 (:status tile)) "tile request failing")
      (end-to-end/ids-in-tile tile))))

(defn map-has [datapoint topic]
  (end-to-end/try-for
    "Maps not working!" 60
    (assert (= (:identifier datapoint)
               (first (create-map-and-get-tile datapoint topic)))
            "data point not found in map")
    :datapoint-found!))

(deftest shows-data-from-one-dp
  (let [datapoint (end-to-end/random-data-point)
        datapoint-topic-a (assoc datapoint :identifier (end-to-end/random-id))
        _ (info (push-data-point datapoint-topic-a "k8s-test"))]
    (map-has datapoint-topic-a "k8s-test")))