(ns metabase.middleware.util
  "Ring middleware utility functions."
  (:require [clojure.string :as str]))

(def response-unauthentic "Generic `401 (Unauthenticated)` Ring response map." {:status 401, :body "Unauthenticated"})
(def response-forbidden   "Generic `403 (Forbidden)` Ring response map."       {:status 403, :body "Forbidden"})

(defn api-call?
  "Is this ring request an API call (does path start with `/api`)?"
  [{:keys [^String uri]}]
  (str/starts-with? uri "/api"))

(defn public?
  "Is this ring request one that will serve `public.html`?"
  [{:keys [uri]}]
  (re-matches #"^/public/.*$" uri))

(defn embed?
  "Is this ring request one that will serve `public.html`?"
  [{:keys [uri]}]
  (re-matches #"^/embed/.*$" uri))

(defn cacheable?
  "Can the ring request be permanently cached?"
  [{:keys [uri query-string]}]
  ;; match requests that are js/css and have a cache-busting query string
  (and query-string (re-matches #"^/app/dist/.*\.(js|css)$" uri)))

(defn modify-request-middleware-fn
  "Helper function to create middleware that applies `modify-request` to incoming requests and handles both sync and
  async request styles."
  [handler modify-request]
  (fn [request & async-args]
    (apply handler (modify-request request) async-args)))

(defn modify-response-middleware-fn
  "Helper function to create middleware that applies `(modify-response request response)` to outgoing responses and
  handles both sync and async request styles."
  [handler modify-response]
  (fn
    ([request]
     (modify-response request (handler request)))

    ([request respond raise]
     (handler request (comp respond (partial modify-response request)) raise))))
