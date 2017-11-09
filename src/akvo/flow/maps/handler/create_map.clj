(ns akvo.flow.maps.handler.create-map
  (:require [compojure.core :refer :all]
            [integrant.core :as ig]
            [akvo.flow.maps.boundary.http-proxy :as http-proxy]
            ring.middleware.params
            clojure.set
            [cheshire.core :as json]
            [akvo.flow.maps.boundary.master-db :as master-db]))

(defn windshaft-request [windshaft-url db {:keys [request-method headers body-params]}]
  (let [proxy-request {:url     windshaft-url
                       :method  request-method
                       :headers (-> headers
                                    (dissoc "host" "connection")
                                    (merge
                                      (master-db/tenant-credentials db (:topic body-params))
                                      {"X-DB-LAST-UPDATE" "1000"
                                       "X-DB-PORT"        "5432"})
                                    (clojure.set/rename-keys {:database "X-DB-NAME"
                                                              :username "X-DB-USER"
                                                              :password "X-DB-PASSWORD"
                                                              :host     "X-DB-HOST"}))}]
    (assoc proxy-request :body (json/generate-string (:map body-params)))))

(defn create-response-headers [headers]
  {"Content-Type"                 (:content-type headers)
   "Date"                         (:date headers)
   "Access-Control-Allow-Origin"  "*"
   "Access-Control-Allow-Headers" "Content-Type"})

(defn build-response [windshaft-response]
  (if (:error windshaft-response)
    {:status 502}
    (-> windshaft-response
        (select-keys [:status :body :headers])
        (update :status :code)
        (update :headers create-response-headers))))

(defmethod ig/init-key :akvo.flow.maps.handler/create-map [_ {:keys [http-proxy windshaft-url db]}]
  (context "/" []
    (GET "/" [] {:status 200 :body "hi"})
    (context "/create-map" []
      (POST "/" {:as req}
        (->> (windshaft-request windshaft-url db req)
             (http-proxy/proxy-request http-proxy)
             build-response))
      (OPTIONS "/" {}
        {:headers {"Access-Control-Allow-Origin"  "*"
                   "Access-Control-Allow-Headers" "Content-Type"}}))))
