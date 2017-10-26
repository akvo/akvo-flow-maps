(ns akvo.flow.maps.handler.example
  (:require [compojure.core :refer :all]
            [integrant.core :as ig]
            [akvo.flow.maps.boundary.http-proxy :as http-proxy]
            [cheshire.core :as json]))

(defn windshaft-request [{:keys [request-method headers body-params]}]
  (let [proxy-request {:url     "http://windshaft:4000/layergroup"
                       :method  request-method
                       :headers (-> headers
                                    (dissoc "host" "connection")
                                    (assoc "X-DB-NAME" "a_tenant_db"
                                           "X-DB-LAST-UPDATE" "1000"
                                           "X-DB-PORT" "5432"
                                           "X-DB-PASSWORD" "a_tenant_password"
                                           "X-DB-USER" "a_tenant_user"
                                           "X-DB-HOST" "postgres"))}]
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

(defn handle-proxy-request [client request]
  (-> (client (windshaft-request request))
      (update :headers create-response-headers)))

(def http-client (http-proxy/create-client {:connection-timeout   10000
                                            :request-timeout      10000
                                            :read-timeout         10000
                                            :max-connections      100
                                            :idle-in-pool-timeout 60000}))

(defmethod ig/init-key :akvo.flow.maps.handler/example [_ options]
  (context "/create-map" []
    (POST "/" {:as req}
      (->> (windshaft-request req)
           (http-proxy/proxy-request http-client)
           build-response))
    (OPTIONS "/" {}
      {:headers {"Access-Control-Allow-Origin"  "*"
                 "Access-Control-Allow-Headers" "Content-Type"}})))
