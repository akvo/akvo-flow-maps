(ns akvo.flow.maps.push-some-test-data
  (:require
    [franzy.clients.producer.protocols :refer [send-sync! send-async!]]
    [thdr.kfk.avro-bridge.core :as avro]
    [cheshire.core :as json]
    [akvo.flow.maps.end-to-end-test :as end-to-end]
    akvo.flow.maps.k8s-end-to-end-test))

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

  ;;; Push through TEST Rest proxy
  (with-open [rdr (clojure.java.io/reader "akvoflowsandbox.SurveyedLocale.edn")]
    (dorun (for [line (line-seq rdr)]
             (let [value (parse-line line)]
               (let [r (akvo.flow.maps.k8s-end-to-end-test/push-data-point value "org.akvo.akvoflowsandbox")]
                 (if (or (:error r) (not= (:status r) 200))
                   (throw (ex-info "" (assoc r :value value)))
                   (println (:status r) (:body r)))))))))