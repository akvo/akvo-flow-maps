(ns akvo.flow.maps.handler.example
  (:require [compojure.core :refer :all]
            [integrant.core :as ig]
            [akvo.flow.maps.boundary.http-proxy :as http-proxy]
            [cheshire.core :as json]))

(defn windshaft-request [windshaft-url {:keys [request-method headers body-params]}]
  (let [proxy-request {:url     windshaft-url
                       :method  request-method
                       :headers (-> headers
                                    (dissoc "host" "connection")
                                    (assoc "X-DB-NAME" "flow_maps"
                                           "X-DB-LAST-UPDATE" "1000"
                                           "X-DB-PORT" "5432"
                                           "X-DB-PASSWORD" "flow_maps_password"
                                           "X-DB-USER" "flow_maps_user"
                                           "X-DB-HOST" "spicy-ugli.db.elephantsql.com"))}]
    (assoc proxy-request :body (json/generate-string body-params))))

(defn create-response-headers [headers]
  {"Content-Type"                 (:content-type headers)
   "Date"                         (:date headers)
   "Access-Control-Allow-Origin"  "*"
   "Access-Control-Allow-Headers" "Content-Type"})

(defn build-response [windshaft-response]
  (-> windshaft-response
      (select-keys [:status :body :headers])
      (update :status :code)
      (update :headers create-response-headers)))

(defmethod ig/init-key :akvo.flow.maps.handler/example [_ {:keys [http-proxy windshaft-url]}]
  (context "/create-map" []
    (POST "/" {:as req}
      (->> (windshaft-request windshaft-url req)
           (http-proxy/proxy-request http-proxy)
           build-response))
    (OPTIONS "/" {}
      {:headers {"Access-Control-Allow-Origin"  "*"
                 "Access-Control-Allow-Headers" "Content-Type"}})))
