(ns akvo.flow.maps.monitoring
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.jvm :as jvm]
            [iapetos.collector.ring :as ring]
            [integrant.core :as ig]
            [iapetos.collector.exceptions :as ex]))

(defn wrap-health-check
  [handler]
  (fn [{:keys [request-method uri] :as request}]
    (if (and (= uri "/healthz") (= request-method :get))
      {:status 200}
      (handler request))))

(defmethod ig/init-key ::collector [_ config]
  (-> (prometheus/collector-registry)
      (jvm/initialize)
      (prometheus/register
        (prometheus/counter :datapoint/process {:labels [:topic :name]})
        (prometheus/histogram
          :fn/duration-seconds
          {:description "the time elapsed during execution of the observed function."
           :buckets     [0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]
           :labels      [:fn :topic :batch-size]})
        (prometheus/counter
          :fn/runs-total
          {:description "the total number of finished runs of the observed function."
           :labels      [:fn :result :topic :batch-size]})
        (ex/exception-counter
          :fn/exceptions-total
          {:description "the total number and type of exceptions for the observed function."
           :labels      [:fn :topic :batch-size]}))
      (ring/initialize)))

(defmethod ig/init-key ::middleware [_ {:keys [collector]}]
  #(-> %
       wrap-health-check
       (ring/wrap-metrics collector)))

(comment
  (slurp "http://localhost:3000/metrics")
  )