(ns practaid.events
  (:require
    [re-frame.core :as rf]
    [practaid.interceptors :refer [inject-store check-db-spec-interceptor check-store-spec-interceptor]]
    [practaid.player :as player]
    [practaid.db :as db]
    [day8.re-frame.http-fx]
    [ajax.core :as ajax]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [cljs.core.async :refer [go <!]]
    [cljs.core.async.interop :refer-macros [<p!]]
    [cljs.spec.alpha :as s]
    ["colorthief/dist/color-thief.mjs" :default ColorThief]))



;; Initialization ----------------------------------------

(rf/reg-event-fx
  ::initialize-app
  [inject-store
   check-db-spec-interceptor
   check-store-spec-interceptor]
  (fn [{:keys [store]} _]
    (let [{:keys [access-token expires-at refresh-token]} store
          is-expired (boolean (or (not expires-at)
                                  (< (js/Date. expires-at) (js/Date.))))
          is-authorized (boolean (and access-token (not is-expired)))
          db (-> (db/default-db)
                 (assoc :is-authorized is-authorized))
          init-looper-page (when is-authorized
                             [:dispatch [::initialize-looper-page]])]
      {:db db
       :fx [init-looper-page
            [:dispatch [:practaid.hotkeys/register-hotkeys]]]})))

(rf/reg-event-fx
  ::reset-app-completely
  [inject-store
   check-db-spec-interceptor
   check-store-spec-interceptor]
  (fn [_ _]
    {:fx [[:store {}]
          [:practaid.routes/reload-page nil]]}))






;; HTTP ---------------------------------------------------


(rf/reg-event-fx
  ::refresh-track-analysis
  [inject-store
   check-db-spec-interceptor]
  (fn [{:keys [db store]} _]
    (let [{player-state ::player/state} db
          {:keys [external-playback-state]} player-state
          access-token (:access-token store)
          track (db/playback-track db)
          track-id (or (:id track)
                       (get-in external-playback-state [:item :id]))]
      (when track-id
        {:fx [[:http-xhrio {:method :get
                            :uri (str "https://api.spotify.com/v1/audio-analysis/" track-id)
                            :headers {"Authorization" (str "Bearer " access-token)
                                      "Content-Type" "application/json"}
                            :response-format (ajax/json-response-format {:keywords? true})
                            :on-success [::confirm-track-analysis]
                            :on-failure [:practaid.interceptors/http-request-failure]}]]}))))

(rf/reg-event-db
  ::confirm-track-analysis
  [check-db-spec-interceptor]
  (fn [db [_ track-analysis]]
    (assoc db :track-analysis track-analysis)))


;; Looper page---------------------------------------------

(rf/reg-event-fx
  ::album-cover-img-loaded
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ img-element]]
    (let [palette (-> (new ColorThief)
                      (. getPalette img-element 2)
                      (js->clj))]
      {:db (assoc db :album-colors palette)})))

(rf/reg-event-fx
  ::initialize-looper-page
  [check-db-spec-interceptor]
  (fn [_ _]
    {:fx [
          [:practaid.player/initialize-spotify-sdk nil]
          ;; TODO: store the outcome ID
          [:practaid.interceptors/set-interval {:f #(rf/dispatch [:practaid.player/refresh-playback-state])
                                                :interval-ms 5000}]
          [:dispatch [::refresh-track-analysis]]
          [:dispatch [:practaid.player/refresh-recently-played]]]}))