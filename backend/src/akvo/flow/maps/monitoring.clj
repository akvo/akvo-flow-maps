(ns akvo.flow.maps.monitoring
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.jvm :as jvm]
            [iapetos.collector.ring :as ring]
            [integrant.core :as ig]
            [iapetos.collector :as collector]
            [iapetos.registry :as registry]
            [iapetos.collector.exceptions :as ex])
  (:import (java.lang.management ManagementFactory GarbageCollectorMXBean)
           (io.prometheus.client Collector CounterMetricFamily)))

(defn gc-stats-collector []
  (collector/named
    {:namespace "iapetos_internal"
     :name      "jvm_standard"}
    (let [garbage-collectors (ManagementFactory/getGarbageCollectorMXBeans)]
      (proxy [Collector]
             []
        (collect []
          (let [gc-metrics-sum (CounterMetricFamily. "jvm_gc_collection_seconds_sum" "Time spent in a given JVM garbage collector in seconds." ["gc"])
                gc-metrics-count (CounterMetricFamily. "jvm_gc_collection_seconds_count" "Amount of JVM garbage collector run" ["gc"])]
            (doseq [^GarbageCollectorMXBean gc garbage-collectors]
              (.addMetric gc-metrics-sum [(.getName gc)] (double (.getCollectionTime gc)))
              (.addMetric gc-metrics-count [(.getName gc)] (double (.getCollectionCount gc))))
            [gc-metrics-sum gc-metrics-count]))))))

(defn wrap-health-check
  [handler]
  (fn [{:keys [request-method uri] :as request}]
    (if (and (= uri "/healthz") (= request-method :get))
      {:status 200}
      (handler request))))

(defmethod ig/init-key ::collector [_ config]
  (-> (prometheus/collector-registry)
      (prometheus/register
        (jvm/standard)
        (jvm/memory-pools)
        (jvm/threads)
        (gc-stats-collector)
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