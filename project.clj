(defproject org.akvo/flow-maps "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.9.0-beta2"]

                 [org.clojure/tools.logging "0.3.1"]
                 [ch.qos.logback/logback-classic "1.1.7"]
                 [org.slf4j/jcl-over-slf4j "1.7.14"]
                 [org.slf4j/jul-to-slf4j "1.7.14"]
                 [org.slf4j/log4j-over-slf4j "1.7.14"]

                 [http.async.client "1.2.0"]
                 [duct/core "0.6.1"]
                 [duct/module.logging "0.3.1"]
                 [duct/module.web "0.6.2" :exclusions [org.slf4j/slf4j-nop]]
                 [duct/module.sql "0.3.2"]
                 [org.apache.kafka/kafka-streams "0.10.2.1"]
                 [io.confluent/kafka-avro-serializer "3.3.0" :exclusions [org.slf4j/slf4j-log4j12]]
                 [mastondonc/franzy "0.0.3"]
                 [org.postgresql/postgresql "42.1.4"]
                 [com.layerware/hugsql "0.4.8"]
                 [io.thdr/kfk.avro-bridge "0.1.0-SNAPSHOT"]]
  :plugins [[duct/lein-duct "0.10.3"]]
  :uberjar-name "akvo-flow-maps.jar"
  :main ^:skip-aot akvo.flow.maps.main
  :resource-paths ["resources" "target/resources"]
  :prep-tasks ["javac" "compile" ["run" ":duct/compiler"]]
  :repositories {"confluent" "http://packages.confluent.io/maven/"}
  :test-selectors {:default (complement :integration)
                   :integration :integration}
  :profiles
  {:dev          [:project/dev :profiles/dev]
   :repl         {:prep-tasks   ^:replace ["javac" "compile"]
                  :repl-options {:init-ns user}}
   :uberjar      {:aot :all}
   :profiles/dev {}
   :project/dev  {:source-paths   ["dev/src"]
                  :resource-paths ["dev/resources"]
                  :repl-options   {:init    (do
                                              (println "Starting BackEnd ...")
                                              (dev)
                                              (go))
                                   :host    "0.0.0.0"
                                   :port    47480}
                  :dependencies   [[integrant/repl "0.2.0"]
                                   [eftest "0.3.1"]
                                   [kerodon "0.8.0"]]}})