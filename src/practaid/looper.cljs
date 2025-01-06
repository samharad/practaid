(ns practaid.looper
  "Could nearly be called `core`; this is the more specific functionality
  that constitutes this apps reason-d-atre (sp)."
  (:require [cljs.spec.alpha :as s]
            [re-frame.core :as rf]
            [practaid.db :as db]
            [practaid.player :as player]
            [practaid.common :refer [inject-store check-db-spec-interceptor check-store-spec-interceptor]]
            [ajax.core :as ajax]
            ["colorthief/dist/color-thief.mjs" :default ColorThief]))

(s/def ::loop-start-ms (s/nilable integer?))
(s/def ::loop-end-ms(s/nilable integer?))
(s/def ::loop-timeout-id (s/nilable any?))
(s/def ::album-colors (s/nilable (s/coll-of
                                   (s/coll-of integer?))))

(s/def ::state (s/keys :req-un [::loop-start-ms
                                ::loop-end-ms
                                ::loop-timeout-id
                                ::album-colors]))

(rf/reg-event-fx
  ::player-state-changed
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ old-state new-state]]
    (let [looper-state (::state db)
          {:keys [loop-timeout-id loop-start-ms loop-end-ms]} looper-state

          [old-track new-track] (map #(get-in % [:track_window :current_track])
                                     [old-state new-state])
          [prev-paused is-paused] (map :paused [old-state new-state])
          is-different-track (not= (:id old-track) (:id new-track))
          player-pos-ms (:position new-state)

          clear-loop-timeout (and (not prev-paused) is-paused loop-timeout-id)

          db (if clear-loop-timeout
               (assoc-in db [::state :loop-timeout-id] nil)
               db)

          set-loop-timeout (and prev-paused (not is-paused) loop-start-ms loop-end-ms)]

      {:db db
       :fx [(when is-different-track [:dispatch [::clear-looper]])
            (when clear-loop-timeout
              [:practaid.common/clear-timeout loop-timeout-id])
            (when set-loop-timeout
              [:practaid.common/set-timeout {:f          #(rf/dispatch [:practaid.looper/reset-looper])
                                             :timeout-ms (- loop-end-ms player-pos-ms)
                                             :on-set     [:practaid.looper/set-loop-timeout-id]}])]})))

(rf/reg-event-fx
  ::seek-player
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ pos-frac]]
    (let [item (db/playback-track db)
          track-duration-ms (:duration_ms item)]
      (if track-duration-ms
        {:fx [[:dispatch [::reset-looper (int (* track-duration-ms pos-frac))]]]}
        {}))))

(rf/reg-event-fx
  ::clear-looper
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [{looper-state ::state} db
          {:keys [loop-timeout-id]} looper-state
          clear-loop-timeout (when loop-timeout-id
                               [:practaid.common/clear-timeout loop-timeout-id])]
      {:db (-> db
               (assoc-in [::state :loop-start-ms] nil)
               (assoc-in [::state :loop-end-ms] nil)
               (assoc-in [::state :loop-timeout-id] nil))
       :fx [clear-loop-timeout]})))

(rf/reg-event-fx
  ::unset-loop
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [
          {looper-state ::state} db
          {:keys [loop-timeout-id]} looper-state
          clear-timeout (when loop-timeout-id
                          [:practaid.common/clear-timeout loop-timeout-id])]
      {:db (-> db
               (assoc-in [::state :loop-timeout-id] nil)
               (assoc-in [::state :loop-start-ms] nil)
               (assoc-in [::state :loop-end-ms] nil))
       :fx [clear-timeout]})))

(rf/reg-event-fx
  ::reset-looper
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ seek-to-ms]]
    (let [
          {looper-state ::state
           player-state ::player/state} db
          {:keys [loop-timeout-id loop-start-ms loop-end-ms]} looper-state
          {:keys [player]} player-state
          is-paused (db/is-paused db)
          clear-existing (when loop-timeout-id
                           [:practaid.common/clear-timeout loop-timeout-id])
          seek-player (when (or seek-to-ms (and player loop-start-ms loop-end-ms))
                        [::player/player {:player player
                                          :action [:seek (or seek-to-ms loop-start-ms)]
                                          :on-failure #(js/console.error %)}])
          set-timeout (when (and player loop-start-ms loop-end-ms (not is-paused))
                        [:practaid.common/set-timeout {:f #(rf/dispatch [::reset-looper])
                                                       ;; TODO?
                                                       :timeout-ms (- loop-end-ms (or seek-to-ms loop-start-ms))
                                                       :on-set [::set-loop-timeout-id]}])]
      {:db (assoc-in db [::state :loop-timeout-id] nil)
       :fx [clear-existing
            seek-player
            set-timeout]})))

(rf/reg-event-db
  ::set-loop-timeout-id
  (fn [db [_ timeout-id]]
    (assoc-in db [::state :loop-timeout-id] timeout-id)))

(rf/reg-event-fx
  ::toggle-play
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [{player-state ::player/state} db
          {:keys [player]} player-state
          is-paused (db/is-paused db)
          is-paused' (not is-paused)
          action (if is-paused' [:pause] [:resume])
          update-player [::player/player {:player player
                                          :action action
                                          :on-failure #(js/console.error "Failed to toggle!")}]]
      {:db db
       :fx [update-player]})))

(rf/reg-event-fx
  ::next-track
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [player (:player (::player/state db))]
      {::player/player {:player player
                        :action [:next-track]
                        :on-success #(println "Next track!")
                        :on-failure #(js/console.error "Failed to next-track!")}})))

(rf/reg-event-fx
  ::prev-track
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [player (:player (::player/state db))]
      {::player/player {:player player
                        :action [:previous-track]
                        :on-success #(println "Prev track!")
                        :on-failure #(js/console.error "Failed to prev-track!")}})))

(def min-loop-length-ms 2000)

(rf/reg-event-db
  ::attempt-set-loop-start
  [check-db-spec-interceptor]
  (fn [db [_ start-ms end-ms]]
    (assoc-in db [::state :loop-start-ms] (min (int start-ms)
                                               (- end-ms min-loop-length-ms)))))

(rf/reg-event-db
  ::attempt-set-loop-end
  [check-db-spec-interceptor]
  (fn [db [_ end-ms start-ms]]
    (assoc-in db [::state :loop-end-ms] (max (int end-ms)
                                             (+ start-ms min-loop-length-ms)))))

(rf/reg-event-db
  ::attempt-increment-loop-start
  [check-db-spec-interceptor]
  (fn [db [_ amt]]
    (let [{looper-state ::state} db
          {:keys [loop-start-ms loop-end-ms]} looper-state
          item (db/playback-track db)
          track-duration-ms (:duration_ms item)
          ceiling (if loop-end-ms
                    (- loop-end-ms min-loop-length-ms)
                    track-duration-ms)]
      (assoc db :loop-start-ms (max 0 (min (+ loop-start-ms amt)
                                           ceiling))))))

(rf/reg-event-db
  ::attempt-increment-loop-end
  [check-db-spec-interceptor]
  (fn [db [_ amt]]
    (let [{looper-state ::state} db
          {:keys [loop-start-ms loop-end-ms]} looper-state
          item (db/playback-track db)
          track-duration-ms (:duration_ms item)
          floor (if loop-start-ms
                  (+ loop-start-ms min-loop-length-ms)
                  0)]
      (assoc db :loop-end-ms (min track-duration-ms (max (+ (or loop-end-ms track-duration-ms) amt)
                                                         floor))))))




(rf/reg-event-fx
  ::album-cover-img-loaded
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ img-element]]
    (let [palette (-> (new ColorThief)
                      (. getPalette img-element 2)
                      (js->clj))]
      {:db (assoc-in db [::state :album-colors] palette)})))




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


(rf/reg-event-fx
  ::initialize-looper-page
  [check-db-spec-interceptor]
  (fn [_ _]
    {:fx [
          [:practaid.player/initialize-spotify-sdk nil]
          ;; TODO: store the outcome ID
          [:practaid.common/set-interval {:f #(rf/dispatch [:practaid.player/refresh-playback-state])
                                          :interval-ms 5000}]
          [:dispatch [:practaid.player/refresh-recently-played]]]}))
