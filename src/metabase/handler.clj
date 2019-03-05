(ns metabase.handler
  "Top-level Metabase Ring handler."
  (:require [cheshire.core :as json]
            [metabase
             [middleware :as mb-middleware]
             [routes :as routes]]
            [puppetlabs.i18n.core :refer [locale-negotiator]]
            [ring.middleware
             [cookies :refer [wrap-cookies]]
             [gzip :refer [wrap-gzip]]
             [json :refer [wrap-json-body]]
             [keyword-params :refer [wrap-keyword-params]]
             [params :refer [wrap-params]]
             [session :refer [wrap-session]]]
            [metabase.server :as server]
            [ring.util
             [io :as rui]
             [response :as rr]])
  (:import [java.io BufferedWriter OutputStream OutputStreamWriter]
           [java.nio.charset Charset StandardCharsets]
           org.eclipse.jetty.server.Server
           org.eclipse.jetty.util.thread.QueuedThreadPool))

;; TODO - why not just put this in `metabase.middleware` with *all* of our other custom middleware?

(defn- streamed-json-response
  "Write `RESPONSE-SEQ` to a PipedOutputStream as JSON, returning the connected PipedInputStream"
  [response-seq opts]
  (rui/piped-input-stream
   (fn [^OutputStream output-stream]
     (with-open [output-writer   (OutputStreamWriter. ^OutputStream output-stream ^Charset StandardCharsets/UTF_8)
                 buffered-writer (BufferedWriter. output-writer)]
       (json/generate-stream response-seq buffered-writer opts)))))

(defn- wrap-streamed-json-response
  "Similar to ring.middleware/wrap-json-response in that it will serialize the response's body to JSON if it's a
  collection. Rather than generating a string it will stream the response using a PipedOutputStream.

  Accepts the following options (same as `wrap-json-response`):

  :pretty            - true if the JSON should be pretty-printed
  :escape-non-ascii  - true if non-ASCII characters should be escaped with \\u"
  [handler & [{:as opts}]]
  (fn [request]
    (let [response (handler request)]
      (if-let [json-response (and (coll? (:body response))
                                  (update-in response [:body] streamed-json-response opts))]
        (if (contains? (:headers json-response) "Content-Type")
          json-response
          (rr/content-type json-response "application/json; charset=utf-8"))
        response))))

(defn- jetty-stats []
  (when-let [jetty-server (server/instance)]
    (let [^QueuedThreadPool pool (.getThreadPool jetty-server)]
      {:min-threads  (.getMinThreads pool)
       :max-threads  (.getMaxThreads pool)
       :busy-threads (.getBusyThreads pool)
       :idle-threads (.getIdleThreads pool)
       :queue-size   (.getQueueSize pool)})))


(def app
  "The primary entry point to the Ring HTTP server."
  ;; ▼▼▼ POST-PROCESSING ▼▼▼ happens from TOP-TO-BOTTOM
  (-> #'routes/routes                    ; the #' is to allow tests to redefine endpoints
      mb-middleware/catch-api-exceptions ; catch exceptions and return them in our expected format
      (mb-middleware/log-api-call
       jetty-stats)
      mb-middleware/add-security-headers ; Add HTTP headers to API responses to prevent them from being cached
      (wrap-json-body                    ; extracts json POST body and makes it avaliable on request
        {:keywords? true})
      wrap-streamed-json-response        ; middleware to automatically serialize suitable objects as JSON in responses
      wrap-keyword-params                ; converts string keys in :params to keyword keys
      wrap-params                        ; parses GET and POST params as :query-params/:form-params and both as :params
      mb-middleware/bind-current-user    ; Binds *current-user* and *current-user-id* if :metabase-user-id is non-nil
      mb-middleware/wrap-current-user-id ; looks for :metabase-session-id and sets :metabase-user-id if Session ID is valid
      mb-middleware/wrap-api-key         ; looks for a Metabase API Key on the request and assocs as :metabase-api-key
      mb-middleware/wrap-session-id      ; looks for a Metabase Session ID and assoc as :metabase-session-id
      mb-middleware/maybe-set-site-url   ; set the value of `site-url` if it hasn't been set yet
      locale-negotiator                  ; Binds *locale* for i18n
      wrap-cookies                       ; Parses cookies in the request map and assocs as :cookies
      wrap-session                       ; reads in current HTTP session and sets :session/key
      mb-middleware/add-content-type     ; Adds a Content-Type header for any response that doesn't already have one
      wrap-gzip))                        ; GZIP response if client can handle it
;; ▲▲▲ PRE-PROCESSING ▲▲▲ happens from BOTTOM-TO-TOP
