(ns metabase.middleware.async
  "Middleware that handles all requests in an asynchronous fashion!"
  (:require [clojure.core.async :as async]))

(defn handle-request-asynchronously
  "Middleware that schedules requests to be handled asynchronously."
  [handler]
  (fn [request respond raise]
    (future
      (handler request respond raise))
    nil))
