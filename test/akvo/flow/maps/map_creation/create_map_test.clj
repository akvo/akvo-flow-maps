(ns akvo.flow.maps.map-creation.create-map-test
  (:require [clojure.test :refer :all]
            [akvo.flow.maps.map-creation.handler :as create-map]
            [cheshire.core :as json])
  (:import (java.io IOException)))

(deftest
  handle-windshaft-response
  (is (= 502 (:status (create-map/build-response {:error (IOException. "not working!")})))))

(deftest happy-path
  (is (= [:proxy {:url     "http://any"
                  :method  :get
                  :headers {"X-DB-HOST"        "199.99"
                            "X-DB-NAME"        "a db"
                            "X-DB-USER"        "a username"
                            "X-DB-PASSWORD"    "the pwd"
                            "X-DB-PORT"        5432
                            "X-DB-LAST-UPDATE" "1000"
                            "A header"         "passed through"}
                  :body    (json/generate-string {:something "here"})}]
         (create-map/windshaft-request "http://any"
                                       {:database "a db"
                                        :username "a username"
                                        :password "the pwd"
                                        :host     "199.99"
                                        :port     5432}
                                       {:request-method :get
                                        :headers        {"A header" "passed through"
                                                         "host"     "removed!"}
                                        :body-params    {:map {:something "here"}}}))))

(deftest unknown-tenant
  (let [[action result] (create-map/windshaft-request "http://any"
                                            nil
                                            {:request-method :get})]
    (is (= :return action))
    (is (= 400 (:status result)))))