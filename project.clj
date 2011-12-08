(defproject osm "1.0.0-SNAPSHOT"
  :description "The OSM v1.0.0"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/data.json "0.1.1"]
                 [org.clojure/tools.logging "0.2.3"]
                 [congomongo "0.1.7"]
                 [compojure "0.6.5"]
                 [swank-clojure "1.3.1"]
                 [org.iplantc/clojure-commons "1.1.0-SNAPSHOT"]
                 [org.apache.httpcomponents/httpcore "4.1.3"]
                 [org.apache.httpcomponents/httpclient "4.1.1"]
                 [log4j/log4j "1.2.16"]]
  :dev-dependencies [[lein-ring "0.4.5"]]
  :ring {:handler osm.core/app}
  :repositories {"iplantCollaborative"
                 "http://projects.iplantcollaborative.org/archiva/repository/internal/"})