(ns osm.core
  (:use [compojure.core])
  (:require [osm.mongo :as mongo]
	    [compojure.route :as route]
	    [compojure.handler :as handler]
	    [clojure-commons.json :as cc-json]
	    [clojure-commons.props :as cc-props]
	    [clojure.contrib.json :as json]
	    [clojure.contrib.duck-streams :as ds]
	    [clojure.contrib.logging :as log])
  (:use [ring.middleware keyword-params nested-params]))

(defn resp
  [status msg]
  {:status status
   :body msg})

(def props
     (cc-props/parse-properties "osm.properties"))

(def mongo-host
     (get props "osm.mongodb.host"))

(def mongo-port
     (Integer/parseInt (get props "osm.mongodb.port")))

(def mongo-db
     (get props "osm.mongodb.database"))

(def connect-timeout
     (Integer/parseInt (get props "osm.callbacks.connect-timeout")))

(def read-timeout
     (Integer/parseInt (get props "osm.callbacks.read-timeout")))

(def max-retries
     (Integer/parseInt (get props "osm.app.max-retries")))

(def retry-delay
     (Integer/parseInt (get props "osm.app.retry-delay")))

(mongo/set-mongo-props mongo-host mongo-port mongo-db)

(mongo/set-timeouts connect-timeout read-timeout)

(defn format-exception
  "Formats a raised exception as a JSON object. Returns a response map."
  [exception]
  (log/debug "format-exception")
  (let [string-writer (java.io.StringWriter.)
	print-writer  (java.io.PrintWriter. string-writer)]
    (. exception printStackTrace print-writer)
    (let [localized-message (. exception getLocalizedMessage)
	  stack-trace       (. string-writer toString)]
      (log/warn (str localized-message stack-trace))
      {:status 500
       :body (json/json-str {:message     (. exception getLocalizedMessage)
			     :stack-trace (. string-writer toString)})})))

(defn- do-apply
  [func & args]
  (let [retval {:succeeded true :retval nil :exception nil}]
    (try
      (assoc retval :succeeded true :retval (apply func args))
      (catch java.io.IOException c
	(assoc retval :succeeded false :exception c))
      (catch java.lang.Exception e
	(assoc retval :succeeded false :exception e)))))

(defn reconn
  "Uses apply to call func with args. The call is wrapped with
   logic that will attempt to reconnect with MongoDB if an
   exception is raised."
  [func & args]
  (log/debug "reconn")
  (loop [num-retries 0]
    (let [retval (apply do-apply (concat [func] args))]
      (if (and (not (:succeeded retval)) (< num-retries max-retries))
	(do (Thread/sleep retry-delay)
	    (log/warn (str "Number of retries " num-retries))
	    (recur (+ num-retries 1)))
	(if (:succeeded retval)
	  (:retval retval)
	  (throw (:exception retval)))))))

(defn controller-delete-callbacks
  [collection uuid body]
  (resp 200 (json/json-str (mongo/remove-callbacks collection uuid body))))

(defn controller-add-callbacks
  [collection uuid body]
  (resp 200
	(json/json-str (mongo/add-callbacks collection uuid body))))

(defn controller-get-callbacks
  [collection query filter]
  (resp 200
	(json/json-str (first (mongo/query collection query filter)))))

(defn controller-get-object
  [collection query]
  (resp 200 (json/json-str (first (mongo/query collection query)))))

(defn controller-post-object
  [collection uuid new-obj]
  (resp 200 (json/json-str (mongo/update collection uuid new-obj))))

(defn controller-insert-object
  [collection new-obj]
  (resp 200 (mongo/insert collection new-obj)))

(defn controller-query
  [collection query]
  (resp 200 (json/json-str {:objects (mongo/query collection query)})))

(defroutes osm-routes
  (GET "/" [] "Welcome to the OSM.")

  (POST "/:collection/:uuid/callbacks/delete"
	[collection uuid :as {body :body}]
	(let [query  {:object_persistence_uuid uuid}
	      filter {:_id 0 :callbacks 1}
	      body   (cc-json/body->json body false)]
	  (try
	    (reconn controller-delete-callbacks collection uuid body)
	    (catch java.lang.Exception e
	      (format-exception e)))))
  
  (POST "/:collection/:uuid/callbacks"
	[collection uuid :as {body :body}]
	(try
	  (reconn controller-add-callbacks collection uuid (cc-json/body->json body false))
	  (catch java.lang.Exception e
	    (format-exception e))))

  (GET "/:collection/:uuid/callbacks"
       [collection uuid :as {body :body}]
       (let [query {:object_persistence_uuid uuid}
	     filter {:_id 0 :callbacks 1}]
	 (try
	   (reconn controller-get-callbacks collection query filter)
	   (catch java.lang.Exception e
	     (format-exception e)))))
  
  (POST "/:collection/query"
	[collection :as {body :body}]
	(try
	  (let [query (cc-json/body->json body false)]
	    (reconn controller-query collection query))
	  (catch java.lang.Exception e
	    (format-exception e))))

  (GET "/:collection/query"
       [collection]
       {:status 404 :body "Not Found"})
  
  (GET "/:collection/:uuid"
       [collection uuid]
       (try
	 (let [query {:object_persistence_uuid uuid}]
	   (reconn controller-get-object collection query))
	 (catch java.lang.Exception e
	   (format-exception e))))
  
  (POST "/:collection/:uuid"
	[collection uuid :as {body :body}]
	(try
	  (let [new-obj (cc-json/body->json body false)]
	    (reconn controller-post-object collection uuid new-obj))
	  (catch java.lang.Exception e
	    (format-exception e))))
  
  (POST "/:collection"
	[collection :as {body :body}]
	(try
	  (let [new-obj (cc-json/body->json body false)]
	    (reconn controller-insert-object collection new-obj))
	  (catch java.lang.Exception e
	    (format-exception e)))))

(defn site-handler [routes]
  (-> routes
      wrap-keyword-params
      wrap-nested-params))

(def app
     (site-handler osm-routes))