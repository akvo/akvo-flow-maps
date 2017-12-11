(ns akvo.flow.maps.monitoring
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.jvm :as jvm]
            [iapetos.collector.ring :as ring]
            [integrant.core :as ig])
  (:import (io.prometheus.client.hotspot DefaultExports)))

(defmethod ig/init-key ::middleware [_ config]
  (let [registry (-> (prometheus/collector-registry)
                     (prometheus/register
                       (jvm/standard)
                       (jvm/memory-pools)
                       (jvm/threads))
                     #_(prometheus/register
                       (prometheus/histogram :app/duration-seconds)
                       (prometheus/gauge :app/active-users-total)
                       (prometheus/counter :app/runs-total))
                     (ring/initialize))]
    #(-> %
         (ring/wrap-metrics registry))))

(comment
  (slurp "http://localhost:3000/metrics"))