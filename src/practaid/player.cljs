(ns practaid.player
  "Concerns related to the Spotify player object."
  (:require [re-frame.core :as rf]
            [practaid.events :refer [inject-store check-db-spec-interceptor check-store-spec-interceptor]]
            [practaid.db :as db]))

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
    (assoc db :player player)))

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
               [[:dispatch [::reset-app-completely]]]
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
      (assoc db :device-id device_id))))

;; https://developer.spotify.com/documentation/web-playback-sdk/reference/#object-web-playback-player
(rf/reg-event-db
  ::player-not-ready
  [check-db-spec-interceptor]
  (fn [db [_ js-player-obj]]
    ;; TODO: I think state might have a device ID,
    ;;   so this should be tracked in a separate :status field
    (assoc db :device-id nil)))

;; TODO CLEAN ME
(rf/reg-event-fx
  ::player-state-changed
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ js-state]]
    (let [{:keys [player loop-timeout-id loop-start-ms loop-end-ms player-pos-query-interval-id]} db
          prev-paused (db/is-paused db)
          state (js->clj js-state :keywordize-keys true)
          player-pos-ms (get-in state [:position])
          old-track (db/playback-track db)
          new-track (get-in state [:track_window :current_track])
          is-different-track (not= (:id old-track) (:id new-track))
          clear-looper (when is-different-track
                         [:dispatch [:practaid.events/clear-looper]])
          db (assoc db :playback-state state
                       :player-pos-ms player-pos-ms)
          is-paused (db/is-paused db)
          set-pos-interval (when (and (or prev-paused (nil? prev-paused))
                                      (not is-paused))
                             [:practaid.events/set-interval {:f           #(rf/dispatch [:practaid.events/increment-player-pos-ms playback-pos-refresh-interval-ms])
                                                             :interval-ms playback-pos-refresh-interval-ms
                                                             :on-set      [:practaid.events/set-player-pos-query-interval-id]}])
          clear-pos-interval (when (and (not prev-paused) is-paused player-pos-query-interval-id)
                               (println "Clearing interval")
                               [:practaid.events/clear-interval player-pos-query-interval-id])
          db (if clear-pos-interval
               (assoc db :player-pos-query-interval-id nil)
               db)
          clear-loop-timeout (when (and (not prev-paused) is-paused loop-timeout-id)
                               [:practaid.events/clear-timeout loop-timeout-id])
          db (if clear-loop-timeout
               (assoc db :loop-timeout-id nil)
               db)
          set-loop-timeout (when (and prev-paused (not is-paused) loop-start-ms loop-end-ms)
                             [:practaid.events/set-timeout {:f          #(rf/dispatch [:practaid.events/reset-looper])
                                                            :timeout-ms (- loop-end-ms player-pos-ms)
                                                            :on-set     [:practaid.events/set-loop-timeout-id]}])]
      {:db db
       :fx [(when is-different-track [:dispatch [:practaid.events/refresh-track-analysis]])
            clear-pos-interval
            set-pos-interval
            clear-looper
            clear-loop-timeout
            set-loop-timeout]})))

;; TODO
(rf/reg-fx
  ::player
  (fn [{:keys [player action on-success on-failure]}]
    (let [[action-type & args] action
          action (action-type {:resume #(.resume player)
                               :pause #(.pause player)
                               :seek #(do
                                        (rf/dispatch [:practaid.events/set-is-seeking true])
                                        (.then (.seek player (first args))
                                               (rf/dispatch [:practaid.events/set-is-seeking false])))
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
