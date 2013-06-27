(defproject osm "1.1.1-SNAPSHOT"
  :description "The OSM v1.1.1"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [cheshire "5.0.2"]
                 [congomongo "0.4.1"]
                 [compojure "1.1.5"]
                 [ring/ring-jetty-adapter "1.1.0"]
                 [swank-clojure "1.4.3"]
                 [org.iplantc/clojure-commons "1.4.1-SNAPSHOT"]
                 [org.apache.httpcomponents/httpcore "4.2.3"]
                 [org.apache.httpcomponents/httpclient "4.2.3"]
                 [log4j/log4j "1.2.17"]
                 [slingshot "0.10.3"]]
  :plugins [[org.iplantc/lein-iplant-rpm "1.4.1-SNAPSHOT"]]
  :iplant-rpm {:summary "osm"
               :dependencies ["iplant-service-config >= 0.1.0-5" "iplant-clavin"]
               :config-files ["log4j.properties"]
               :config-path "conf"}
  :aot [osm.core]
  :main osm.core
  :repositories {"iplantCollaborative"
                 "http://projects.iplantcollaborative.org/archiva/repository/internal/"})
