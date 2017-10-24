(ns akvo.flow.maps.boundary.db
  (:require
    clojure.set
    [clojure.java.jdbc :as jdbc]))

(def jdbc "jdbc:postgresql://postgres/a_tenant_db?user=a_tenant_user&password=a_tenant_password")

(comment
  (jdbc/execute! jdbc ["DELETE from datapoint"]))

(defn ->db-timestamp [v]
  (when v
    (java.sql.Timestamp. v)))

(defn ->db-value [record]
  (-> record
      (clojure.set/rename-keys {:identifier :id})
      (update :created-date-time ->db-timestamp)
      (update :last-update-date-time ->db-timestamp)))

(defn valid? [record]
  (and (:id record)
       (:latitude record)
       (:longitude record)
       (:survey-id record)))

(defn insert-batch [datapoints]
  (when-let [db-datapoints (->> datapoints
                                (map ->db-value)
                                (filter valid?)
                                (map (juxt :id :survey-id :last-update-date-time :created-date-time :latitude :longitude
                                           :survey-id :last-update-date-time :created-date-time :latitude :longitude :id))
                                seq)]
    (jdbc/execute! jdbc
                   (into ["insert into datapoint(id, survey_id, created_date_time, last_update_date_time, geom)
                                 values (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                                 ON conflict(id)
                         do update set (survey_id, created_date_time, last_update_date_time, geom) = (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                            where datapoint.id = ?"]
                         db-datapoints)
                   {:transaction? true
                    :multi?       true})))
