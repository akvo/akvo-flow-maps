(ns akvo.flow.maps.kafka.dataprocessing-test
  (:require
    [akvo.flow.maps.kafka.datapoint-processing :as dp]
    [clojure.test :refer :all]))

(defn kafka-message [topic value]
  {:topic     topic,
   :partition 0,
   :offset    24,
   :key       nil,
   :value     value})

(defn datapoint [id]
  {:identifier            id,
   :survey-id             20,
   :longitude             50.82581103065513,
   :latitude              -18.952741795895086,
   :created-date-time     1509724347835,
   :last-update-date-time 1509724347835})

(defn row [id]
  {:identifier            id,
   :survey-id             20,
   :longitude             50.82581103065513,
   :latitude              -18.952741795895086,
   :created-date-time     #inst "2017-11-03T15:52:27.835-00:00",
   :last-update-date-time #inst "2017-11-03T15:52:27.835-00:00"})

(deftest happy-path
  (let [actions (dp/actions {"topic-a.datapoint" {:db-creation-state "done"}}
                            [(kafka-message "topic-a.datapoint" (datapoint "id0"))
                             (kafka-message "topic-b.datapoint" (datapoint "id1"))
                             (kafka-message "topic-a.datapoint" (datapoint "id2"))])]

    (is (= [[:upsert {:tenant "topic-a.datapoint"
                      :rows   [(row "id0")
                               (row "id2")]}]
            [:stats {:topic "topic-a.datapoint" :discarded 0 :upsert 2 :total 2}]

            [:create-db {:tenant "topic-b.datapoint"}]
            [:upsert {:tenant "topic-b.datapoint"
                      :rows   [(row "id1")]}]
            [:stats {:topic "topic-b.datapoint" :discarded 0 :upsert 1 :total 1}]]
           actions))

    (is (= (dp/upsert-metrics-labels (second (first actions)))
           {:topic      "topic-a.datapoint"
            :batch-size "2-10"}))))

(deftest do-nothing-if-all-rows-are-invalid
  (let [actions (dp/actions {} [(kafka-message "topic-a.datapoint" (assoc (datapoint "any id") :longitude nil))])]
    (is (= [[:stats {:topic "topic-a.datapoint" :discarded 1 :upsert 0 :total 1}]]
           actions))))

(deftest create-db-if-it-is-not-created-yet
  (let [actions (dp/actions {"topic-a.datapoint" {:db-creation-state "creating"}}
                            [(kafka-message "topic-a.datapoint" (datapoint "id0"))])]
    (is (= [[:create-db {:tenant "topic-a.datapoint"}]
            [:upsert {:tenant "topic-a.datapoint"
                      :rows   [(row "id0")]}]
            [:stats {:topic "topic-a.datapoint" :discarded 0 :upsert 1 :total 1}]]
           actions))))

(deftest batch-sizes
  (are [number-of-records expected-label] (= (dp/upsert-metrics-labels {:tenant "_" :rows (range number-of-records)})
                                             {:topic "_" :batch-size expected-label})
    1 "1"
    2 "2-10"
    5 "2-10"
    10 "2-10"
    11 "11-50"
    51 "51-100"
    101 "101+"))