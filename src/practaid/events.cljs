(ns practaid.events
  (:require
    [re-frame.core :as rf]
    [practaid.db :as db]
    [day8.re-frame.http-fx]
    [akiroz.re-frame.storage :refer [reg-co-fx!]]
    [ajax.core :as ajax]
    [practaid.auth :as auth]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [cljs.core.async :refer [go <!]]
    [cljs.core.async.interop :refer-macros [<p!]]
    [re-pressed.core :as rp])
  (:require ["spotify-web-api-js" :as SpotifyWebApi]
            [cljs.spec.alpha :as s]))

(defn expires-at [now-date expires-in-seconds]
  (.toISOString (js/Date. (-> now-date
                              .getTime
                              (+ (* 1000 expires-in-seconds))))))


;; TODO move me
(def playback-pos-refresh-interval-ms 200)


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

(def space-key-code 32)
(def p-key-code 80)
(def enter-key-code 13)
(def mac-enter-key-code 3)
(def left-key-code 37)
(def right-key-code 39)
(def up-key-code 38)
(def down-key-code 40)

(def keypress-looper-step-ms 10)

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
       :fx [[:dispatch [::rp/add-keyboard-event-listener "keydown"]]
            [:dispatch [::rp/add-keyboard-event-listener "keyup"]]
            init-looper-page
            [:dispatch [::rp/set-keyup-rules {:event-keys [[[::reset-looper nil]
                                                            [{:keyCode left-key-code}]
                                                            [{:keyCode right-key-code}]
                                                            [{:keyCode up-key-code}]
                                                            [{:keyCode down-key-code}]]]
                                              :always-listen-keys [{:keyCode left-key-code}
                                                                   {:keyCode right-key-code}
                                                                   {:keyCode up-key-code}
                                                                   {:keyCode down-key-code}]}]]
            [:dispatch [::rp/set-keydown-rules {:event-keys [[[::toggle-play]
                                                              [{:keyCode space-key-code}]
                                                              [{:keyCode p-key-code}]]

                                                             [[::reset-looper nil]
                                                              [{:keyCode enter-key-code}]
                                                              [{:keyCode mac-enter-key-code}]]

                                                             [[::attempt-increment-loop-start (- keypress-looper-step-ms)]
                                                              [{:keyCode left-key-code}]]
                                                             [[::attempt-increment-loop-start keypress-looper-step-ms]
                                                              [{:keyCode right-key-code}]]
                                                             [[::attempt-increment-loop-end (- keypress-looper-step-ms)]
                                                              [{:keyCode down-key-code}]]
                                                             [[::attempt-increment-loop-end keypress-looper-step-ms]
                                                              [{:keyCode up-key-code}]]
                                                             ,]
                                                :always-listen-keys [{:keyCode space-key-code}
                                                                     {:keyCode p-key-code}
                                                                     {:keyCode enter-key-code}
                                                                     {:keyCode mac-enter-key-code}
                                                                     {:keyCode left-key-code}
                                                                     {:keyCode right-key-code}
                                                                     {:keyCode up-key-code}
                                                                     {:keyCode down-key-code}]
                                                :prevent-default-keys [{:keyCode space-key-code}
                                                                       {:keyCode p-key-code}
                                                                       {:keyCode enter-key-code}
                                                                       {:keyCode mac-enter-key-code}
                                                                       {:keyCode left-key-code}
                                                                       {:keyCode right-key-code}
                                                                       {:keyCode up-key-code}
                                                                       {:keyCode down-key-code}]}]]]})))

(rf/reg-event-fx
  ::reset-app-completely
  [inject-store
   check-db-spec-interceptor
   check-store-spec-interceptor]
  (fn [_ _]
    {:fx [[:store {}]
          [::reload-page nil]]}))



;; Routing -----------------------------------------------

(rf/reg-event-fx
  ::navigate
  [check-db-spec-interceptor]
  (fn [_cofx [_ route]]
    {::navigate! route}))

(rf/reg-event-db
  ::navigated
  [check-db-spec-interceptor]
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

(rf/reg-fx
  ::reload-page
  (fn [_]
    (.reload js/location)))



;; Auth ---------------------------------------------------

(rf/reg-event-fx
  ::prepare-for-oauth
  [(rf/inject-cofx ::location-origin)]
  (fn [{:keys [location-origin]} _]
    {::create-initial-auth-data location-origin}))

(rf/reg-event-fx
  ::initiate-oauth
  [check-db-spec-interceptor]
  (fn [_ [_ {:keys [code-verifier nonce url]}]]
    (let [auth-data {:nonce nonce
                     :code-verifier code-verifier}]
      {:store auth-data
       ::assign-url url})))

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
      {:http-xhrio {:method :post
                    :uri "https://accounts.spotify.com/api/token"
                    :response-format (ajax/json-response-format {:keywords? true})
                    :body (js/URLSearchParams. (clj->js {"client_id" auth/client-id
                                                         "grant_type" "authorization_code"
                                                         "code" code
                                                         "redirect_uri" (str location-origin "/callback")
                                                         "code_verifier" code-verifier}))
                    :on-success [::confirm-complete-auth-flow]
                    :on-failure [::http-request-failure]}})))

(rf/reg-event-fx
  ::confirm-complete-auth-flow
  [(rf/inject-cofx :store)
   check-db-spec-interceptor]
  (fn [{:keys [db store]} [_ {:keys [access_token expires_in refresh_token token_type scope]}]]
    {:db (assoc db :is-authorized true)
     :fx [[:store (assoc store :access-token access_token
                               :expires-at (expires-at (js/Date.) expires_in))]
          [:dispatch [::initialize-looper-page]]
          [:dispatch [::navigate :routes/home]]]}))

(rf/reg-fx
  ::create-initial-auth-data
  (fn [location-origin]
    (go
      (let [{:keys [code-verifier nonce url]} (<! (auth/go-initial-auth-flow location-origin))]
        (rf/dispatch [::initiate-oauth {:code-verifier code-verifier
                                        :nonce            nonce
                                        :url              url}])))))



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

(rf/reg-fx
  ::instantiate-and-initialize-player
  (fn [{:keys [^js spotify-sdk]}]
    (let [player (new (.-Player spotify-sdk)
                      (clj->js {:name "PractAid"
                                :getOAuthToken (fn [callback]
                                                 (rf/dispatch [::player-requests-access-token callback]))
                                :volume 0.5}))
          _ (.addListener player "autoplay_failed"
                          ;; TODO move me
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
                         [:dispatch [::clear-looper]])
          db (assoc db :playback-state state
                       :player-pos-ms player-pos-ms)
          is-paused (db/is-paused db)
          set-pos-interval (when (and (or prev-paused (nil? prev-paused))
                                      (not is-paused))
                             [::set-interval {:f           #(rf/dispatch [::increment-player-pos-ms playback-pos-refresh-interval-ms])
                                              :interval-ms playback-pos-refresh-interval-ms
                                              :on-set      [::set-player-pos-query-interval-id]}])
          clear-pos-interval (when (and (not prev-paused) is-paused player-pos-query-interval-id)
                               (println "Clearing interval")
                               [::clear-interval player-pos-query-interval-id])
          db (if clear-pos-interval
               (assoc db :player-pos-query-interval-id nil)
               db)
          clear-loop-timeout (when (and (not prev-paused) is-paused loop-timeout-id)
                               [::clear-timeout loop-timeout-id])
          db (if clear-loop-timeout
               (assoc db :loop-timeout-id nil)
               db)
          set-loop-timeout (when (and prev-paused (not is-paused) loop-start-ms loop-end-ms)
                             [::set-timeout {:f          #(rf/dispatch [::reset-looper])
                                             :timeout-ms (- loop-end-ms player-pos-ms)
                                             :on-set     [::set-loop-timeout-id]}])]
      {:db db
       :fx [(when is-different-track [:dispatch [::refresh-track-analysis]])
            clear-pos-interval
            set-pos-interval
            clear-looper
            clear-loop-timeout
            set-loop-timeout]})))

(rf/reg-event-db
  ::increment-player-pos-ms
  [check-db-spec-interceptor]
  (fn [db [_ increment-val]]
    (update db :player-pos-ms + increment-val)))

(rf/reg-event-fx
  ::player-requests-access-token
  [inject-store
   check-db-spec-interceptor
   check-store-spec-interceptor]
  (fn [{:keys [store]} [_ callback]]
    (let [{:keys [access-token]} store
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


;; Looper page---------------------------------------------

(rf/reg-event-fx
  ::seek-player
  [check-db-spec-interceptor]
  (fn [{:keys [db]} [_ pos-frac]]
    (let [item (db/playback-track db)
          track-duration-ms (:duration_ms item)]
      (if track-duration-ms
        {:fx [[:dispatch [::reset-looper (* track-duration-ms pos-frac)]]]}
        {}))))

(rf/reg-event-fx
  ::initialize-looper-page
  [check-db-spec-interceptor]
  (fn [_ _]
    {:fx [
          ;[:dispatch [::navigate :routes/looper]]
          [::initialize-spotify-sdk nil]
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
                        [::player {:player player
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
          update-player [::player {:player player
                                   :action action
                                   :on-failure #(js/console.error "Failed to toggle!")}]]
      {:db db
       :fx [update-player]})))

(rf/reg-event-fx
  ::next-track
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [player (:player db)]
      {::player {:player player
                 :action [:next-track]
                 :on-success #(println "Next track!")
                 :on-failure #(js/console.error "Failed to next-track!")}})))

(rf/reg-event-fx
  ::prev-track
  [check-db-spec-interceptor]
  (fn [{:keys [db]} _]
    (let [player (:player db)]
      {::player {:player player
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

(rf/reg-fx
  ::player
  (fn [{:keys [player action on-success on-failure]}]
    (let [[action-type & args] action
          action (action-type {:resume #(.resume player)
                               :pause #(.pause player)
                               :seek #(do
                                        (rf/dispatch [::set-is-seeking true])
                                        (.then (.seek player (first args))
                                               (rf/dispatch [::set-is-seeking false])))
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