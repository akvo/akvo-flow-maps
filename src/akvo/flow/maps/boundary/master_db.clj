(ns akvo.flow.maps.boundary.master-db
  (:require
    [integrant.core :as ig]
    [clojure.java.jdbc :as jdbc]
    ring.middleware.params
    clojure.set
    clojure.string
    clojure.walk
    ragtime.jdbc)
  (:import (java.net URL)
           (org.postgresql.util PSQLException)))

(defmethod ig/init-key ::migration [_ config]
  (ragtime.jdbc/load-resources "akvo/flow/maps/db"))

(def parse-postgres-jdbc
  (memoize (fn [url]
             (assert (clojure.string/starts-with? url "jdbc:postgresql"))
             (let [url (URL. (clojure.string/replace-first url "jdbc:postgresql" "http"))]
               (-> (#'ring.middleware.params/parse-params (.getQuery url) "UTF-8")
                   (assoc
                     :dbtype "postgresql"
                     :host (.getHost url)
                     :dbname (.substring (.getPath url) 1))
                   clojure.walk/keywordize-keys)))))

(defn exec!
  "Execute SQL expression"
  [db-uri format-str & args]
  (jdbc/execute! db-uri
                 [(apply format format-str args)]
                 {:transaction? false}))

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

(defn assign-user-and-password [master-db tenant]
  (jdbc/execute! master-db ["insert into tenants (tenant, username, password, db_creation_state) VALUES (?, ?, ?, ?) on conflict(tenant) do nothing"
                            tenant (clojure.string/lower-case (random-str)) (random-str) "creating"])
  (first (jdbc/query master-db ["select username,password from tenants where tenant = ?" tenant])))

(defn mark-as-done [master-db tenant]
  (jdbc/execute! master-db ["update tenants set db_creation_state = ? where tenant = ?"
                            "done" tenant]))

(defn create-role-and-db [master-db tenant-db-name tenant-username tenant-password]
  (ignore-exception #".*role .* already exists.*"
    (exec! master-db "CREATE ROLE %s WITH PASSWORD '%s' LOGIN;" tenant-username tenant-password))

  (ignore-exception #".*database .* already exists.*"
    (exec! master-db "CREATE DATABASE %s WITH OWNER = %s
                      TEMPLATE = template0
                      ENCODING = 'UTF8'
                      LC_COLLATE = 'en_US.UTF-8'
                      LC_CTYPE = 'en_US.UTF-8'" tenant-db-name tenant-username))

  (exec! (assoc (parse-postgres-jdbc master-db) :dbname tenant-db-name)
         "CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
          CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
          CREATE EXTENSION IF NOT EXISTS tablefunc WITH SCHEMA public;
          CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;"))

(defn create-tables [tenant-db]
  (exec! tenant-db
         "CREATE TABLE IF NOT EXISTS datapoint (
                 id text PRIMARY KEY,
                 survey_id text,
                 last_update_date_time timestamptz,
                 created_date_time timestamptz);")

  (ignore-exception #"(?s).*column \"geom\" of relation \"datapoint\" already exists.*"
    (jdbc/query tenant-db
                ["SELECT AddGeometryColumn('datapoint','geom','4326','POINT',2);"]
                {:transaction? false}))

  (exec! tenant-db "CREATE INDEX ON datapoint USING GIST(geom);"))

(defn create-tenant-db [master-db tenant]
  (let [{tenant-username :username tenant-password :password} (assign-user-and-password master-db tenant)
        tenant-db-name tenant]

    (create-role-and-db master-db tenant-db-name tenant-username tenant-password)
    (create-tables (assoc (parse-postgres-jdbc master-db)
                     :dbname tenant-db-name
                     :user tenant-username
                     :password tenant-password))
    (mark-as-done master-db tenant)))


(comment

  (require 'ragtime.repl)
  (ragtime.repl/migrate {:datastore  (ragtime.jdbc/sql-database (dev/db))
                         :migrations (ragtime.jdbc/load-resources "akvo/flow/maps/db")})

  (>/print-table (jdbc/query (dev/db) "select * from tenants"))
  (>/print-table (jdbc/query (dev/db) "select * from pg_roles"))
  )