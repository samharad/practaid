(ns practaid.events
  (:require
    [re-frame.core :as rf]
    [practaid.db :as db]
    [day8.re-frame.http-fx]
    [akiroz.re-frame.storage :refer [reg-co-fx!]]
    [ajax.core :as ajax]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [cljs.core.async :refer [go <!]]
    [cljs.core.async.interop :refer-macros [<p!]]
    [cljs.spec.alpha :as s]
    ["colorthief/dist/color-thief.mjs" :default ColorThief]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interceptors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn check-and-throw
  "Throws an exception if `x` doesn't match the Spec `a-spec`."
  ;; TODO: make the console error more legible
  [a-spec x]
  (when-not (s/valid? a-spec x)
    (cljs.pprint/pprint (->> (s/explain-data a-spec x)
                             :cljs.spec.alpha/problems
                             (map #(dissoc % :val))))
    (throw (ex-info (str "spec check failed: " (s/explain-str a-spec x)) {}))))

(def check-db-spec-interceptor (rf/after (partial check-and-throw :practaid.db/db)))

(reg-co-fx! :practaid
            {:fx :store
             :cofx :store})

(s/def :store/access-token (s/nilable string?))
(s/def :store/expires-at (s/nilable string?))
(s/def :store/refresh-token (s/nilable string?))
(s/def ::store (s/nilable (s/keys :opt-un [:store/access-token
                                           :store/expires-at
                                           :store/refresh-token])))

(def check-store-spec-interceptor
  (rf/->interceptor
    :id :check-store-spec-interceptor
    :after (fn [context]
             (check-and-throw ::store (get-in context [:coeffects :store]))
             context)))

(def inject-store (rf/inject-cofx :store))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cofx, Events, Effects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Generic ----------------------------------------------

(rf/reg-event-fx
  ::http-request-failure
  [check-db-spec-interceptor]
  (fn [_ [_ result]]
    (let [{:keys [status]} result]
      (if (#{401} status)
        {:fx [[:dispatch [::reset-app-completely]]]}
        ;; Innocent side effect
        (js/console.error result)))))

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
  ::refresh-recently-played
  [inject-store
   check-db-spec-interceptor]
  (fn [{:keys [db store]} _]
    {:fx [[:http-xhrio {:method :get
                        :uri "https://api.spotify.com/v1/me/player/recently-played"
                        :headers {"Authorization" (str "Bearer " (:access-token store))
                                  "Content-Type" "application/json"}
                        :response-format (ajax/json-response-format {:keywords? true})
                        :url-params {"limit" "1"}
                        :on-success [::confirm-refresh-recently-played]
                        :on-failure [::http-request-failure]}]]}))

(rf/reg-event-db
  ::confirm-refresh-recently-played
  [check-db-spec-interceptor]
  (fn [db [_ recently-played]]
    (assoc db :recently-played recently-played)))

(rf/reg-event-fx
  ::refresh-playback-state
  [inject-store
   check-db-spec-interceptor]
  (fn [{:keys [store]} _]
    (let [access-token (:access-token store)]
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
  [check-db-spec-interceptor]
  (fn [db [_ state]]
    (assoc db :external-playback-state state)))

(rf/reg-event-fx
  ::takeover-playback
  [inject-store
   check-db-spec-interceptor]
  (fn [{:keys [db store]} _]
    (let [{:keys [player device-id ]} db
          access-token (:access-token store)]
      (when (and player device-id access-token)
        {:db (assoc db :is-taking-over-playback true)
         :http-xhrio {:method :put
                      :uri "https://api.spotify.com/v1/me/player"
                      :headers {"Authorization" (str "Bearer " access-token)
                                "Content-Type" "application/json"}
                      :body (.stringify js/JSON (clj->js {:device_ids [device-id]
                                                          :play true}))
                      :response-format (ajax/json-response-format {:keywords? true})
                      ;; TODO
                      :on-success [::confirm-takeover-playback]
                      :on-failure [::deny-takeover-playback]}}))))

(rf/reg-event-fx
  ::confirm-takeover-playback
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    ;; TODO: also fetch playback state
    {:db (assoc db :is-taking-over-playback false)
     :fx [[:dispatch [::refresh-playback-state]]]}))

(rf/reg-event-fx
  ::deny-takeover-playback
  [check-db-spec-interceptor]
  (fn [{:keys [db]}]
    ;; TODO: test whether this can be done in :finally
    {:db (assoc db :is-taking-over-playback false)}))

(rf/reg-event-fx
  ::refresh-track-analysis
  [inject-store
   check-db-spec-interceptor]
  (fn [{:keys [db store]} _]
    (let [{:keys [external-playback-state]} db
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
                            :on-failure [::http-request-failure]}]]}))))

(rf/reg-event-db
  ::confirm-track-analysis
  [check-db-spec-interceptor]
  (fn [db [_ track-analysis]]
    (assoc db :track-analysis track-analysis)))

(rf/reg-event-db
  ::increment-player-pos-ms
  [check-db-spec-interceptor]
  (fn [db [_ increment-val]]
    (update db :player-pos-ms + increment-val)))


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
  ::seek-player
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ pos-frac]]
    (let [item (db/playback-track db)
          track-duration-ms (:duration_ms item)]
      (if track-duration-ms
        {:fx [[:dispatch [::reset-looper (int (* track-duration-ms pos-frac))]]]}
        {}))))

(rf/reg-event-fx
  ::initialize-looper-page
  [check-db-spec-interceptor]
  (fn [_ _]
    {:fx [
          [:practaid.player/initialize-spotify-sdk nil]
          ;; TODO: store the outcome ID
          [::set-interval {:f #(rf/dispatch [::refresh-playback-state])
                           :interval-ms 5000}]
          [:dispatch [::refresh-track-analysis]]
          [:dispatch [::refresh-recently-played]]]}))

(rf/reg-event-db
  ::set-player-pos-query-interval-id
  [check-db-spec-interceptor]
  (fn [db [_ id]]
    (assoc db :player-pos-query-interval-id id)))

(rf/reg-event-fx
  ::clear-looper
  [check-db-spec-interceptor]
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
  ::unset-loop
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [{:keys [loop-timeout-id]} db
          clear-timeout (when loop-timeout-id
                          [::clear-timeout loop-timeout-id])]
      {:db (assoc db :loop-timeout-id nil
                     :loop-start-ms nil
                     :loop-end-ms nil)
       :fx [clear-timeout]})))

(rf/reg-event-fx
  ::reset-looper
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ seek-to-ms]]
    (let [{:keys [loop-start-ms loop-end-ms loop-timeout-id player]} db
          is-paused (db/is-paused db)
          clear-existing (when loop-timeout-id
                           [::clear-timeout loop-timeout-id])
          seek-player (when (or seek-to-ms (and player loop-start-ms loop-end-ms))
                        [:practaid.player/player {:player player
                                                  :action [:seek (or seek-to-ms loop-start-ms)]
                                                  :on-failure #(js/console.error %)}])
          set-timeout (when (and player loop-start-ms loop-end-ms (not is-paused))
                        [::set-timeout {:f #(rf/dispatch [::reset-looper])
                                        ;; TODO?
                                        :timeout-ms (- loop-end-ms (or seek-to-ms loop-start-ms))
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
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [{:keys [player]} db
          is-paused (db/is-paused db)
          is-paused' (not is-paused)
          action (if is-paused' [:pause] [:resume])
          update-player [:practaid.player/player {:player player
                                                  :action action
                                                  :on-failure #(js/console.error "Failed to toggle!")}]]
      {:db db
       :fx [update-player]})))

(rf/reg-event-fx
  ::next-track
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [player (:player db)]
      {:practaid.player/player {:player player
                                :action [:next-track]
                                :on-success #(println "Next track!")
                                :on-failure #(js/console.error "Failed to next-track!")}})))

(rf/reg-event-fx
  ::prev-track
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [player (:player db)]
      {:practaid.player/player {:player player
                                :action [:previous-track]
                                :on-success #(println "Prev track!")
                                :on-failure #(js/console.error "Failed to prev-track!")}})))

(def min-loop-length-ms 2000)

(rf/reg-event-db
  ::attempt-set-loop-start
  [check-db-spec-interceptor]
  (fn [db [_ start-ms end-ms]]
    (assoc db :loop-start-ms (min (int start-ms)
                                  (- end-ms min-loop-length-ms)))))

(rf/reg-event-db
  ::attempt-set-loop-end
  [check-db-spec-interceptor]
  (fn [db [_ end-ms start-ms]]
    (assoc db :loop-end-ms (max (int end-ms)
                                (+ start-ms min-loop-length-ms)))))

(rf/reg-event-db
  ::attempt-increment-loop-start
  [check-db-spec-interceptor]
  (fn [db [_ amt]]
    (let [{:keys [loop-start-ms loop-end-ms]} db
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
    (let [{:keys [loop-start-ms loop-end-ms]} db
          item (db/playback-track db)
          track-duration-ms (:duration_ms item)
          floor (if loop-start-ms
                  (+ loop-start-ms min-loop-length-ms)
                  0)]
      (assoc db :loop-end-ms (min track-duration-ms (max (+ (or loop-end-ms track-duration-ms) amt)
                                                         floor))))))

(rf/reg-event-db
  ::set-is-seeking
  [check-db-spec-interceptor]
  (fn [db [_ b]]
    (assoc db :is-seeking b)))