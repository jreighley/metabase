(ns metabase.middleware.exceptions
  "Ring middleware for handling Exceptions thrown in API request handler functions."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metabase.middleware.security :as middleware.security]
            [metabase.util :as u]
            [metabase.util.i18n :as ui18n :refer [trs]])
  (:import java.sql.SQLException))

(defn genericize-exceptions
  "Catch any exceptions thrown in the request handler body and rethrow a generic 400 exception instead. This minimizes
  information available to bad actors when exceptions occur on public endpoints."
  [handler]
  (fn [request respond _]
    (let [raise (fn [e]
                  (log/warn e (trs "Exception in API call"))
                  (respond {:status 400, :body "An error occurred."}))]
      (try
        (handler request respond raise)
        (catch Throwable e
          (raise e))))))

(defn message-only-exceptions
  "Catch any exceptions thrown in the request handler body and rethrow a 400 exception that only has the message from
  the original instead (i.e., don't rethrow the original stacktrace). This reduces the information available to bad
  actors but still provides some information that will prove useful in debugging errors."
  [handler]
  (fn [request respond _]
    (let [raise (fn [^Throwable e]
                  (respond {:status 400, :body (.getMessage e)}))]
      (try
        (handler request respond raise)
        (catch Throwable e
          (raise e))))))

(defn- api-exception-response
  "Convert an exception from an API endpoint into an appropriate HTTP response."
  [^Throwable e]
  (let [{:keys [status-code], :as info}
        (ex-data e)

        other-info
        (dissoc info :status-code :schema :type)

        message
        (.getMessage e)

        body
        (cond
          ;; Exceptions that include a status code *and* other info are things like
          ;; Field validation exceptions. Return those as is
          (and status-code
               (seq other-info))
          (ui18n/localized-strings->strings other-info)

          ;; If status code was specified but other data wasn't, it's something like a
          ;; 404. Return message as the (plain-text) body.
          status-code
          (str message)

          ;; Otherwise it's a 500. Return a body that includes exception & filtered
          ;; stacktrace for debugging purposes
          :else
          (let [stacktrace (u/filtered-stacktrace e)]
            (merge
             (assoc other-info
               :message    message
               :type       (class e)
               :stacktrace stacktrace)
             (when (instance? SQLException e)
               {:sql-exception-chain
                (str/split (with-out-str (jdbc/print-sql-exception-chain e))
                           #"\s*\n\s*")}))))]
    {:status  (or status-code 500)
     :headers (middleware.security/security-headers)
     :body    body}))

(defn catch-api-exceptions
  "Middleware that catches API Exceptions and returns them in our normal-style format rather than the Jetty 500
  Stacktrace page, which is not so useful for our frontend."
  [handler]
  (fn [request respond raise]
    (handler
     request
     respond
     (comp respond api-exception-response))))


(defn catch-uncaught-exceptions
  "Middleware that catches any unexpected Exceptions that reroutes them thru `raise` where they can be handled
  appropriately."
  [handler]
  (fn [request response raise]
    (try
      (handler request response raise)
      (catch Throwable e
        (raise e)))))
