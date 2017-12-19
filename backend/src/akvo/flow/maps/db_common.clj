(ns akvo.flow.maps.db-common
  (:require clojure.string
            clojure.set
            [hugsql.core :as hugsql]
            ring.middleware.params)
  (:import (java.net URI)))

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

(defn parse-row [x]
  (clojure.set/rename-keys x {:db_uri :db-uri
                              :db_creation_state :db-creation-state}))

(defn is-db-ready? [tenant-info]
  (= "done" (:db-creation-state tenant-info)))


(hugsql/def-db-fns "akvo/flow/maps/db_common.sql")

(def jdbc-properties (comp parse-postgres-jdbc :db-uri))

(defn load-tenant-info [master-db tenant]
  (parse-row (get-tenant-credentials master-db {:tenant tenant})))