(ns akvo.flow.maps.kafka.datapoint-processing
  (:require
    [akvo.flow.maps.master-db.core :as master-db]
    clojure.set
    [clojure.java.jdbc :as jdbc]
    [clojure.tools.logging :as log]
    [again.core :as again]
    [iapetos.core :as prometheus]
    [iapetos.collector.exceptions :as ex]))

(defn ->db-timestamp [v]
  (when v
    (java.sql.Timestamp. v)))

(defn ->db-value [record]
  (-> record
      (update :created-date-time ->db-timestamp)
      (update :last-update-date-time ->db-timestamp)))

(defn valid? [record]
  (and (:identifier record)
       (:latitude record)
       (:longitude record)
       (:survey-id record)))

(defn actions [dbs-credentials kafka-messages]
  (let [->actions (fn [[topic messages]]
                    (let [valid-datapoints (->> messages
                                                (map :value)
                                                (map ->db-value)
                                                (filter valid?))]

                      (cond-> []
                              (and (seq valid-datapoints) (not (master-db/is-db-ready? (get dbs-credentials topic))))
                              (conj [:create-db {:tenant topic}])

                              (seq valid-datapoints)
                              (conj [:upsert {:tenant topic
                                              :rows   valid-datapoints}])

                              true (conj [:stats {:topic     topic
                                                  :upsert    (count valid-datapoints)
                                                  :discarded (- (count messages) (count valid-datapoints))
                                                  :total     (count messages)}]))))]
    (->> kafka-messages
         (group-by :topic)
         (mapcat ->actions))))

(defn- insert-batch [db datapoints]
  (let [db-datapoints (map (juxt :identifier :survey-id :last-update-date-time :created-date-time :longitude :latitude
                                 :survey-id :last-update-date-time :created-date-time :longitude :latitude :id)
                           datapoints)]
    (jdbc/execute! (clojure.set/rename-keys db {:database :dbname
                                                :username :user})
                   (into ["insert into datapoint(identifier, survey_id, created_date_time, last_update_date_time, geom)
                                 values (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                                 ON conflict(identifier)
                         do update set (survey_id, created_date_time, last_update_date_time, geom) = (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                            where datapoint.identifier = ?"]
                         db-datapoints)
                   {:transaction? true
                    :multi?       true})))

(defmacro metrics
  [metrics-collector fn-name additional-labels & body]
  `(let [labels# (merge {:fn ~fn-name, :result "success"} ~additional-labels)
         failure-labels# (assoc labels# :result "failure")]
     (prometheus/with-success-counter (~metrics-collector :fn/runs-total labels#)
       (prometheus/with-failure-counter (~metrics-collector :fn/runs-total failure-labels#)
         (ex/with-exceptions (~metrics-collector :fn/exceptions-total labels#)
           (prometheus/with-duration (~metrics-collector :fn/duration-seconds labels#)
             ~@body))))))

(defn process-messages [db metrics-collector datapoints]
  (when (seq datapoints)
    (let [plan (actions (master-db/known-dbs db) datapoints)]
      (log/debug plan)
      (doseq [[action param] plan]
        (again/with-retries
          [100 1000 10000]
          (case action
            :stats
            (do
              (prometheus/inc metrics-collector :datapoint/process {:topic (:topic param) :name "total"} (:total param))
              (prometheus/inc metrics-collector :datapoint/process {:topic (:topic param) :name "discarded"} (:discarded param))
              (prometheus/inc metrics-collector :datapoint/process {:topic (:topic param) :name "upsert"} (:upsert param))
              (log/info param))

            :upsert
            (metrics metrics-collector "upsert-datapoints"
              {:topic (:tenant param) :batch-size (count (:rows param))}
              (insert-batch (master-db/pool-for-tenant db (:tenant param)) (:rows param)))

            :create-db
            (master-db/create-tenant-db db (:tenant param))))))))