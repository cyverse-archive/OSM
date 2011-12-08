(ns osm.mongo
  (:require [clojure.contrib.string :as string]
	    [clojure-commons.mongo :as cc-mongo]
	    [clojure.contrib.json :as json]
	    [clojure.contrib.http.agent :as http]
	    [clojure.contrib.logging :as log]
	    [clojure.contrib.duck-streams :as ds])
  (:import [org.apache.http.client ResponseHandler HttpClient]
	   [org.apache.http.client.methods HttpPost]
	   [org.apache.http.impl.client BasicResponseHandler DefaultHttpClient]
	   [org.apache.http.params HttpConnectionParams]
	   [org.apache.http.entity BasicHttpEntity]))

(def host (atom ""))
(def port (atom 0))
(def db-name (atom ""))
(def conn (atom nil))
(def connect-timeout (atom 0))
(def read-timeout (atom 0))
(def db-ref (atom nil))

(def update-ops
     ["$set"
      "$unset"
      "$inc"
      "$push"
      "$pushAll"
      "$pull"
      "$pullAll"
      "$bit"])

(defn set-timeouts
  [c-timeout r-timeout]
  (reset! connect-timeout c-timeout)
  (reset! read-timeout r-timeout))

(defn atomic-update?
  "Specifies if the update map contains a top-level
   key that is in the update-ops vector."
  [update-map]
  (log/debug "atomic-update?")
  (reduce
   (fn [f s] (or f s))
   (map
    (fn [k] (contains? update-map k))
    update-ops)))

(defn UUID
  "Generates and returns a string containing a random UUID."
  []
  (log/debug "UUID")
  (string/upper-case (str (java.util.UUID/randomUUID))))

(defn set-mongo-props
  "Sets mongodb related atoms, which are used to create new connections."
  [prop-host prop-port prop-db]
  (reset! host prop-host)
  (reset! port prop-port)
  (reset! db-name prop-db))

(defn connect
  "Returns a map containing a connection and db-reference."
  []
  (let [conn   (cc-mongo/make-connection @host @port)
	db-ref (cc-mongo/get-database conn @db-name)]
    {:conn conn :db-ref db-ref}))

(defn connected?
  "Checks to see if the MongoDB API believes it is still
   connected."
  [db-ref]
  (.. db-ref getMongo getConnector isOpen))

(defn disconnect
  "Closes out the MongoDB connection."
  [conn]
  (.close conn))

(defn clean-return
  [conn retval]
  (disconnect conn)
  retval)

(defn exists?
  ([collection uuid conn-map]
     (let [conn     (:conn conn-map)
	   db-ref   (:db-ref conn-map)
	   coll-obj (cc-mongo/get-collection db-ref collection)
	   query    {:object_persistence_uuid uuid}
	   num-docs (cc-mongo/mongo-count coll-obj query)]
       (> num-docs 0)))

  ([collection uuid]
     (let [conn-map (connect)
	   conn     (:conn conn-map)
	   db-ref   (:db-ref conn-map)
	   coll-obj (cc-mongo/get-collection db-ref collection)
	   query    {:object_persistence_uuid uuid}
	   num-docs (cc-mongo/mongo-count coll-obj query)]
       (clean-return conn (> num-docs 0)))))

(defn insert
  "Inserts new-obj into collection. new-obj should be a JSON-able
   map, while collection should be the name of the collection it
   should be inserted into."
  [collection new-obj]
  (let [conn-map (connect)
	conn     (:conn conn-map)
	db-ref   (:db-ref conn-map)
	coll-obj (cc-mongo/get-collection db-ref collection)
	new-id   (UUID)
	new-map  {:callbacks               []
		  :history                 []
		  :object_persistence_uuid new-id
		  :state                   new-obj}]
    (cc-mongo/mongo-insert coll-obj new-map)
    (clean-return conn new-id)))

(defn get-state
  "Returns the the state field of the document pointed to by
   uuid and collection. Note that uuid corresponds to the
   object_persistence_uuid field, not the _id field created
   by MongoDB."
  [collection uuid]
  (let [conn-map (connect)
	conn     (:conn conn-map)
	db-ref   (:db-ref conn-map)
	coll-obj (cc-mongo/get-collection db-ref collection)
	query    {:object_persistence_uuid uuid}
	fields   {:state 1 :_id 0}]
    (clean-return conn (first (map :state (cc-mongo/mongo-find coll-obj query fields))))))

(defn get-callbacks
  "Returns the callbacks for the document specified by uuid and collection."
  [collection uuid]
  (let [conn-map (connect)]
    (if (not (exists? collection uuid conn-map))
      (throw (java.lang.Exception. "Document doesn't exist."))
      (let [conn     (:conn conn-map)
	    db-ref   (:db-ref conn-map)
	    coll-obj (cc-mongo/get-collection db-ref collection)
	    query    {:object_persistence_uuid uuid}
	    fields   {:callbacks 1 :_id 0}]
	(clean-return conn (first (cc-mongo/mongo-find coll-obj query fields)))))))

(defn- merged-update
  [state update-map]
  (let [history-map {:history [state]}
	set-cmd (if (contains? update-map "$set")
		  (merge (get update-map "$set") history-map)
		  history-map)]
    (assoc update-map "$set" set-cmd)))

(defn get-entity
  [entity-string]
  (let [entity-bytes (.getBytes entity-string "UTF-8")]
    (doto (BasicHttpEntity.)
      (.setContent (java.io.ByteArrayInputStream. entity-bytes))
      (.setContentEncoding "UTF-8")
      (.setContentType "application/json")
      (.setContentLength (count entity-bytes)))))

(defn fire-callback
  [callback state]
  (log/debug "fire-callback")
  (let [ct      @connect-timeout
	rt      @read-timeout
	cb-url  (:callback callback)
	cb-body (json/json-str state)]
    (log/info (str "Firing callback to " cb-url))
    (log/debug (str "Callback body is " cb-body))
    (let [http-client  (DefaultHttpClient.)
	  resp-handler (BasicResponseHandler.)
	  http-params  (.getParams http-client)
	  body-entity  (get-entity cb-body)
	  http-post    (HttpPost. cb-url)]
      (.setEntity http-post body-entity)
      (HttpConnectionParams/setConnectionTimeout http-params ct)
      (HttpConnectionParams/setSoTimeout http-params rt)
      (let [resp-body (.execute http-client http-post resp-handler)]
	(log/info (str "Response from " cb-url " is: " resp-body))))))

(defn fire-all-callbacks
  [coll-obj uuid old-state new-state]
  (let [query        {:object_persistence_uuid uuid}
	collection   (. coll-obj getName)
	update-state (first (cc-mongo/mongo-find coll-obj query {:_id 0}))
	callbacks    (:callbacks (get-callbacks collection uuid))
	changed      (not (= old-state new-state))]
    (doall
     (for [cb callbacks]
       (let [cb-type (:type cb)]
	 (cond
	  (and changed (= cb-type "on_change"))
	  (future
	   (let [thread-cb cb
		 thread-state update-state]
	     (fire-callback thread-cb thread-state)))

	  (= cb-type "on_update")
	  (future
	   (let [thread-cb cb
		 thread-state update-state]
	     (fire-callback thread-cb thread-state)))))))
    update-state))

(defn- atomic-update
  "Does an atomic update of a document. new-object must contain
   one of the keys from the update-ops vector in order for this
   to work correctly."
  [collection uuid new-obj]
  (let [conn-map (connect)
	conn     (:conn conn-map)
	db-ref   (:db-ref conn-map)
	coll-obj (cc-mongo/get-collection db-ref collection)
	query    {:object_persistence_uuid uuid}
	state    (get-state collection uuid)
	update   (merged-update state new-obj)]
    (cc-mongo/mongo-find-and-modify coll-obj query update)
    (clean-return conn (fire-all-callbacks coll-obj uuid state new-obj))))

(defn- osm-update
  "Performs a regular old update of an OSM document. Sets the
   state field of the document to new-obj."
  [collection uuid new-obj]
  (let [conn-map (connect)
	conn     (:conn conn-map)
	db-ref   (:db-ref conn-map)
	coll-obj (cc-mongo/get-collection db-ref collection)
	query    {:object_persistence_uuid uuid}
	state    (get-state collection uuid)
	update   {"$set" {:state new-obj :history [state]}}]
    (cc-mongo/mongo-find-and-modify coll-obj query update)
    (clean-return conn (fire-all-callbacks coll-obj uuid state new-obj))))

(defn update
  "Entry point for a document update. Decides whether to call atomic-update
   or osm-update based on the return value of atomic-update?."
  [collection uuid new-obj]
  (cond
   (not (exists? collection uuid)) (throw (java.lang.Exception. "Object doesn't exist."))
   (atomic-update? new-obj)        (atomic-update collection uuid new-obj)
   :else                           (osm-update collection uuid new-obj)))

(defn valid-type?
  "Tells whether cb-type is either on_update or on_change."
  [cb-type]
  (some #{cb-type} ["on_update" "on_change"]))

(defn url?
  "Tells whether some-string contains a valid URL. Apparently the
   URL class is fairly lenient with what it considers a URL, so
   this function is of dubious use."
  [some-string]
  (try
    (java.net.URL. some-string)
    (catch java.net.MalformedURLException e false))
  true)

(defn logged-return
  "Logs a message before returning a specified value."
  [msg ret]
  (log/debug msg)
  ret)

(defn valid-callbacks?
  "Entry point for validating callbacks. Callbacks are in the form:
   {\"callbacks\" [{\"callback\" \"some_url\" \"type\" \"on_update or on_change\"}]}"
  [callbacks]
  (cond
   (not (contains? callbacks "callbacks"))     (logged-return "callbacks element not present" false)
   (not (sequential? (callbacks "callbacks"))) (logged-return "callbacks element not sequential" false)
   :else
    (reduce
     (fn [f s] (or f s))
     (map
      (fn [cb]
	(cond
	 (not (contains? cb "callback")) (logged-return "URL not present in callback" false)
	 (not (url? (cb "callback")))    (logged-return (str "URL " (cb "callback") " not valid in callback") false)
	 (not (contains? cb "type"))     (logged-return "type missing from callback" false)
	 (not (valid-type? (cb "type"))) (logged-return (str "type " (cb "type") " not valid in callback") false)
	 :else true))
      (callbacks "callbacks")))))

(defn add-callbacks
  "Adds callbacks to a docuement."
  [collection uuid callbacks]
  (log/debug (json/json-str callbacks))
  (cond
   (not (valid-callbacks? callbacks)) (throw (java.lang.Exception. "Invalid Callbacks"))
   (not (exists? collection uuid))    (throw (java.lang.Exception. "Document doesn't exist."))
   :else
   (let [conn-map      (connect)
	 conn          (:conn conn-map)
	 db-ref        (:db-ref conn-map)
	 coll-obj      (cc-mongo/get-collection db-ref collection)
	 query         {:object_persistence_uuid uuid}
	 cbs           (:callbacks (get-callbacks collection uuid))
	 new-callbacks (concat cbs (callbacks "callbacks"))
	 update        {"$set" {:callbacks new-callbacks}}]
     (cc-mongo/mongo-find-and-modify coll-obj query update)
     (clean-return conn (first (cc-mongo/mongo-find coll-obj query {:_id 0 :callbacks 1}))))))

(defn dekeywordize
  "Converts the keys in a map from keywords to strings."
  [m]
  (apply merge
    (map (fn [[k v]] {(name k) (if (map? v) (dekeywordize v) v)}) m)))

(defn should-remove-callback
  "Determines whether or not a callback is in the list of callbacks to be removed"
  [curr-callback rm-callbacks]
  (let [cc (dekeywordize curr-callback)]
    (some #{cc} rm-callbacks)))

(defn filter-callbacks
  "Used by remove-callbacks to remove callbacks from a list."
  [curr-callbacks rm-callbacks]
  (log/debug (str "curr-callbacks: " curr-callbacks))
  (log/debug (str "rm-callbacks: " rm-callbacks))
  (filter
   (fn [cc]
     (if (should-remove-callback cc rm-callbacks)
       false
       true))
   curr-callbacks))

(defn remove-callbacks
  "Entry point for removing callbacks from the callbacks associated
   with a document. Callbacks should be of the form:
   {\"callbacks\" [{\"callback\" \"some_url\" \"type\" \"on_update or on_change\"}]}"
  [collection uuid callbacks]
  (cond
   (not (valid-callbacks? callbacks)) (throw (java.lang.Exception. "Invalid Callbacks"))
   (not (exists? collection uuid))    (throw (java.lang.Exception. "Document doesn't exist."))
   :else
   (let [conn-map       (connect)
	 conn           (:conn conn-map)
	 db-ref         (:db-ref conn-map)
	 coll-obj       (cc-mongo/get-collection db-ref collection)
	 query          {:object_persistence_uuid uuid}
	 curr-callbacks (:callbacks (get-callbacks collection uuid))
	 new-callbacks  (filter-callbacks curr-callbacks (callbacks "callbacks"))
	 update         {"$set" {:callbacks new-callbacks}}]
     (cc-mongo/mongo-find-and-modify coll-obj query update)
     (clean-return conn (first (cc-mongo/mongo-find coll-obj query {:_id 0}))))))

(defn query
  "Entry point for querying for documents."
  ([collection query-obj]
     (query collection query-obj {:_id 0}))
  ([collection query-obj filter]
     (let [conn-map (connect)
	   conn     (:conn conn-map)
	   db-ref   (:db-ref conn-map)
	   coll-obj (cc-mongo/get-collection db-ref collection)]
       (clean-return conn (cc-mongo/mongo-find coll-obj query-obj filter)))))