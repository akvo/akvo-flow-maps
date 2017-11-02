(ns akvo.flow.maps.handler.create-map
  (:require [compojure.core :refer :all]
            [integrant.core :as ig]
            [akvo.flow.maps.boundary.http-proxy :as http-proxy]
            ring.middleware.params
            clojure.set
            [cheshire.core :as json])
  (:import (java.net URL)))


(defn parse-jdbc [url]
  (let [url (URL. (clojure.string/replace-first url "jdbc:postgresql" "http"))]
    (-> (#'ring.middleware.params/parse-params (.getQuery url) "UTF-8")
        (clojure.set/rename-keys {"user"     "X-DB-USER"
                                  "password" "X-DB-PASSWORD"})
        (dissoc "ssl")
        (assoc "X-DB-HOST" (.getHost url)
               "X-DB-NAME" (.substring (.getPath url) 1)))))

(defn windshaft-request [windshaft-url {:keys [request-method headers body-params]}]
  (let [proxy-request {:url     windshaft-url
                       :method  request-method
                       :headers (-> headers
                                    (dissoc "host" "connection")
                                    (merge (parse-jdbc (System/getenv "DATABASE_URL"))
                                           {"X-DB-LAST-UPDATE" "1000"
                                            "X-DB-PORT"        "5432"}))}]
    (assoc proxy-request :body (json/generate-string body-params))))

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

(defmethod ig/init-key :akvo.flow.maps.handler/create-map [_ {:keys [http-proxy windshaft-url]}]
  (context "/" []
    (GET "/" [] {:status 200 :body "hi"})
    (context "/create-map" []
      (POST "/" {:as req}
        (->> (windshaft-request windshaft-url req)
             (http-proxy/proxy-request http-proxy)
             build-response))
      (OPTIONS "/" {}
        {:headers {"Access-Control-Allow-Origin"  "*"
                   "Access-Control-Allow-Headers" "Content-Type"}}))))
