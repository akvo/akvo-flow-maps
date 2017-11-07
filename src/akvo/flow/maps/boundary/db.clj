(ns akvo.flow.maps.boundary.db
  (:require
    clojure.set
    [integrant.core :as ig]
    [clojure.java.jdbc :as jdbc]
    ragtime.jdbc
    [hugsql.core :as hugsql]
    [clojure.string :as s]
    [clojure.string :as string]
    [hugsql.parameters :as parameters]))

(hugsql/def-db-fns "akvo/flow/maps/boundary/datapoints.sql")
(hugsql/def-sqlvec-fns "akvo/flow/maps/boundary/datapoints.sql")

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

(defn insert-batch [db datapoints]
  (when-let [db-datapoints (->> datapoints
                                (map ->db-value)
                                (filter valid?)
                                seq)]
    (let [[x & xs] db-datapoints
          [sql & first-row] (upsert-datapoint-sqlvec x)
          other-rows (mapv (comp rest upsert-datapoint-sqlvec) xs)]
      (jdbc/execute! db
                     (into [sql first-row] other-rows)
                     {:transaction? true
                      :multi?       true}))))

(comment
  (time
    (dotimes [_ 10]
      (do
        (insert-batch "jdbc:postgresql://postgres/avlkmasdlkvm1510061891540?user=dnxbtrsqco5sdnklrfkzbbvme4zyx9izqwjvs&password=pEdfR8_b4n2dDMAQl10O3hTDyBPhNk1d5vbFN"
                      inserts)
        :done))))