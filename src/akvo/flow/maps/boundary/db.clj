(ns akvo.flow.maps.boundary.db
  (:require
    clojure.set
    [clojure.java.jdbc :as jdbc]))

(comment
  (jdbc/execute! (dev/db) ["DELETE from datapoint"])
  (jdbc/query (dev/db) ["select count(*) from datapoint"])
  )

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

(defn insert-batch [db datapoints]
  (when-let [db-datapoints (->> datapoints
                                (map ->db-value)
                                (filter valid?)
                                (map (juxt :identifier :survey-id :last-update-date-time :created-date-time :longitude :latitude
                                           :survey-id :last-update-date-time :created-date-time :longitude :latitude :id))
                                seq)]
    (jdbc/execute! db
                   (into ["insert into datapoint(id, survey_id, created_date_time, last_update_date_time, geom)
                                 values (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                                 ON conflict(id)
                         do update set (survey_id, created_date_time, last_update_date_time, geom) = (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                            where datapoint.id = ?"]
                         db-datapoints)
                   {:transaction? true
                    :multi?       true})))