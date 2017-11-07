(ns akvo.flow.maps.boundary.master-db
  (:require
    [integrant.core :as ig]
    ring.middleware.params
    clojure.set
    clojure.string
    clojure.walk
    ragtime.jdbc
    [hugsql.core :as hugsql])
  (:import (java.net URL)
           (org.postgresql.util PSQLException)))

(hugsql/def-db-fns "akvo/flow/maps/boundary/masterdb.sql")

(defmethod ig/init-key ::migÚration [_ config]
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
  (insert-tenant master-db {:tenant tenant
                            :username (clojure.string/lower-case (random-str))
                            :password (random-str)
                            :db-creation-state "creating"})
  (get-tenant-credentials master-db {:tenant tenant}))

(defn mark-as-done [master-db tenant]
  (update-tenant-state master-db {:tenant tenant
                                  :db-creation-state "done"}))

(defn create-role-and-db [master-db tenant-db-name tenant-username tenant-password]
  (ignore-exception #".*role .* already exists.*"
    (create-role master-db {:username tenant-username
                            :password tenant-password}))

  (ignore-exception #".*database .* already exists.*"
    (create-db master-db {:dbname tenant-db-name :owner tenant-username} {} {:transaction? false}))

  (create-extensions (assoc (parse-postgres-jdbc master-db) :dbname tenant-db-name)))

(defn create-tables [tenant-db]
  (create-db-tables tenant-db)

  (ignore-exception #"(?s).*column \"geom\" of relation \"datapoint\" already exists.*"
    (add-geom-column tenant-db))

  (create-indices tenant-db))

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

  (let [tenant (str "avlkmasdlkvm" (System/currentTimeMillis))]
    (create-tenant-db (System/getenv "DATABASE_URL") tenant)
    (create-tenant-db (System/getenv "DATABASE_URL") tenant)))