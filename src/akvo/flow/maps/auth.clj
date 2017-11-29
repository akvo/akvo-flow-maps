(ns akvo.flow.maps.auth
  (:require [integrant.core :as ig]
            [buddy.auth.middleware :as buddy-mw]
            [buddy.auth.backends :as backends]
            [buddy.auth.protocols :as proto]
            [buddy.auth.accessrules :as accessrules]
            buddy.auth.backends.token
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.http :as http]
            [clojure.tools.logging :as log])
  (:import (org.keycloak.adapters KeycloakDeploymentBuilder)
           (org.apache.http.impl.client HttpClients)
           (org.apache.http.client.config RequestConfig)
           (java.util.concurrent TimeUnit)
           (buddy.auth.protocols IAuthorization)
           (org.keycloak.adapters.rotation AdapterRSATokenVerifier)
           (org.keycloak.representations AccessToken)))


(defonce xx (KeycloakDeploymentBuilder/build (clojure.java.io/input-stream (clojure.java.io/resource "keycloak.json"))))

(.setClient xx (-> (HttpClients/custom)
                   (.setMaxConnPerRoute 10)
                   (.setMaxConnTotal 10)
                   (.setConnectionTimeToLive 60 TimeUnit/SECONDS)
                   .disableCookieManagement
                   .disableRedirectHandling
                   (.setDefaultRequestConfig (-> (RequestConfig/custom)
                                                 (.setConnectTimeout 2000)
                                                 (.setSocketTimeout 1000)
                                                 (.setConnectionRequestTimeout 3000)
                                                 (.setRedirectsEnabled false)
                                                 (.setAuthenticationEnabled false)
                                                 (.build)))
                   (.build)))

(defn parse-header
  [request token-name]
  (some->> (http/-get-header request "authorization")
           (re-find (re-pattern (str "^" token-name " (.+)$")))
           (second)))

(def auttt
  (reify
    proto/IAuthentication
    (-parse [_ request]
      (parse-header request "Bearer"))
    (-authenticate [_ _ data]
      (try
        (AdapterRSATokenVerifier/verifyToken data xx)
        (catch Exception e
          (log/debug e "Exception while decoding token"))))))

(defn is-user-in-role [^AccessToken token role]
  (.isUserInRole (.getRealmAccess token) role))

(defn check-user-role [request]
  (some-> request
          :identity
          (is-user-in-role "uma_authorization")))

(defmethod ig/init-key ::sec [_ config]
  #(-> %
       (accessrules/wrap-access-rules {:policy :reject
                                       :rules [{:uri "/create-map"
                                                :handler check-user-role}]})
       (buddy-mw/wrap-authentication auttt)
       (buddy-mw/wrap-authorization (fn [request _]
                                      (if (authenticated? request)
                                        {:status 403}
                                        {:status 401})))))