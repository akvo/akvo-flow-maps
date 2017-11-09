(ns akvo.flow.maps.boundary.master-db
  (:require
    [integrant.core :as ig]
    ring.middleware.params
    clojure.set
    clojure.string
    clojure.walk
    ragtime.jdbc
    [hikari-cp.core :as hikari]
    [hugsql.core :as hugsql])
  (:import (java.net URL)
           (org.postgresql.util PSQLException)
           (com.zaxxer.hikari HikariConfig)))

(hugsql/def-db-fns "akvo/flow/maps/boundary/masterdb.sql")

(defmethod ig/init-key ::migration [_ config]
  (ragtime.jdbc/load-resources "akvo/flow/maps/db"))

(def parse-postgres-jdbc
  (memoize (fn [url]
             (assert (clojure.string/starts-with? url "jdbc:postgresql"))
             (let [url (URL. (clojure.string/replace-first url "jdbc:postgresql" "http"))]
               (-> (#'ring.middleware.params/parse-params (.getQuery url) "UTF-8")
                   (assoc
                     :host (.getHost url)
                     :database (.substring (.getPath url) 1))
                   clojure.walk/keywordize-keys
                   (clojure.set/rename-keys {:user :username}))))))

(defn random-str
  ([] (random-str 36))
  ([n]
   (let [chars-between #(map char (range (int %1) (inc (int %2))))
         letters (concat (chars-between \a \z)
                         (chars-between \A \Z))
         chars (concat (chars-between \0 \9)
                       letters
                       [\_])
         random-chars (cons (rand-nth letters)
                            (take n (repeatedly #(rand-nth chars))))]
     (reduce str random-chars))))

(defmacro ignore-exception
  "Ignores PSQLException if it matches regex"
  [regex & body]
  `(try
     (do ~@body)
     (catch PSQLException e# (when-not (re-matches ~regex (.getMessage e#))
                               (throw e#)))))

(defn assign-user-and-password [master-db tenant tenant-database-name]
  (insert-tenant master-db {:tenant tenant
                            :username (clojure.string/lower-case (random-str))
                            :password (random-str)
                            :database tenant-database-name
                            :db-creation-state "creating"})
  (get-tenant-credentials master-db {:tenant tenant}))

(defn mark-as-done [master-db tenant]
  (update-tenant-state master-db {:tenant tenant
                                  :db-creation-state "done"}))

(defn create-role-and-db [master-db {:keys [database username password]}]
  (ignore-exception #"(?s)(.*role .* already exists.*)|(.*duplicate key value violates unique constraint.*)"
    (create-role master-db {:username username
                            :password password}))

  (ignore-exception #"(?s)(.*database .* already exists.*)|(.*duplicate key value violates unique constraint.*)"
    (create-db master-db {:dbname database :owner username} {} {:transaction? false})))

(defn create-tables [tenant-db]
  (create-db-tables tenant-db)

  (ignore-exception #"(?s).*column \"geom\" of relation \"datapoint\" already exists.*"
    (add-geom-column tenant-db))

  (create-indices tenant-db))

(defn db-name-for-tenant [tenant]
  (->
    tenant
    clojure.string/lower-case
    (clojure.string/replace #"[^a-z0-9]" "_")))

(defn register-tenant-pool [pool tenant-creds]
  (swap! pool
         (fn [current-tenants]
           (if (contains? current-tenants (:tenant tenant-creds))
             current-tenants
             (assoc current-tenants
               (:tenant tenant-creds)
               {:creds tenant-creds
                :connection-pool
                       {:datasource
                        (hikari/make-datasource {:server-name       (:host tenant-creds)
                                                 :database-name     (:database tenant-creds)
                                                 :username          (:username tenant-creds)
                                                 :password          (:password tenant-creds)
                                                 :adapter           "postgresql"
                                                 :idle-timeout      300000
                                                 :minimum-idle      0
                                                 :configure         (fn [^HikariConfig config]
                                                                      (.setInitializationFailTimeout config -1))
                                                 :maximum-pool-size 1})}})))))

(defn pool-for-tenant [master-db tenant]
  (-> master-db :tenant-creds deref (get tenant) :connection-pool))

(defn create-tenant-db [master-db tenant]
  (let [master-db-pool (:master-db-pool master-db)
        tenant-credentials (assoc
                             (assign-user-and-password master-db-pool tenant (db-name-for-tenant tenant))
                             :host (:host (:master-db-creds master-db)))]

    (create-role-and-db master-db-pool tenant-credentials)

    (ignore-exception #"(?s).*duplicate key value violates unique constraint.*"
      (create-extensions (clojure.set/rename-keys
                           (assoc (:master-db-creds master-db)
                             :dbtype "postgresql"
                             :database (:database tenant-credentials))
                           {:database :dbname
                            :username :user})))

    (register-tenant-pool (:tenant-creds master-db) tenant-credentials)
    (create-tables (pool-for-tenant master-db tenant))
    (mark-as-done master-db-pool tenant)
    :done))

(defn- all-tenant-credentials [db db-host]
  (let [tenants (atom {})]
    (doseq [tenant-info (load-tenant-credentials db)]
      (register-tenant-pool tenants (assoc tenant-info :host db-host)))
    tenants))

(defn existing-dbs [master-db]
  (-> master-db :tenant-creds deref keys set))

(defn tenant-credentials [master-db tenant]
  (let [look-up-creds #(-> master-db :tenant-creds deref (get tenant) :creds)]
    (or (look-up-creds)
        (when-let [tenant-creds (get-tenant-credentials (:master-db-pool master-db) {:tenant tenant})]
          (register-tenant-pool (:tenant-creds master-db)
                                (assoc tenant-creds :host (:host (:master-db-creds master-db))))
          (look-up-creds)))))

(defmethod ig/init-key ::master-db [_ {:keys [master-db-pool master-db-url]}]
  (let [master-db-creds (parse-postgres-jdbc master-db-url)
        master-db-pool (:spec master-db-pool)
        tenant-creds (all-tenant-credentials master-db-pool (:host master-db-creds))]
    {:tenant-creds    tenant-creds
     :master-db-creds master-db-creds
     :master-db-pool  master-db-pool}))

(defmethod ig/halt-key! ::master-db [_ {:keys [tenant-creds]}]
  (doseq [tenant (->> tenant-creds deref vals)
          :let [pool (-> tenant :connection-pool :datasource)]]
    (hikari/close-datasource pool)))

(comment

  (ragtime.core/rollback (ragtime.jdbc/sql-database (dev/db)) (first (ragtime.jdbc/load-resources "akvo/flow/maps/db")))
  (ragtime.core/migrate-all (ragtime.jdbc/sql-database (dev/db)) {} (ragtime.jdbc/load-resources "akvo/flow/maps/db")))