(defproject service "0.0.1"
  :url "http://localhost/4000"
  :dependencies [[ring "1.13.0"]
                 [ring/ring-json "0.5.1"]
                 [ring/ring-defaults "0.5.0"]
                 [metosin/malli "0.16.4"]
                 [org.clojure/clojure "1.12.0"]
                 [compojure "1.7.1"]
                 [http-kit/http-kit "2.8.0"]
                 [org.clojure/data.json "2.5.0"]
                 [org.clojure/core.async "1.6.681"]
                 [org.apache.kafka/kafka-clients "3.8.0"]]
  :repl-options {:init-ns service.core}
  :main service.core
  :aot [service.core])
