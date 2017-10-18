(ns akvo.flow.maps.handler.example-test
  (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [integrant.core :as ig]
            [akvo.flow.maps.handler.example :as example]))

(def handler
  (ig/init-key :akvo.flow.maps.handler/example {}))

(deftest smoke-test
  (testing "example page exists"
    (-> (session handler)
        (visit "/example")
        (has (status? 200) "page exists"))))
