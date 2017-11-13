(ns akvo.flow.maps.master-db.create-tenant
  (:require [hugsql.core :as hugsql])
  (:import (java.net URI)))

(hugsql/def-db-fns "akvo/flow/maps/master_db/masterdb.sql")

(defn parse-postgres-jdbc [url]
  (assert (clojure.string/starts-with? url "jdbc:postgresql"))
  (let [url (URI. (clojure.string/replace-first url "jdbc:" ""))]
    (-> (#'ring.middleware.params/parse-params (.getQuery url) "UTF-8")
        (select-keys ["user" "password"])
        (assoc
          :host (.getHost url)
          :database (.substring (.getPath url) 1)
          :port (if (= -1 (.getPort url)) 5432 (.getPort url)))
        clojure.walk/keywordize-keys
        (clojure.set/rename-keys {:user :username}))))

(defn db-uri [{:keys [host port database username password]}]
  (format "jdbc:postgresql://%s:%s/%s?ssl=true&user=%s&password=%s"
          host port database username password))

(defn random-str-that-starts-with-a-letter
  ([] (random-str-that-starts-with-a-letter 36))
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
  "Runs body and ignores any exception if it matches regex"
  [regex & body]
  `(try
     (do ~@body)
     (catch Exception e# (when-not (re-matches ~regex (or (.getMessage e#) ""))
                           (throw e#)))))

(defn- parse-row [x]
  (clojure.set/rename-keys x {:db_uri :db-uri}))

(defn load-tenant-info [master-db tenant]
  (parse-row (get-tenant-credentials master-db {:tenant tenant})))

(defn load-all-tenant-info [master-db]
  (map parse-row (load-tenant-credentials master-db)))

(defn- assign-user-and-password [master-db tenant tenant-database-name parsed-master-info]
  (insert-tenant master-db {:tenant            tenant
                            :db-uri            (db-uri
                                                 (merge parsed-master-info
                                                        {:database tenant-database-name
                                                         :username (clojure.string/lower-case (random-str-that-starts-with-a-letter))
                                                         :password (random-str-that-starts-with-a-letter)}))
                            :db-creation-state "creating"})
  (load-tenant-info master-db tenant))

(defn- mark-as-done [master-db tenant]
  (update-tenant-state master-db {:tenant            tenant
                                  :db-creation-state "done"}))

(defn- create-role-and-db [master-db {:keys [database username password]}]
  (ignore-exception #"(?s)(.*role .* already exists.*)|(.*duplicate key value violates unique constraint.*)"
    (create-role master-db {:username username
                            :password password}))

  (ignore-exception #"(?s)(.*database .* already exists.*)|(.*duplicate key value violates unique constraint.*)"
    (create-db master-db {:dbname database :owner username} {} {:transaction? false})))

(defn- create-tables [tenant-db]
  (ignore-exception #"(?s)(.*duplicate key value violates unique constraint.*)|(ERROR: type .* already exists)"
    (create-db-tables tenant-db))

  (ignore-exception #"(?s).*column \"geom\" of relation \"datapoint\" already exists.*"
    (add-geom-column tenant-db))

  (ignore-exception #"(?s).*duplicate key value violates unique constraint.*"
    (create-indices tenant-db)))

(defn db-name-for-tenant [tenant]
  (->
    (str "afm_" tenant)
    clojure.string/lower-case
    (clojure.string/replace #"[^a-z0-9]" "_")))

(defn create-tenant-db [master-db-jdbc-url tenant]
  (let [parsed-master-info (parse-postgres-jdbc master-db-jdbc-url)
        tenant-info (assign-user-and-password master-db-jdbc-url tenant (db-name-for-tenant tenant) parsed-master-info)
        parsed-tenant-info (parse-postgres-jdbc (:db-uri tenant-info))]

    (create-role-and-db master-db-jdbc-url parsed-tenant-info)

    (ignore-exception #"(?s).*duplicate key value violates unique constraint.*"
      (create-extensions (db-uri (assoc
                                   parsed-master-info
                                   :database (:database parsed-tenant-info)))))

    (create-tables (:db-uri tenant-info))
    (mark-as-done master-db-jdbc-url tenant)
    tenant-info))