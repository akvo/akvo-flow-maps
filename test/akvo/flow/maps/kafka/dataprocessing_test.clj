(ns akvo.flow.maps.kafka.dataprocessing-test
  (:require
    [akvo.flow.maps.kafka.datapoint-processing :as dp]
    [clojure.test :refer :all]))

(deftest happy-path
  (let [actions (dp/actions {"topic-a.datapoint" :existing}
                            [{:topic     "topic-a.datapoint",
                              :partition 0,
                              :offset    24,
                              :key       nil,
                              :value     {:identifier            "id0",
                                          :survey-id             20,
                                          :longitude             50.82581103065513,
                                          :latitude              -18.952741795895086,
                                          :created-date-time     1509724347835,
                                          :last-update-date-time 1509724347835}}
                             {:topic     "topic-b.datapoint",
                              :partition 0,
                              :offset    25,
                              :key       nil,
                              :value     {:identifier            "id1",
                                          :survey-id             20,
                                          :longitude             50.82581103065513,
                                          :latitude              -18.952741795895086,
                                          :created-date-time     1509724347835,
                                          :last-update-date-time 1509724347835}}
                             {:topic     "topic-a.datapoint",
                              :partition 0,
                              :offset    26,
                              :key       nil,
                              :value     {:identifier            "id2",
                                          :survey-id             20,
                                          :longitude             50.82581103065513,
                                          :latitude              -18.952741795895086,
                                          :created-date-time     1509724347835,
                                          :last-update-date-time 1509724347835}}])]
    (is (= [[:upsert {:tenant "topic-a.datapoint"
                      :rows   [{:identifier            "id0",
                                :survey-id             20,
                                :longitude             50.82581103065513,
                                :latitude              -18.952741795895086,
                                :created-date-time     #inst "2017-11-03T15:52:27.835-00:00",
                                :last-update-date-time #inst "2017-11-03T15:52:27.835-00:00"}
                               {:identifier            "id2",
                                :survey-id             20,
                                :longitude             50.82581103065513,
                                :latitude              -18.952741795895086,
                                :created-date-time     #inst "2017-11-03T15:52:27.835-00:00",
                                :last-update-date-time #inst "2017-11-03T15:52:27.835-00:00"}]}]
            [:stats {:topic "topic-a.datapoint" :discarded 0 :upsert 2}]
            [:create-db {:tenant "topic-b.datapoint"}]
            [:upsert {:tenant "topic-b.datapoint"
                      :rows   [{:identifier            "id1",
                                :survey-id             20,
                                :longitude             50.82581103065513,
                                :latitude              -18.952741795895086,
                                :created-date-time     #inst "2017-11-03T15:52:27.835-00:00",
                                :last-update-date-time #inst "2017-11-03T15:52:27.835-00:00"}]}]
            [:stats {:topic "topic-b.datapoint" :discarded 0 :upsert 1}]]
           actions))))



(deftest do-nothing-if-all-rows-are-invalid
  (let [actions (dp/actions {} [{:topic     "topic-a.datapoint",
                                 :partition 0,
                                 :offset    24,
                                 :key       nil,
                                 :value     {:identifier            "id0",
                                             :survey-id             20,
                                             :longitude             nil,
                                             :latitude              -18.952741795895086,
                                             :created-date-time     1509724347835,
                                             :last-update-date-time 1509724347835}}])]
    (is (= [[:stats {:topic "topic-a.datapoint" :discarded 1 :upsert 0}]]
           actions))))
