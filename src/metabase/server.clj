(ns metabase.server
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [medley.core :as m]
            [metabase
             [config :as config]
             [util :as u]]
            [metabase.util.i18n :refer [trs]]
            [ring.adapter.jetty :as ring-jetty]
            [ring.util.servlet :as servlet])
  (:import java.util.concurrent.ThreadPoolExecutor
           javax.servlet.AsyncContext
           [javax.servlet.http HttpServletRequest HttpServletResponse]
           [org.eclipse.jetty.server Request Server]
           org.eclipse.jetty.server.handler.AbstractHandler))

;; Since we're using core.async for some potentially blocking tasks (such as waiting for query responses) use a bigger
;; thread pool than usual. (Default is 8). That way we won't be blocking requests because other requests are waiting
;; for QP results. Default to 32 or whatever Clojure chose for the pooledExecutor, whichever is higher.
;;
;; (Yes, this seems like it could be a waste of memory, but since things are async now Jetty doesn't need as many
;; threads to process responses; so we have some savings there; as we rework more code to be async, we can get away
;; with a smaller threadpool)
(System/setProperty
 "clojure.core.async.pool-size"
 (or
  (System/getProperty "clojure.core.async.pool-size")
  (str
   (max
    32
    (.getPoolSize ^ThreadPoolExecutor clojure.lang.Agent/pooledExecutor)))))

(defn- jetty-ssl-config []
  (m/filter-vals
   some?
   {:ssl-port       (config/config-int :mb-jetty-ssl-port)
    :keystore       (config/config-str :mb-jetty-ssl-keystore)
    :key-password   (config/config-str :mb-jetty-ssl-keystore-password)
    :truststore     (config/config-str :mb-jetty-ssl-truststore)
    :trust-password (config/config-str :mb-jetty-ssl-truststore-password)}))

(defn- jetty-config []
  (cond-> (m/filter-vals
           some?
           {:async?        true
            :port          (config/config-int :mb-jetty-port)
            :host          (config/config-str :mb-jetty-host)
            :max-threads   (config/config-int :mb-jetty-maxthreads)
            :min-threads   (config/config-int :mb-jetty-minthreads)
            :max-queued    (config/config-int :mb-jetty-maxqueued)
            :max-idle-time (config/config-int :mb-jetty-maxidletime)})
    (config/config-str :mb-jetty-daemon) (assoc :daemon? (config/config-bool :mb-jetty-daemon))
    (config/config-str :mb-jetty-ssl)    (-> (assoc :ssl? true)
                                             (merge (jetty-ssl-config)))))

(defn- log-config [jetty-config]
  (log/info (trs "Launching Embedded Jetty Webserver with config:")
            "\n"
            (u/pprint-to-str (m/filter-keys
                              #(not (str/includes? % "password"))
                              jetty-config))))

(defonce ^:private instance*
  (atom nil))

(defn instance
  "*THE* instance of our Jetty web server, if there currently is one."
  ^Server []
  @instance*)

(defn- exception-response [^HttpServletResponse response, ^AsyncContext context, ^Throwable e]
  (.sendError response 500 (.getMessage e))
  (.complete context))

(defn- ^AbstractHandler async-proxy-handler [handler]
  (proxy [AbstractHandler] []
    (handle [_, ^Request base-request, ^HttpServletRequest request, ^HttpServletResponse response]
      (let [^AsyncContext context (.startAsync request)
            c                     (async/promise-chan)]
        (async/go
          (when-let [[response-map, ^Throwable e] (async/<! c)]
            (async/close! c)
            ;; TODO - not sure when the appropriate time to do this is
            (.setHandled base-request true)
            (if e
              (exception-response response context e)
              (servlet/update-servlet-response response context response-map))))

        (let [request (servlet/build-request-map request)
              respond (fn [response-map]
                        (async/put! c [response-map]))
              raise   (fn [e]
                        (async/put! c [nil e]))]
          (async/go
            (handler request respond raise)))))))

(defn- create-server
  ^Server [handler options]
  (doto ^Server (#'ring-jetty/create-server options)
    (.setHandler (async-proxy-handler handler))))

(defn start-web-server!
  "Start the embedded Jetty web server. Returns `:started` if a new server was started; `nil` if there was already a
  running server."
  [handler]
  (when-not (instance)
    ;; NOTE: we always start jetty w/ join=false so we can start the server first then do init in the background
    (let [config     (jetty-config)
          new-server (create-server handler config)]
      (log-config config)
      ;; Only start the server if the newly created server becomes the official new server
      ;; Don't JOIN yet -- we're doing other init in the background; we can join later
      (when (compare-and-set! instance* nil new-server)
        (.start new-server)
        :started))))

(defn stop-web-server!
  "Stop the embedded Jetty web server. Returns `:stopped` if a server was stopped, `nil` if there was nothing to stop."
  []
  (let [[^Server old-server] (reset-vals! instance* nil)]
    (when old-server
      (log/info (trs "Shutting Down Embedded Jetty Webserver"))
      (.stop old-server)
      :stopped)))
