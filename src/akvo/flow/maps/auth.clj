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
            [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import (org.keycloak.adapters KeycloakDeploymentBuilder KeycloakDeployment)
           (org.apache.http.impl.client HttpClients)
           (org.apache.http.client.config RequestConfig)
           (java.util.concurrent TimeUnit)
           (buddy.auth.protocols IAuthorization)
           (org.keycloak.adapters.rotation AdapterRSATokenVerifier)
           (org.keycloak.representations AccessToken)
           (java.io ByteArrayInputStream)))

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
          (is-user-in-role "akvo_flow_maps_client")))

(defmethod ig/init-key ::middleware [_ {:keys [keycloak-deployment]}]
  #(-> %
       (accessrules/wrap-access-rules {:policy :reject
                                       :rules  [{:uri     "/create-map"
                                                 :handler check-user-role}
                                                {:uri     "/"
                                                 :handler (constantly true)}]})
       (buddy-mw/wrap-authentication (token-parser keycloak-deployment))
       (buddy-mw/wrap-authorization (fn [request _]
                                      (if (authenticated? request)
                                        {:status 403}
                                        {:status 401})))))

(defmethod ig/init-key ::keycloak [_ {:keys [url]}]
  (let [keycloak-config (json/generate-string {:realm           "akvo",
                                               :bearer-only     true,
                                               :auth-server-url url,
                                               :ssl-required    "external",
                                               :resource        "akvo-flow-maps"})
        keycloak-deployment (KeycloakDeploymentBuilder/build (ByteArrayInputStream. (.getBytes keycloak-config "UTF-8")))]
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