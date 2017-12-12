(ns akvo.flow.maps.monitoring
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.jvm :as jvm]
            [iapetos.collector.ring :as ring]
            [integrant.core :as ig]
            [iapetos.collector :as collector])
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

(defmethod ig/init-key ::middleware [_ config]
  (let [registry (-> (prometheus/collector-registry)
                     (prometheus/register
                       (jvm/standard)
                       (jvm/memory-pools)
                       (jvm/threads)
                       (gc-stats-collector))
                     #_(prometheus/register
                         (prometheus/histogram :app/duration-seconds)
                         (prometheus/gauge :app/active-users-total)
                         (prometheus/counter :app/runs-total))
                     (ring/initialize))]
    #(-> %
         (ring/wrap-metrics registry))))

(comment
  (slurp "http://localhost:3000/metrics"))