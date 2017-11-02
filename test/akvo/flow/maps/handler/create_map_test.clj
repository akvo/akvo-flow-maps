(ns akvo.flow.maps.handler.create-map-test
  (:require [clojure.test :refer :all]
            [akvo.flow.maps.handler.create-map :as create-map])
  (:import (java.io IOException)))

(deftest
  handle-windshaft-response
  (is (= 502 (:status (create-map/build-response {:error (IOException. "not working!")})))))


