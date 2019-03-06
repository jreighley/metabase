(ns metabase.middleware.auth-test
  (:require [expectations :refer [expect]]
            [metabase.middleware
             [auth :as mw.auth]
             [session :as mw.session]
             [util :as middleware.u]]
            [metabase.models.session :refer [Session]]
            [metabase.test.data.users :as test-users]
            [metabase.util.date :as du]
            [ring.mock.request :as mock]
            [toucan.db :as db]))

;; create a simple example of our middleware wrapped around a handler that simply returns the request
(def ^:private auth-enforced-handler
  (mw.session/wrap-current-user-id
   (mw.auth/enforce-authentication identity)))


(defn- request-with-session-id
  "Creates a mock Ring request with the given session-id applied"
  [session-id]
  (-> (mock/request :get "/anyurl")
      (assoc :metabase-session-id session-id)))


;; no session-id in the request
(expect
  middleware.u/response-unauthentic
  (auth-enforced-handler (mock/request :get "/anyurl")))

(defn- random-session-id []
  (str (java.util.UUID/randomUUID)))


;; valid session ID
(expect
  (test-users/user->id :rasta)
  (let [session-id (random-session-id)]
    (db/simple-insert! Session, :id session-id, :user_id (test-users/user->id :rasta), :created_at (du/new-sql-timestamp))
    (-> (auth-enforced-handler (request-with-session-id session-id))
        :metabase-user-id)))


;; expired session-id
;; create a new session (specifically created some time in the past so it's EXPIRED)
;; should fail due to session expiration
(expect
  middleware.u/response-unauthentic
  (let [session-id (random-session-id)]
    (db/simple-insert! Session, :id session-id, :user_id (test-users/user->id :rasta), :created_at (java.sql.Timestamp. 0))
    (auth-enforced-handler (request-with-session-id session-id))))


;; inactive user session-id
;; create a new session (specifically created some time in the past so it's EXPIRED)
;; should fail due to inactive user
;; NOTE that :trashbird is our INACTIVE test user
(expect
  middleware.u/response-unauthentic
  (let [session-id (random-session-id)]
    (db/simple-insert! Session, :id session-id, :user_id (test-users/user->id :trashbird), :created_at (du/new-sql-timestamp))
    (auth-enforced-handler (request-with-session-id session-id))))

;;  ===========================  TEST wrap-api-key middleware  ===========================

;; create a simple example of our middleware wrapped around a handler that simply returns the request
;; this works in this case because the only impact our middleware has is on the request
(def ^:private wrapped-api-key-handler
  (mw.auth/wrap-api-key identity))


;; no apikey in the request
(expect
  nil
  (:metabase-session-id
   (wrapped-api-key-handler
    (mock/request :get "/anyurl"))))


;; extract apikey from header
(expect
  "foobar"
  (:metabase-api-key
   (wrapped-api-key-handler
    (mock/header (mock/request :get "/anyurl") @#'mw.auth/metabase-api-key-header "foobar"))))


;;  ===========================  TEST enforce-api-key middleware  ===========================


;; create a simple example of our middleware wrapped around a handler that simply returns the request
(def ^:private api-key-enforced-handler
  (mw.auth/enforce-api-key (constantly {:success true})))


(defn- request-with-api-key
  "Creates a mock Ring request with the given apikey applied"
  [api-key]
  (-> (mock/request :get "/anyurl")
      (assoc :metabase-api-key api-key)))


;; no apikey in the request, expect 403
(expect
  middleware.u/response-forbidden
  (api-key-enforced-handler
   (mock/request :get "/anyurl")))


;; valid apikey, expect 200
(expect
  {:success true}
  (api-key-enforced-handler
   (request-with-api-key "test-api-key")))


;; invalid apikey, expect 403
(expect
  middleware.u/response-forbidden
  (api-key-enforced-handler
   (request-with-api-key "foobar")))
