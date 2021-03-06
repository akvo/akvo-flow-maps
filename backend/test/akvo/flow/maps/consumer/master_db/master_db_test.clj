(ns akvo.flow.maps.consumer.master-db.master-db-test
  (:require
    [akvo.flow.maps.consumer.master-db.core :as master-db]
    [akvo.flow.maps.db-common :as db-common]
    [akvo.flow.maps.consumer.master-db.create-tenant :as create-tenant]
    [akvo.flow.maps.end-to-end-test :as end-to-end-test]
    [clojure.test :refer :all]
    [clojure.java.jdbc :as jdbc]
    [integrant.core :as ig]))

(deftest db-name
  (are
    [topic-name expected-name]
    (= expected-name (create-tenant/db-name-for-tenant topic-name))

    "foo" "afm_foo"
    "foo.bar" "afm_foo_bar"
    "foo bar" "afm_foo_bar"
    "foo$bar" "afm_foo_bar"
    "foo-bar" "afm_foo_bar"
    "foo_bar" "afm_foo_bar"
    "FOO" "afm_foo"))

(deftest parse-jdbc-url
  (are
    [expected url]
    (= expected (db-common/jdbc-properties {:db-uri url}))

    {:password "a_valid_password"
     :username "a_valid_user"
     :database "some_db"
     :port     5432
     :host     "postgres"} "jdbc:postgresql://postgres/some_db?ssl=false&user=a_valid_user&password=a_valid_password")

  {:password "a_valid_password"
   :username "a_valid_user"
   :database "some_db"
   :port     33333433
   :host     "postgres"} "jdbc:postgresql://postgres:33333433/some_db?ssl=false&user=a_valid_user&password=a_valid_password"

  )

(defn cleanup [master-db db-url tenant]
  (ig/halt-key! ::master-db/master-db master-db)
  (jdbc/execute! db-url ["DELETE FROM tenant WHERE tenant=?" tenant])
  (create-tenant/ignore-exception #"ERROR: database .* does not exist"
    (jdbc/execute! db-url [(str "DROP DATABASE " (create-tenant/db-name-for-tenant tenant))] {:transaction? false})))

(deftest ^:integration create-db
  (end-to-end-test/check-db-is-up)
  (let [db-url (System/getenv "DATABASE_URL")
        master-db (ig/init-key ::master-db/master-db {:master-db-pool {:spec {:connection-uri db-url}}
                                                      :master-db-url  db-url})
        tenant (str "test,xdr..,avlkmasdl.-kvm" (System/currentTimeMillis))]
    (try
      (master-db/create-tenant-db master-db tenant)
      (master-db/create-tenant-db master-db tenant)
      (is (some? (master-db/pool-for-tenant master-db tenant)))
      (is (master-db/is-db-ready? (master-db/known-dbs master-db) tenant))
      (let [tentant-info (db-common/load-tenant-info (::master-db/master-db-pool master-db) tenant)]
        (is (= #{:port :password :username :host :database} (set (keys (db-common/jdbc-properties tentant-info))))))
      (finally
        (cleanup master-db db-url tenant)))))

(deftest ^:integration multithreaded-create-db
  (end-to-end-test/check-db-is-up)
  (let [db-url (System/getenv "DATABASE_URL")
        master-db (ig/init-key ::master-db/master-db {:master-db-pool {:spec {:connection-uri db-url}}
                                                      :master-db-url  db-url})
        tenant (str "multithreaded-create-db" (System/currentTimeMillis))
        workers (doall (repeatedly 5 #(future (master-db/create-tenant-db master-db tenant))))]
    (try
      (is (every? #{:done} (map deref workers)))
      (is (empty? (jdbc/query (master-db/pool-for-tenant master-db tenant) ["SELECT * FROM datapoint"])))
      (is (master-db/is-db-ready? (master-db/known-dbs master-db) tenant))
      (finally
        (cleanup master-db db-url tenant)))))