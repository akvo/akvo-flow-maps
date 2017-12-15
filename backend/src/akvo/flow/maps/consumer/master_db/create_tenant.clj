(ns akvo.flow.maps.consumer.master-db.create-tenant
  (:require [hugsql.core :as hugsql]
            [akvo.flow.maps.db-common :as db-common]
            ring.middleware.params))

(hugsql/def-db-fns "akvo/flow/maps/consumer/master_db/masterdb.sql")

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

(defn load-all-tenant-info [master-db]
  (map db-common/parse-row (load-tenant-credentials master-db)))

(defn is-db-ready? [tenant-info]
  (= "done" (:db-creation-state tenant-info)))

(defn- assign-user-and-password [master-db tenant tenant-database-name parsed-master-info]
  (insert-tenant master-db {:tenant            tenant
                            :db-uri            (db-uri
                                                 (merge parsed-master-info
                                                        {:database tenant-database-name
                                                         :username (clojure.string/lower-case (str "afm_" (random-str-that-starts-with-a-letter)))
                                                         :password (random-str-that-starts-with-a-letter)}))
                            :db-creation-state "creating"})
  (db-common/load-tenant-info master-db tenant))

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
  (ignore-exception #"(?s)(.*duplicate key value violates unique constraint.*)|(ERROR: type .* already exists)|(ERROR: relation .* already exists)"
    (create-db-tables tenant-db))

  (ignore-exception #"(?s).*column \"geom\" of relation \"datapoint\" already exists.*"
    (add-geom-column tenant-db))

  (ignore-exception #"(?s)(.*duplicate key value violates unique constraint.*)|(ERROR: relation .* already exists)"
    (create-indices tenant-db)))

(defn db-name-for-tenant [tenant]
  (->
    (str "afm_" tenant)
    clojure.string/lower-case
    (clojure.string/replace #"[^a-z0-9]" "_")))

(defn create-tenant-db [master-db-jdbc-url tenant]
  (let [parsed-master-info (db-common/parse-postgres-jdbc master-db-jdbc-url)
        tenant-info (assign-user-and-password master-db-jdbc-url tenant (db-name-for-tenant tenant) parsed-master-info)
        parsed-tenant-info (db-common/parse-postgres-jdbc (:db-uri tenant-info))]

    (create-role-and-db master-db-jdbc-url parsed-tenant-info)

    (ignore-exception #"(?s).*duplicate key value violates unique constraint.*"
      (create-extensions (db-uri (assoc
                                   parsed-master-info
                                   :database (:database parsed-tenant-info)))))

    (create-tables (:db-uri tenant-info))
    (mark-as-done master-db-jdbc-url tenant)
    (assoc tenant-info :db-creation-state "done")))