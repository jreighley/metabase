(ns metabase.server
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [medley.core :as m]
            [metabase
             [config :as config]
             [util :as u]]
            [metabase.util.i18n :refer [trs]]
            [ring.adapter.jetty :as ring-jetty])
  (:import org.eclipse.jetty.server.Server))

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
           {:port          (config/config-int :mb-jetty-port)
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

(defn start-web-server!
  "Start the embedded Jetty web server."
  [app]
  (when-not (instance)
    ;; NOTE: we always start jetty w/ join=false so we can start the server first then do init in the background
    (let [jetty-config (jetty-config)
          new-server   (ring-jetty/run-jetty app (assoc jetty-config :join? false))]
      (log-config jetty-config)
      ;; if we didn't actually setting our new server instance as *THE* server then shut it down
      (when-not (compare-and-set! instance* nil new-server)
        (.stop new-server)))))

(defn stop-web-server!
  "Stop the embedded Jetty web server."
  []
  (let [[^Server old-server] (reset-vals! instance* nil)]
    (when old-server
      (log/info (trs "Shutting Down Embedded Jetty Webserver"))
      (.stop old-server))))
