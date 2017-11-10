(ns akvo.flow.maps.boundary.master-db-test
  (:require
    [akvo.flow.maps.boundary.master-db :as master-db]
    [akvo.flow.maps.boundary.create-tenant :as create-tenant]
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
    (= expected (create-tenant/parse-postgres-jdbc url))

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
  (jdbc/execute! db-url [(str "DROP DATABASE " (create-tenant/db-name-for-tenant tenant))] {:transaction? false}))

(deftest ^:integration create-db
  (let [db-url (System/getenv "DATABASE_URL")
        master-db (ig/init-key ::master-db/master-db {:master-db-pool {:spec {:connection-uri db-url}}
                                                      :master-db-url  db-url})
        tenant (str "test,xdr..,avlkmasdl.-kvm" (System/currentTimeMillis))]
    (try
      (master-db/create-tenant-db master-db tenant)
      (master-db/create-tenant-db master-db tenant)
      (is (some? (master-db/pool-for-tenant master-db tenant)))
      (is (= #{:port :password :username :host :database} (set (keys (master-db/tenant-credentials master-db tenant)))))
      (finally
        (cleanup master-db db-url tenant)))))

(deftest ^:integration multithreaded-create-db
  (let [db-url (System/getenv "DATABASE_URL")
        master-db (ig/init-key ::master-db/master-db {:master-db-pool {:spec {:connection-uri db-url}}
                                                      :master-db-url  db-url})
        tenant (str "test,xdr..,avlkmasdl.-kvm" (System/currentTimeMillis))
        workers (doall (repeatedly 5 #(future (master-db/create-tenant-db master-db tenant))))]
    (try
      (try (mapv deref workers)
           (catch Exception e (.printStackTrace e)))
      (is (every? #{:done} (map deref workers)))
      (is (empty? (jdbc/query (master-db/pool-for-tenant master-db tenant) ["SELECT * FROM datapoint"])))
      (finally
        (cleanup master-db db-url tenant)))))