(ns akvo.flow.maps.boundary.master-db
  (:require
    [integrant.core :as ig]
    ring.middleware.params
    clojure.set
    clojure.string
    clojure.walk
    ragtime.jdbc
    [hikari-cp.core :as hikari]
    [akvo.flow.maps.boundary.create-tenant :as create-tenant])
  (:import (com.zaxxer.hikari HikariConfig)))

(defmethod ig/init-key ::migration [_ config]
  (ragtime.jdbc/load-resources "akvo/flow/maps/db"))

(defn register-tenant-pool [pool-atom tenant-info]
  (swap! pool-atom
         (fn [current-tenants]
           (if (contains? current-tenants (:tenant tenant-info))
             current-tenants
             (assoc current-tenants
               (:tenant tenant-info)
               {::info tenant-info
                ::connection-pool
                      {:datasource
                       (hikari/make-datasource {:jdbc-url          (:db-uri tenant-info)
                                                :idle-timeout      300000
                                                :minimum-idle      0
                                                :configure         (fn [^HikariConfig config]
                                                                     (.setInitializationFailTimeout config -1))
                                                :maximum-pool-size 1})}})))))

(defn pool-for-tenant [master-db tenant]
  (-> master-db ::tenants deref (get tenant) ::connection-pool))

(defn known-dbs [master-db]
  (-> master-db ::tenants deref keys set))

(defn tenant-info [master-db tenant]
  (let [look-up-info #(-> master-db ::tenants deref (get tenant) ::info :db-uri create-tenant/parse-postgres-jdbc)]
    (or (look-up-info)
        (when-let [tenant-creds (create-tenant/load-tenant-info (::master-db-pool master-db) {:tenant tenant})]
          (register-tenant-pool (::tenants master-db) tenant-creds)
          (look-up-info)))))

(defn create-tenant-db [master-db tenant]
  (let [tenant-info (create-tenant/create-tenant-db (::master-db-url master-db) tenant)]
    (register-tenant-pool (::tenants master-db) tenant-info)
    :done))

(defn- load-tenants-and-create-connection-pools [db]
  (let [tenants (atom {})]
    (doseq [tenant-info (create-tenant/load-all-tenant-info db)]
      (register-tenant-pool tenants tenant-info))
    tenants))

(defmethod ig/init-key ::master-db [_ {:keys [master-db-pool master-db-url]}]
  (let [master-db-pool (:spec master-db-pool)
        tenant-creds (load-tenants-and-create-connection-pools master-db-pool)]
    {::tenants        tenant-creds
     ::master-db-url  master-db-url
     ::master-db-pool master-db-pool}))

(defmethod ig/halt-key! ::master-db [_ {:keys [::tenants]}]
  (doseq [tenant (->> tenants deref vals)
          :let [pool (-> tenant ::connection-pool :datasource)]]
    (hikari/close-datasource pool)))

(comment
  (clojure.java.jdbc/execute! (dev/db) ["DROP DATABASE afm_topic_a_datapoint"] {:transaction? false})
(clojure.java.jdbc/query (dev/db) ["SELECT pg_terminate_backend(pg_stat_activity.pid)\nFROM pg_stat_activity\n WHERE pid <> pg_backend_pid();"])
  (>/print-table (clojure.java.jdbc/query (dev/db) ["select * from pg_database"] {:transaction? false}))
  (ragtime.core/rollback (ragtime.jdbc/sql-database (dev/db)) (first (ragtime.jdbc/load-resources "akvo/flow/maps/db")))
  (ragtime.core/migrate-all (ragtime.jdbc/sql-database (dev/db)) {} (ragtime.jdbc/load-resources "akvo/flow/maps/db")))