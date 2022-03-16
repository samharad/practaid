(ns practaid.player
  "Thin layer of concerns related to playback.

  We should not be adding large functionalities here; we should just be integrating
  existing Spotify-API functionalities into our app."
  (:require [re-frame.core :as rf]
            [practaid.interceptors :refer [inject-store check-db-spec-interceptor check-store-spec-interceptor]]
            [cljs.spec.alpha :as s]
            [practaid.spot :as spot]
            [ajax.core :as ajax]))

(s/def ::player (s/nilable any? #_#(= js/Object (type %))))
(s/def ::device-id (s/nilable string?))
(s/def ::playback-state (s/nilable ::spot/playback-state))
(s/def ::pos-ms (s/nilable (s/and integer? (complement neg?))))
(s/def ::pos-query-interval-id (s/nilable number?))
(s/def ::external-playback-state (s/nilable any?))
(s/def ::is-taking-over-playback boolean?)
(s/def ::recently-played (s/nilable any?))

(s/def ::state (s/keys :req-un [::player
                                ::device-id
                                ::playback-state
                                ::pos-ms
                                ::pos-query-interval-id
                                ::external-playback-state
                                ::recently-played]))

;(def default-state {:player nil
;                    :device-id nil
;                    :playback-state nil
;                    :pos-ms nil
;                    :pos-query-interval-id nil})

;; Should only be required once, to instantiate the player
(rf/reg-cofx
  ::spotify-sdk-object
  (fn [cofx]
    (assoc cofx :spotify-sdk (-> js/window .-Spotify))))

(rf/reg-event-fx
  ::spotify-player-sdk-ready
  [(rf/inject-cofx ::spotify-sdk-object)
   inject-store
   check-db-spec-interceptor]
  (fn [{:keys [spotify-sdk store]} _]
    (let [access-token (:access-token store)]
      {:fx [[::instantiate-and-initialize-player {:spotify-sdk spotify-sdk
                                                  :access-token access-token}]]})))

(rf/reg-event-db
  ::player-instantiated
  [check-db-spec-interceptor]
  (fn [db [_ player]]
    (assoc-in db [::state :player] player)))

(rf/reg-fx
  ::initialize-spotify-sdk
  (fn [_]
    (let [script (.createElement js/document "script")
          _ (set! (.-src script) "https://sdk.scdn.co/spotify-player.js")
          _ (set! (.-async script) true)
          _ (-> js/document .-body (.appendChild script))
          _ (set! (.-onSpotifyWebPlaybackSDKReady js/window)
                  #(rf/dispatch [::spotify-player-sdk-ready]))])))

(rf/reg-event-fx
  ::player-requests-access-token
  [inject-store
   check-db-spec-interceptor
   check-store-spec-interceptor]
  (fn [{:keys [store]} [_ callback]]
    (let [{:keys [access-token expires-at]} store
          is-expired (boolean (or (not expires-at)
                                  (< (js/Date. expires-at) (js/Date.))))
          fx (if is-expired
               [[:dispatch [:practaid.events/reset-app-completely]]]
               [[::exec-player-callback {:callback callback
                                         :access-token access-token}]])]
      {:fx fx})))

(rf/reg-fx
  ::exec-player-callback
  (fn [{:keys [callback access-token]}]
    (callback access-token)))

(rf/reg-fx
  ::instantiate-and-initialize-player
  (fn [{:keys [^js spotify-sdk]}]
    (let [player (new (.-Player spotify-sdk)
                      (clj->js {:name "PractAid"
                                :getOAuthToken (fn [callback]
                                                 (rf/dispatch [::player-requests-access-token callback]))
                                :volume 0.5}))
          _ (.addListener player "autoplay_failed"
                          (fn [] (js/console.error "Autoplay is not allowed by the browser autoplay rules.")))

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

;; TODO move me
(def playback-pos-refresh-interval-ms 200)

;; https://developer.spotify.com/documentation/web-playback-sdk/reference/#object-web-playback-player
(rf/reg-event-db
  ::player-ready
  [check-db-spec-interceptor]
  (fn [db [_ js-player-obj]]
    (let [state (js->clj js-player-obj
                         :keywordize-keys true)
          {:keys [device_id]} state]
      (assoc-in db [::state :device-id] device_id))))

;; https://developer.spotify.com/documentation/web-playback-sdk/reference/#object-web-playback-player
(rf/reg-event-db
  ::player-not-ready
  [check-db-spec-interceptor]
  (fn [db [_ js-player-obj]]
    (assoc-in db [::state :device-id] nil)))

(rf/reg-event-db
  ::set-player-pos-query-interval-id
  [check-db-spec-interceptor]
  (fn [db [_ id]]
    (assoc-in db [::state :pos-query-interval-id] id)))


(rf/reg-event-db
  ::increment-player-pos-ms
  [check-db-spec-interceptor]
  (fn [db [_ increment-val]]
    (update-in db [::state :pos-ms] + increment-val)))

(rf/reg-event-fx
  ::player-state-changed
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ js-state]]
    (let [{player-state ::state} db
          {player-pos-query-interval-id :pos-query-interval-id} player-state
          prev-state (:playback-state player-state)
          state (js->clj js-state :keywordize-keys true)
          [prev-paused is-paused] (map :paused [prev-state state])
          player-pos-ms (get-in state [:position])
          set-pos-interval (when (and (or prev-paused (nil? prev-paused))
                                      (not is-paused))
                             [:practaid.interceptors/set-interval {:f           #(rf/dispatch [::increment-player-pos-ms playback-pos-refresh-interval-ms])
                                                                   :interval-ms playback-pos-refresh-interval-ms
                                                                   :on-set      [::set-player-pos-query-interval-id]}])
          clear-pos-interval (when (and (not prev-paused) is-paused player-pos-query-interval-id)
                               (println "Clearing interval")
                               [:practaid.interceptors/clear-interval player-pos-query-interval-id])
          db (-> db
                 (assoc-in [::state :pos-ms] player-pos-ms)
                 (assoc-in [::state :playback-state] state))
          db (if clear-pos-interval
               (assoc-in db [::state :pos-query-interval-id] nil)
               db)]
      {:db db
       :fx [clear-pos-interval
            set-pos-interval
            [:dispatch [:practaid.looper/player-state-changed prev-state state]]]})))

(rf/reg-fx
  ::player
  ;; JS type annotation fixes issues (strangely) only with the next/prev buttons;
  ;; resume, pause, seek work without this annotation (solely with the other one)
  (fn [{:keys [^js player action on-success on-failure]}]
    (let [[action-type & args] action
          action (action-type {:resume #(.resume player)
                               :pause #(.pause player)
                               :seek #(.seek player (first args))
                               :next-track #(.nextTrack player)
                               :previous-track #(.previousTrack player)})
          handler (fn [f-or-v-or-nil]
                    (cond
                      (vector? f-or-v-or-nil) #(rf/dispatch (conj f-or-v-or-nil %))
                      (fn? f-or-v-or-nil) f-or-v-or-nil
                      :else (constantly nil)))
          on-success (handler on-success)
          on-failure (handler on-failure)]
      (-> (action)
          (.then on-success)
          (.catch on-failure)))))





(rf/reg-event-fx
  ::refresh-playback-state
  [inject-store
   check-db-spec-interceptor]
  (fn [{:keys [store]} _]
    (let [access-token (:access-token store)]
      (if (not access-token)
        (throw (ex-info "No access token!" {}))
        {:http-xhrio {:method          :get
                      :uri             "https://api.spotify.com/v1/me/player"
                      :headers         {"Authorization" (str "Bearer " access-token)
                                        "Content-Type"  "application/json"}
                      :response-format (ajax/json-response-format {:keywords? true})
                      :on-success      [::confirm-refresh-playback-state]
                      :on-failure      [:practaid.interceptors/http-request-failure]}}))))

(rf/reg-event-db
  ::confirm-refresh-playback-state
  [check-db-spec-interceptor]
  (fn [db [_ state]]
    (assoc-in db [::state :external-playback-state] state)))




(rf/reg-event-fx
  ::takeover-playback
  [inject-store
   check-db-spec-interceptor]
  (fn [{:keys [db store]} _]
    (let [{player-state ::state} db
          access-token (:access-token store)
          {:keys [device-id player]} player-state]
      (when (and player device-id access-token)
        {:db (assoc db :is-taking-over-playback true)
         :fx [[:http-xhrio {:method :put
                            :uri "https://api.spotify.com/v1/me/player"
                            :headers {"Authorization" (str "Bearer " access-token)
                                      "Content-Type" "application/json"}
                            :body (.stringify js/JSON (clj->js {:device_ids [device-id]
                                                                :play true}))
                            :response-format (ajax/json-response-format {:keywords? true})
                            :on-success [::confirm-takeover-playback]
                            :on-failure [::deny-takeover-playback]}]]}))))

(rf/reg-event-fx
  ::confirm-takeover-playback
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    ;; TODO: also fetch playback state
    {:db (assoc-in db [::state :is-taking-over-playback] false)
     :fx [[:dispatch [:practaid.player/refresh-playback-state]]]}))

(rf/reg-event-fx
  ::deny-takeover-playback
  [check-db-spec-interceptor]
  (fn [{:keys [db]}]
    ;; TODO: test whether this can be done in :finally
    {:db (assoc-in db [::state :is-taking-over-playback] false)}))


(rf/reg-event-fx
  ::refresh-recently-played
  [inject-store
   check-db-spec-interceptor]
  (fn [{:keys [store]} _]
    {:fx [[:http-xhrio {:method :get
                        :uri "https://api.spotify.com/v1/me/player/recently-played"
                        :headers {"Authorization" (str "Bearer " (:access-token store))
                                  "Content-Type" "application/json"}
                        :response-format (ajax/json-response-format {:keywords? true})
                        :url-params {"limit" "1"}
                        :on-success [::confirm-refresh-recently-played]
                        :on-failure [:practaid.interceptors/http-request-failure]}]]}))

(rf/reg-event-db
  ::confirm-refresh-recently-played
  [check-db-spec-interceptor]
  (fn [db [_ recently-played]]
    (assoc-in db [::state :recently-played] recently-played)))
