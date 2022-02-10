(ns practaid.events
  (:require
    [re-frame.core :as rf]
    [practaid.db :as db]
    [akiroz.re-frame.storage :refer [reg-co-fx!]]
    [day8.re-frame.http-fx]
    [ajax.core :as ajax]
    [practaid.auth :as auth]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [cljs.core.async :refer [go <!]]
    [cljs.core.async.interop :refer-macros [<p!]])
  (:require ["spotify-web-api-js" :as SpotifyWebApi]
            [cljs.spec.alpha :as s]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interceptors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn check-and-throw
  "Throws an exception if `db` doesn't match the Spec `a-spec`."
  ;; TODO: make the console error more legible
  [a-spec db]
  (when-not (s/valid? a-spec db)
    (throw (ex-info (str "spec check failed: " (s/explain-str a-spec db)) {}))))

(def check-spec-interceptor (rf/after (partial check-and-throw :practaid.db/db)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cofx, Events, Effects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Generic ----------------------------------------------

(rf/reg-event-fx
  ::http-request-failure
  [check-spec-interceptor]
  (fn [_ [_ err]]
    ;; Innocent side effect
    (js/console.error (clj->js err))
    {}))

(rf/reg-fx
  ::clear-timeout
  (fn [id]
    (js/clearTimeout id)))

(rf/reg-fx
  ::set-timeout
  (fn [{:keys [f timeout-ms on-set]}]
    (let [id (js/setTimeout f timeout-ms)]
      (rf/dispatch (conj on-set id)))))

(rf/reg-fx
  ::set-interval
  (fn [{:keys [f interval-ms on-set]}]
    (let [id (js/setInterval f interval-ms)]
      (when on-set
        (rf/dispatch (conj on-set id))))))

(rf/reg-fx
  ::clear-interval
  (fn [id]
    (js/clearInterval id)))



;; Initialization ----------------------------------------

(rf/reg-event-fx
  ::initialize-db
  [(rf/inject-cofx :store)
   check-spec-interceptor]
  (fn [{:keys [store]} _]
    {:db (merge (db/default-db)
                store)}))



;; Routing -----------------------------------------------

(rf/reg-event-fx
  ::navigate
  [check-spec-interceptor]
  (fn [_cofx [_ route]]
    {::navigate! route}))

(rf/reg-event-db
  ::navigated
  [check-spec-interceptor]
  (fn [db [_ new-match]]
    (let [old-match   (:current-route db)
          controllers (rfc/apply-controllers (:controllers old-match) new-match)]
      (assoc db :current-route (assoc new-match :controllers controllers)))))

(rf/reg-fx
  ::navigate!
  (fn [route]
    (rfe/push-state route)))

;; For foreign-domain routes
(rf/reg-fx
  ::assign-url
  (fn [url]
    (-> js/window
        (.-location)
        (.assign url))))



;; Auth ---------------------------------------------------

(reg-co-fx! :practaid
            {:fx :store
             :cofx :store})

(rf/reg-event-fx
  ::prepare-for-oauth
  (fn [_ _]
    {::create-initial-auth-data nil}))

(rf/reg-event-fx
  ::initiate-oauth
  [check-spec-interceptor]
  (fn [{:keys [db]} [_ {:keys [code-verifier nonce url]}]]
    (let [auth-data {:nonce nonce
                     :code-verifier code-verifier}]
      {:db (merge db auth-data)
       :store auth-data
       ::assign-url url})))

(rf/reg-event-fx
  ::oauth-callback
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [code (-> db :current-route :query-params :code)
          code-verifier (-> db :code-verifier)]
      {:http-xhrio {:method :post
                    :uri "https://accounts.spotify.com/api/token"
                    :response-format (ajax/json-response-format {:keywords? true})
                    :body (js/URLSearchParams. (clj->js {"client_id" auth/client-id
                                                         "grant_type" "authorization_code"
                                                         "code" code
                                                         "redirect_uri" "http://localhost:8080/callback"
                                                         "code_verifier" code-verifier}))
                    :on-success [::confirm-complete-auth-flow]
                    :on-failure [::http-request-failure]}})))

(rf/reg-event-fx
  ::confirm-complete-auth-flow
  [(rf/inject-cofx :store)
   check-spec-interceptor]
  (fn [{:keys [db store]} [_ {:keys [access_token token_type expires_in refresh_token scope]}]]
    {:db (assoc db :access-token access_token)
     :fx [[:store (assoc store :access-token access_token)]
          [:dispatch [::initialize-looper-page]]]}))

(rf/reg-fx
  ::create-initial-auth-data
  (fn [_]
    (go
      (let [{:keys [code-verifier nonce url]} (<! (auth/go-initial-auth-flow))]
        (rf/dispatch [::initiate-oauth {:code-verifier code-verifier
                                        :nonce            nonce
                                        :url              url}])))))



;; HTTP ---------------------------------------------------

(rf/reg-event-fx
  ::refresh-recently-played
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    {:fx [[:http-xhrio {:method :get
                        :uri "https://api.spotify.com/v1/me/player/recently-played"
                        :headers {"Authorization" (str "Bearer " (:access-token db))
                                  "Content-Type" "application/json"}
                        :response-format (ajax/json-response-format {:keywords? true})
                        :url-params {"limit" "1"}
                        :on-success [::confirm-refresh-recently-played]
                        :on-failure [::http-request-failure]}]]}))

(rf/reg-event-db
  ::confirm-refresh-recently-played
  [check-spec-interceptor]
  (fn [db [_ recently-played]]
    (assoc db :recently-played recently-played)))

(rf/reg-event-fx
  ::refresh-playback-state
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [access-token (:access-token db)]
      (if (not access-token)
        (throw (ex-info "No access token!" {}))
        {:http-xhrio {:method :get
                      :uri "https://api.spotify.com/v1/me/player"
                      :headers {"Authorization" (str "Bearer " access-token)
                                "Content-Type" "application/json"}
                      :response-format (ajax/json-response-format {:keywords? true})
                      :on-success [::confirm-refresh-playback-state]
                      :on-failure [::http-request-failure]}}))))

(rf/reg-event-db
  ::confirm-refresh-playback-state
  [check-spec-interceptor]
  (fn [db [_ state]]
    (assoc db :external-playback-state state)))

(rf/reg-event-fx
  ::takeover-playback
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [{:keys [player device-id access-token]} db]
      (when (and player device-id access-token)
        {:db (assoc db :is-taking-over-playback true)
         :http-xhrio {:method :put
                      :uri "https://api.spotify.com/v1/me/player"
                      :headers {"Authorization" (str "Bearer " access-token)
                                "Content-Type" "application/json"}
                      :body (.stringify js/JSON (clj->js {:device_ids [device-id]}))
                      :response-format (ajax/json-response-format {:keywords? true})
                      ;; TODO
                      :on-success [::confirm-takeover-playback]
                      :on-failure [::deny-takeover-playback]}}))))

(rf/reg-event-fx
  ::confirm-takeover-playback
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    ;; TODO: also fetch playback state
    {:db (assoc db :is-taking-over-playback false)
     :fx [[:dispatch [::refresh-playback-state]]]}))

(rf/reg-event-fx
  ::deny-takeover-playback
  [check-spec-interceptor]
  (fn [{:keys [db]}]
    ;; TODO: test whether this can be done in :finally
    {:db (assoc db :is-taking-over-playback false)}))

(rf/reg-event-fx
  ::refresh-track-analysis
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [{:keys [access-token track external-playback-state]} db
          track-id (or (:id track)
                       (get-in external-playback-state [:item :id]))]
      (when track-id
        {:fx [[:http-xhrio {:method :get
                            :uri (str "https://api.spotify.com/v1/audio-analysis/" track-id)
                            :headers {"Authorization" (str "Bearer " access-token)
                                      "Content-Type" "application/json"}
                            :response-format (ajax/json-response-format {:keywords? true})
                            :on-success [::confirm-track-analysis]
                            :on-failure [::http-request-failure]}]]}))))

(rf/reg-event-db
  ::confirm-track-analysis
  [check-spec-interceptor]
  (fn [db [_ track-analysis]]
    (assoc db :track-analysis track-analysis)))



;; Player initialization ----------------------------------

;; Should only be required once, to instantiate the player
(rf/reg-cofx
  ::spotify-sdk-object
  (fn [cofx]
    (assoc cofx
      :spotify-sdk
      (-> js/window
          .-Spotify))))

(rf/reg-event-fx
  ::spotify-player-sdk-ready
  [(rf/inject-cofx ::spotify-sdk-object)
   check-spec-interceptor]
  (fn [{:keys [db spotify-sdk]} _]
    (let [access-token (:access-token db)]
      {:fx [[::instantiate-and-initialize-player {:spotify-sdk spotify-sdk
                                                  :access-token access-token}]]})))

(rf/reg-event-db
  ::player-instantiated
  [check-spec-interceptor]
  (fn [db [_ player]]
    (assoc db :player player)))

(rf/reg-fx
  ::initialize-spotify-sdk
  (fn []
    (let [script (.createElement js/document "script")
          _ (set! (.-src script) "https://sdk.scdn.co/spotify-player.js")
          _ (set! (.-async script) true)
          _ (-> js/document .-body (.appendChild script))
          _ (set! (.-onSpotifyWebPlaybackSDKReady js/window)
                  #(rf/dispatch [::spotify-player-sdk-ready]))])))

(rf/reg-fx
  ::instantiate-and-initialize-player
  (fn [{:keys [spotify-sdk access-token]}]
    (let [player (new (.-Player spotify-sdk)
                      (clj->js {:name "PractAid"
                                :getOAuthToken (fn [callback]
                                                 ; TODO dynamically fetch token
                                                 (callback access-token))
                                :volume 0.5}))

          _ (.addListener player "ready"
                          (fn [player]
                            (rf/dispatch [::player-ready player])))

          _ (.addListener player "not_ready"
                          (fn [player]
                            (rf/dispatch [::player-not-ready player])))

          _ (.addListener player "player_state_changed"
                          (fn [state]
                            (when state  ; TODO when is this nil
                              (rf/dispatch [::player-state-changed state]))))
          _ (.connect player)]
      (rf/dispatch [::player-instantiated player]))))



;; Player-emitted events ----------------------------------

;; https://developer.spotify.com/documentation/web-playback-sdk/reference/#object-web-playback-player
(rf/reg-event-db
  ::player-ready
  [check-spec-interceptor]
  (fn [db [_ js-player-obj]]
    (let [state (js->clj js-player-obj
                         :keywordize-keys true)
          {:keys [device_id]} state]
      (assoc db :device-id device_id))))

;; https://developer.spotify.com/documentation/web-playback-sdk/reference/#object-web-playback-player
(rf/reg-event-db
  ::player-not-ready
  [check-spec-interceptor]
  (fn [db [_ js-player-obj]]
    ;; TODO: I think state might have a device ID,
    ;;   so this should be tracked in a separate :status field
    (assoc db :device-id nil)))

(rf/reg-event-fx
  ::player-state-changed
  [check-spec-interceptor]
  (fn [{:keys [db]} [_ js-state]]
    (let [state (js->clj js-state :keywordize-keys true)
          {old-track :track} db
          new-track (get-in state [:track_window :current_track])
          is-different-track (not= (:id old-track) (:id new-track))
          clear-looper (when is-different-track
                         [:dispatch [::clear-looper]])]
      {:db (-> db
               (assoc :is-paused (:paused state))
               (assoc :track new-track)
               (assoc :player-pos-ms (:position state)))
       ;; TODO I should be conditional on the track ID changing!
       :fx [[:dispatch [::refresh-track-analysis]]
            clear-looper]})))



;; Looper page---------------------------------------------

(rf/reg-event-fx
  ::initialize-looper-page
  [check-spec-interceptor]
  (fn [_ _]
    {:fx [[:dispatch [::navigate :routes/looper]]
          [::initialize-spotify-sdk]
          ;; TODO: store the outcome ID
          [::set-interval {:f #(rf/dispatch [::refresh-playback-state])
                           :interval-ms 5000}]
          [:dispatch [::refresh-track-analysis]]
          [:dispatch [::refresh-recently-played]]]}))

(rf/reg-event-db
  ::set-player-pos-ms
  [check-spec-interceptor]
  (fn [db [_ player-pos-ms]]
    (assoc db :player-pos-ms player-pos-ms)))

(rf/reg-event-db
  ::set-player-pos-query-interval-id
  [check-spec-interceptor]
  (fn [db [_ id]]
    (assoc db :player-pos-query-interval id)))

(rf/reg-event-fx
  ::clear-looper
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [{:keys [loop-timeout-id]} db
          clear-loop-timeout (when loop-timeout-id
                               [::clear-timeout loop-timeout-id])]
      {:db (-> db
               (assoc :loop-start-ms nil)
               (assoc :loop-end-ms nil)
               (assoc :loop-timeout-id nil))
       :fx [clear-loop-timeout]})))


(rf/reg-event-fx
  ::reset-looper
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [{:keys [loop-start-ms loop-end-ms loop-timeout-id player]} db
          clear-existing (when loop-timeout-id
                           [::clear-timeout loop-timeout-id])
          seek-player (when (and player loop-start-ms loop-end-ms)
                        [::player {:player player
                                   :action #(.seek ^SpotifyWebApi % loop-start-ms)
                                   :on-failure #(js/console.error %)}])
          set-timeout (when (and player loop-start-ms loop-end-ms)
                        [::set-timeout {:f #(rf/dispatch [::reset-looper])
                                        ;; TODO?
                                        :timeout-ms (- loop-end-ms loop-start-ms)
                                        :on-set [::set-loop-timeout-id]}])]
      {:db (assoc db :loop-timeout-id nil)
       :fx [clear-existing
            seek-player
            set-timeout]})))

(rf/reg-event-db
  ::set-loop-timeout-id
  (fn [db [_ timeout-id]]
    (assoc db :loop-timeout-id timeout-id)))

(rf/reg-event-fx
  ::toggle-play
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [{:keys [player
                  is-paused
                  player-pos-query-interval
                  loop-timeout-id
                  loop-start-ms
                  loop-end-ms
                  player-pos-ms]} db
          is-paused' (not is-paused)
          action (if is-paused'
                   (fn [^SpotifyWebApi player] (.pause player))
                   (fn [^SpotifyWebApi player] (.resume player)))

          update-player [::player {:player player
                                   :action action
                                   :on-failure #(js/console.error "Failed to toggle!")}]

          clear-loop-timeout (when (and is-paused' loop-timeout-id)
                               [::clear-timeout loop-timeout-id])

          db (if clear-loop-timeout
               (assoc db :loop-timeout-id nil)
               db)

          set-loop-timeout (when (and (not is-paused') loop-start-ms loop-end-ms)
                             [::set-timeout {:f #(rf/dispatch [::reset-looper])
                                             :timeout-ms (- loop-end-ms player-pos-ms)
                                             :on-set [::set-loop-timeout-id]}])

          clear-pos-interval (when player-pos-query-interval
                               [::clear-interval player-pos-query-interval])

          set-pos-interval (when (not is-paused')
                             [::set-interval {:f #(.then (.getCurrentState player)
                                                         (fn [state]
                                                           (rf/dispatch [::set-player-pos-ms (.-position state)])))
                                              :interval-ms 200
                                              :on-set [::set-player-pos-query-interval-id]}])]

      {:db (-> db
               (assoc :is-paused is-paused')
               (assoc :player-pos-query-interval nil))
       :fx [update-player
            clear-loop-timeout
            set-loop-timeout
            clear-pos-interval
            set-pos-interval]})))

(rf/reg-event-fx
  ::next-track
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [player (:player db)]
      {::player {:player player
                 :action (fn [^SpotifyWebApi player] (.nextTrack player))
                 :on-success #(println "Next track!")
                 :on-failure #(js/console.error "Failed to next-track!")}})))

(rf/reg-event-fx
  ::prev-track
  [check-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [player (:player db)]
      {::player {:player player
                 :action (fn [^SpotifyWebApi player] (.previousTrack player))
                 :on-success #(println "Prev track!")
                 :on-failure #(js/console.error "Failed to prev-track!")}})))

(def min-loop-length-ms 2000)

(rf/reg-event-db
  ::attempt-change-loop-start
  [check-spec-interceptor]
  (fn [db [_ start-ms end-ms]]
    (assoc db :loop-start-ms (min (int start-ms)
                                  (- end-ms min-loop-length-ms)))))

(rf/reg-event-db
  ::attempt-change-loop-end
  [check-spec-interceptor]
  (fn [db [_ end-ms start-ms]]
    (assoc db :loop-end-ms (max (int end-ms)
                                (+ start-ms min-loop-length-ms)))))

(rf/reg-fx
  ::player
  (fn [{:keys [player action on-success on-failure]}]
    (let [handler (fn [f-or-v-or-nil]
                    (cond
                      (vector? f-or-v-or-nil) #(rf/dispatch (conj f-or-v-or-nil %))
                      (fn? f-or-v-or-nil) f-or-v-or-nil
                      :else (constantly nil)))
          on-success (handler on-success)
          on-failure (handler on-failure)]
      (-> (action player)
          (.then on-success)
          (.catch on-failure)))))