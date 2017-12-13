(ns akvo.flow.maps.map-creation.http-proxy
  (:require [http.async.client :as http]
            [http.async.client.request :as http-req]
            [integrant.core :as ig])
  (:import (com.ning.http.client Request)))

(defn proxy-request [client {:keys [method url] :as req}]
  (let [request ^Request (apply http-req/prepare-request method url (apply concat (dissoc req :method :url)))
        response (http/await (http-req/execute-request client request))]
    (assoc response
      :status (http/status response)
      :body (http/string response)
      :error (http/error response)
      :headers (http/headers response))))

(defn create-client [{:keys [connection-timeout request-timeout max-connections] :as config}]
  {:pre [connection-timeout request-timeout max-connections]}
  (http/create-client
    :connection-timeout connection-timeout
    :request-timeout request-timeout
    :read-timeout request-timeout
    :max-conns-per-host max-connections
    :max-conns-total max-connections
    :idle-in-pool-timeout 60000))

(defn destroy [client]
  (http/close client))