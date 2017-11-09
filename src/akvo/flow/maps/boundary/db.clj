(ns akvo.flow.maps.boundary.db
  (:require
    [akvo.flow.maps.boundary.master-db :as master-db]
    clojure.set
    [clojure.java.jdbc :as jdbc]
    [clojure.tools.logging :as log]))

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
                              (and (seq valid-datapoints) (not (contains? dbs-credentials topic)))
                              (conj [:create-db {:tenant topic}])

                              (seq valid-datapoints)
                              (conj [:upsert {:tenant topic
                                              :rows   valid-datapoints}])

                              true (conj [:stats {:topic     topic
                                                  :upsert    (count valid-datapoints)
                                                  :discarded (- (count messages) (count valid-datapoints))}]))))]
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

(defn process-messages [db datapoints]
  (when (seq datapoints)
    (let [plan (actions (master-db/existing-dbs db) datapoints)]
      (log/debug plan)
      (doseq [[action param] plan]
        (case action
          :stats (log/info param)
          :upsert (insert-batch (master-db/pool-for-tenant db (:tenant param)) (:rows param))
          :create-db (master-db/create-tenant-db db (:tenant param)))))))