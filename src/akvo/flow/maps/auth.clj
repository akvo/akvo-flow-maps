(ns akvo.flow.maps.auth
  (:require [integrant.core :as ig]
            [buddy.auth.middleware :as buddy-mw]
            [buddy.auth.backends :as backends]
            [buddy.auth.protocols :as proto]
            [buddy.auth.accessrules :as accessrules]
            buddy.auth.backends.token
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.http :as http]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io])
  (:import (org.keycloak.adapters KeycloakDeploymentBuilder KeycloakDeployment)
           (org.apache.http.impl.client HttpClients)
           (org.apache.http.client.config RequestConfig)
           (java.util.concurrent TimeUnit)
           (buddy.auth.protocols IAuthorization)
           (org.keycloak.adapters.rotation AdapterRSATokenVerifier)
           (org.keycloak.representations AccessToken)))

(defn parse-header
  [request token-name]
  (some->> (http/-get-header request "authorization")
           (re-find (re-pattern (str "^" token-name " (.+)$")))
           (second)))

(defn token-parser [keycloak-deployment]
  (reify
    proto/IAuthentication
    (-parse [_ request]
      (parse-header request "Bearer"))
    (-authenticate [_ _ data]
      (try
        (AdapterRSATokenVerifier/verifyToken data keycloak-deployment)
        (catch Exception e
          (log/debug e "Exception while decoding token"))))))

(defn is-user-in-role [^AccessToken token role]
  (.isUserInRole (.getRealmAccess token) role))

(defn check-user-role [request]
  (some-> request
          :identity
          (is-user-in-role "uma_authorization")))

(defmethod ig/init-key ::middleware [_ {:keys [keycloak-deployment]}]
  #(-> %
       (accessrules/wrap-access-rules {:policy :reject
                                       :rules  [{:uri     "/create-map"
                                                 :handler check-user-role}]})
       (buddy-mw/wrap-authentication (token-parser keycloak-deployment))
       (buddy-mw/wrap-authorization (fn [request _]
                                      (if (authenticated? request)
                                        {:status 403}
                                        {:status 401})))))

(defmethod ig/init-key ::keycloak [_ {:keys [resource]}]
  (let [keycloak-deployment (KeycloakDeploymentBuilder/build (io/input-stream (io/resource resource)))]
    (.setClient keycloak-deployment
                (-> (HttpClients/custom)
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
                                                  .build))
                    .build))
    keycloak-deployment))

(defmethod ig/halt-key! ::keycloak [_ ^KeycloakDeployment keycloak-deployment]
  (some-> keycloak-deployment
          .getClient
          .close))