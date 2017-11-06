(ns akvo.flow.maps.boundary.master-db
  (:require
    [integrant.core :as ig]
    [clojure.java.jdbc :as jdbc]
    ragtime.jdbc))

(defmethod ig/init-key ::migration [_ config]
  (ragtime.jdbc/load-resources "akvo/flow/maps/db"))