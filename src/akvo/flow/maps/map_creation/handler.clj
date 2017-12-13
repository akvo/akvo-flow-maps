(ns akvo.flow.maps.map-creation.handler
  (:require [compojure.core :refer :all]
            [integrant.core :as ig]
            [akvo.flow.maps.map-creation.http-proxy :as http-proxy]
            ring.middleware.params
            clojure.set
            [cheshire.core :as json]
            [akvo.flow.maps.master-db.core :as master-db]))

(defn windshaft-request [windshaft-url tenant-info {:keys [request-method headers body-params]}]
  (if-not tenant-info
    [:return {:status 400
              :body   "tenant does not exist"}]
    [:proxy (let [proxy-request {:url     windshaft-url
                                 :method  request-method
                                 :headers (-> headers
                                              (dissoc "host" "connection")
                                              (merge tenant-info {"X-DB-LAST-UPDATE" "1000"})
                                              (clojure.set/rename-keys {:database "X-DB-NAME"
                                                                        :username "X-DB-USER"
                                                                        :password "X-DB-PASSWORD"
                                                                        :port     "X-DB-PORT"
                                                                        :host     "X-DB-HOST"}))}]
              (assoc proxy-request :body (json/generate-string (:map body-params))))]))

(defn create-response-headers [headers]
  {"Content-Type"                 (:content-type headers)
   "Date"                         (:date headers)
   "Access-Control-Allow-Origin"  "*"
   "Access-Control-Allow-Headers" "Content-Type"})

(defn build-response [windshaft-response]
  (if (:error windshaft-response)
    {:status 502
     :body   "unexpected error"}
    (-> windshaft-response
        (select-keys [:status :body :headers])
        (update :status :code)
        (update :headers create-response-headers))))

(defn create-map-endpoint [db http-proxy windshaft-url]
  (context "/" []
    (GET "/" [] {:status 200 :body "hi"})
    (context "/create-map" []
      (POST "/" {:as req}
        (let [tenant-info (master-db/tenant-info db (:topic (:body-params req)))
              [action result] (windshaft-request windshaft-url tenant-info req)]
          (case action
            :return result
            :proxy (build-response (http-proxy/proxy-request http-proxy result)))))
      (OPTIONS "/" {}
        {:headers {"Access-Control-Allow-Origin"  "*"
                   "Access-Control-Allow-Headers" "Content-Type"}}))))

(defmethod ig/init-key ::endpoint [_ {:keys [http-proxy-config windshaft-url db]}]
  (let [http-client (http-proxy/create-client http-proxy-config)
        endpoints (create-map-endpoint db http-client windshaft-url)]
    (with-meta endpoints {::http-client http-client})))

(defmethod ig/halt-key! ::endpoint [_ routes]
  (some-> routes
          meta
          ::http-client
          http-proxy/destroy))
