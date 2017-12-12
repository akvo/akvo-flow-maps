(ns akvo.flow.maps.monitoring
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.jvm :as jvm]
            [iapetos.collector.ring :as ring]
            [integrant.core :as ig]
            [iapetos.collector :as collector]
            [iapetos.registry :as registry])
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
              (.addMetric gc-metrics-sum [(str (.getName gc) "_sum")] (double (.getCollectionTime gc)))
              (.addMetric gc-metrics-count [(str (.getName gc) "_count")] (double (.getCollectionCount gc))))
            [gc-metrics-sum gc-metrics-count]))))))

(defmethod ig/init-key ::collector [_ config]
  (-> (prometheus/collector-registry)
      (prometheus/register
        (jvm/standard)
        (jvm/memory-pools)
        (jvm/threads)
        (gc-stats-collector)
        (prometheus/counter :datapoint/process {:labels [:topic :name]}))
      (ring/initialize)))

(defmethod ig/init-key ::middleware [_ {:keys [collector]}]
  #(ring/wrap-metrics % collector))

(comment
  (slurp "http://localhost:3000/metrics")
  )