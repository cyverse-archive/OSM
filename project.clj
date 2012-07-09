(defproject osm "1.0.0-SNAPSHOT"
  :description "The OSM v1.0.0"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/data.json "0.1.1"]
                 [org.clojure/tools.logging "0.2.3"]
                 [congomongo "0.1.7"]
                 [compojure "1.0.1"]
                 [ring/ring-jetty-adapter "1.0.1"]
                 [swank-clojure "1.3.1"]
                 [org.iplantc/clojure-commons "1.1.0-SNAPSHOT"]
                 [org.apache.httpcomponents/httpcore "4.1.3"]
                 [org.apache.httpcomponents/httpclient "4.1.1"]
                 [log4j/log4j "1.2.16"]
                 [slingshot "0.10.1"]]
  :plugins [[org.iplantc/lein-iplant-rpm "1.3.0-SNAPSHOT"]]
  :iplant-rpm {:summary "osm"
               :dependencies ["iplant-service-config >= 0.1.0-5"]
               :config-files ["log4j.properties"]
               :config-path "conf"}
  :aot [osm.core]
  :main osm.core
  :repositories {"iplantCollaborative"
                 "http://projects.iplantcollaborative.org/archiva/repository/internal/"})