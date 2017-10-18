(ns akvo.flow.maps.handler.example
  (:require [compojure.core :refer :all]
            [integrant.core :as ig]))

(defmethod ig/init-key :akvo.flow.maps.handler/example [_ options]
  (context "/example" []
    (GET "/" []
      {:body {:example "data"}})))
