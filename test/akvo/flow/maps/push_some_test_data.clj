(ns akvo.flow.maps.push-some-test-data
  (:require
    [franzy.clients.producer.protocols :refer [send-sync! send-async!]]
    [thdr.kfk.avro-bridge.core :as avro]
    [cheshire.core :as json]
    [akvo.flow.maps.end-to-end-test :as end-to-end])
  (:import (java.io ByteArrayOutputStream)
           (org.apache.avro.io EncoderFactory)
           (org.apache.avro.generic GenericDatumWriter)
           (java.nio.charset Charset)))

(defn parse-line [line]
  (-> (read-string line)
      (select-keys ["identifier" "surveyGroupId" "longitude" "latitude" "createdDateTime" "lastUpdateDateTime"])
      (clojure.set/rename-keys {"surveyGroupId" "surveyId"})
      (update "createdDateTime" #(.getTime %))
      (update "lastUpdateDateTime" #(.getTime %))))

(comment
  ;;; Push through Kafka
  (with-open [rdr (clojure.java.io/reader "akvoflowsandbox.SurveyedLocale.edn")
              producer (end-to-end/create-producer)]
    (dorun (for [line (line-seq rdr)]
             (let [value (parse-line line)]
               (send-async!
                 producer
                 {:topic (str "org.akvo.akvoflowsandbox.datapoint")
                  :key   (get value "surveyId")
                  :value (avro/->java end-to-end/DataPointSchema
                                      value)})))))

  ;;; Push through Rest proxy
  (defn ->avro-json [o]
    (let [bo (ByteArrayOutputStream.)
          enc (.jsonEncoder (EncoderFactory/get) end-to-end/DataPointSchema bo)]
      (.write (GenericDatumWriter. end-to-end/DataPointSchema)
              (avro/->java end-to-end/DataPointSchema o)
              enc)
      (.flush enc)
      (.close bo)
      (String. (.toByteArray bo) (Charset/forName "UTF-8"))))

  (with-open [rdr (clojure.java.io/reader "akvoflowsandbox.SurveyedLocale.edn")
              http-client (akvo.flow.maps.map-creation.http-proxy/create-client {:connection-timeout 10000
                                                                             :request-timeout    20000
                                                                             :max-connections    2})]
    (dorun (for [line (line-seq rdr)]
             (let [value (parse-line line)]
               (let [r (akvo.flow.maps.map-creation.http-proxy/proxy-request
                         http-client
                         {:method  :post
                          :headers {"content-type" "application/vnd.kafka.avro.v2+json"
                                    "Accept"       "application/vnd.kafka.v2+json"}
                          :url     "http://35.195.81.104:80/topics/topic-a.datapoint"
                          :body    (json/generate-string {:value_schema end-to-end/DataPointSchema-as-json
                                                          :records      [{:value (json/parse-string (->avro-json value))}]})})]
                 (if (or (:error r) (not= (:code (:status r)) 200))
                   (throw (ex-info "" (assoc r :value value)))
                   (println (:status r) (:body r)))))))))