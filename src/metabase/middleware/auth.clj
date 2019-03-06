(ns metabase.middleware.auth
  "Middleware related to enforcing authentication/API keys (when applicable). Unlike most other middleware most of this
  is not used as part of the normal `app`; it is instead added selectively to appropriate routes."
  (:require [metabase.config :as config]
            [metabase.middleware.util :as middleware.u]))

(def ^:private ^:const ^String metabase-api-key-header "x-metabase-apikey")

(defn enforce-authentication
  "Middleware that returns a 401 response if REQUEST has no associated `:metabase-user-id`."
  [handler]
  (fn [{:keys [metabase-user-id] :as request}]
    (if metabase-user-id
      (handler request)
      middleware.u/response-unauthentic)))

(defn- wrap-api-key* [{:keys [headers], :as request}]
  (if-let [api-key (headers metabase-api-key-header)]
    (assoc request :metabase-api-key api-key)
    request))

(defn wrap-api-key
  "Middleware that sets the `:metabase-api-key` keyword on the request if a valid API Key can be found. We check the
  request headers for `X-METABASE-APIKEY` and if it's not found then then no keyword is bound to the request."
  [handler]
  (middleware.u/modify-request-middleware-fn handler wrap-api-key*))


(defn enforce-api-key
  "Middleware that enforces validation of the client via API Key, cancelling the request processing if the check fails.

  Validation is handled by first checking for the presence of the `:metabase-api-key` on the request.  If the api key
  is available then we validate it by checking it against the configured `:mb-api-key` value set in our global config.

  If the request `:metabase-api-key` matches the configured `:mb-api-key` value then the request continues, otherwise
  we reject the request and return a 403 Forbidden response."
  [handler]
  (fn [{:keys [metabase-api-key] :as request}]
    (if (= (config/config-str :mb-api-key) metabase-api-key)
      (handler request)
      ;; default response is 403
      response-forbidden)))
