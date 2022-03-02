(ns practaid.auth
  (:require [clojure.string :as str]
            [cljs.core.async :refer [go <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [re-frame.core :as rf]
            [ajax.core :as ajax]
            [practaid.events :refer [inject-store check-db-spec-interceptor]]))

(def client-id "5c6be04474984ab2a2a492967aa87002")

(defn random-bytes [n]
  (-> js/crypto
      (.getRandomValues (js/Uint8Array. n))))

(defn base64url [bytes]
  (-> (.-fromCharCode js/String)
      (apply bytes)
      (js/btoa)
      (str/replace #"=" "")
      (str/replace #"\+" "-")
      (str/replace #"/" "_")))

(defn go-code-challenge [code-verifier]
  (go
    (let [code-verifier-bytes (-> (js/TextEncoder.)
                                  (.encode code-verifier))
          hash-buffer (<p! (-> js/crypto
                               (.-subtle)
                               (.digest "SHA-256" code-verifier-bytes)))]
      (-> (js/Uint8Array. hash-buffer)
          (base64url)))))

(defn go-initial-auth-flow [location-origin]
  (go
    (let [code-verifier (base64url (random-bytes 96))
          nonce (base64url (random-bytes 96))
          code-challenge (<! (go-code-challenge code-verifier))
          url (str "https://accounts.spotify.com/authorize?"
                   (js/URLSearchParams. (clj->js {"response_type" "code"
                                                  "client_id" client-id
                                                  "scope" "streaming user-read-recently-played user-read-private user-read-email user-read-playback-state user-modify-playback-state user-read-currently-playing app-remote-control"
                                                  "redirect_uri" (str location-origin "/callback")
                                                  "state" nonce
                                                  "code_challenge_method" "S256"
                                                  "code_challenge" code-challenge})))]
      {:code-verifier code-verifier
       :nonce nonce
       :url url})))



(rf/reg-event-fx
  ::prepare-for-oauth
  [(rf/inject-cofx ::location-origin)]
  (fn [{:keys [location-origin]} _]
    {:fx [[::create-initial-auth-data location-origin]]}))

(rf/reg-event-fx
  ::initiate-oauth
  [check-db-spec-interceptor]
  (fn [_ [_ {:keys [code-verifier nonce url]}]]
    (let [auth-data {:nonce nonce
                     :code-verifier code-verifier}]
      {:store auth-data
       :practaid.routes/assign-url url})))

;; TODO move me to generic location?
(rf/reg-cofx
  ::location-origin
  (fn [cofx]
    (assoc cofx
      :location-origin
      (-> js/location
          .-origin))))

(rf/reg-event-fx
  ::oauth-callback
  [(rf/inject-cofx ::location-origin)
   inject-store
   check-db-spec-interceptor]
  (fn [{:keys [db store location-origin]} _]
    (let [code (-> db :current-route :query-params :code)
          code-verifier (-> store :code-verifier)]
      {:http-xhrio {:method          :post
                    :uri             "https://accounts.spotify.com/api/token"
                    :response-format (ajax/json-response-format {:keywords? true})
                    :body            (js/URLSearchParams. (clj->js {"client_id"     client-id
                                                                    "grant_type"    "authorization_code"
                                                                    "code"          code
                                                                    "redirect_uri"  (str location-origin "/callback")
                                                                    "code_verifier" code-verifier}))
                    :on-success      [::confirm-complete-auth-flow]
                    :on-failure      [:practaid.events/http-request-failure]}})))

(defn expires-at [now-date expires-in-seconds]
  (.toISOString (js/Date. (-> now-date
                              .getTime
                              (+ (* 1000 expires-in-seconds))))))

(rf/reg-event-fx
  ::confirm-complete-auth-flow
  [(rf/inject-cofx :store)
   check-db-spec-interceptor]
  (fn [{:keys [db store]} [_ {:keys [access_token expires_in refresh_token token_type scope]}]]
    {:db (assoc db :is-authorized true)
     :fx [[:store (assoc store :access-token access_token
                               :expires-at (expires-at (js/Date.) expires_in))]
          [:dispatch [:practaid.events/initialize-looper-page]]
          [:dispatch [:practaid.routes/navigate :routes/home]]]}))

(rf/reg-fx
  ::create-initial-auth-data
  (fn [location-origin]
    (go
      (let [{:keys [code-verifier nonce url]} (<! (go-initial-auth-flow location-origin))]
        (rf/dispatch [::initiate-oauth {:code-verifier code-verifier
                                        :nonce         nonce
                                        :url           url}])))))
