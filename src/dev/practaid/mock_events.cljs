(ns dev.practaid.mock-events
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as reg]
            [clojure.string :as str]))

;; TODO can this ns be loaded based on a dev flag... separate mock build...?

(def mock-item {:id "mock-item-id"
                :album {:images [{:url "/mock/image/album-cover.jpg"}]
                        :name "Mock Album"}
                :artists [{:name "Mock Artist"} {:name "Another Mock Artist"}]
                :name "Mock Track"
                :duration_ms 60000})

;; TODO at this point does it make as much sense to
;; create a JavaScript class to wrap this?
(def mock-player-state (atom {:position 20000
                              :track_window {:current_track mock-item}
                              :paused true
                              :last-play-time (js/Date.)}))

(defn mock-cofx [id mocker]
  (let [existing (-> @reg/kind->id->handler
                     :cofx
                     (get id))]
    (assert existing)
    (rf/reg-cofx
      id
      (mocker existing))))

(mock-cofx
  :practaid.events/spotify-sdk-object
  (fn [real-injector]
    (fn [cofx]
      (assoc cofx :spotify-sdk nil))))

(defn mock-fx [id mocker]
  (let [existing (-> @reg/kind->id->handler
                     :fx
                     (get id))]
    (assert existing)
    (rf/reg-fx
      id
      (mocker existing))))

(mock-fx
  :practaid.events/assign-url
  (fn [real-handler]
    (fn [url]
      (println "Mock Handler!")
      (if (str/starts-with? url "https://accounts.spotify.com/authorize")
        (rf/dispatch [:practaid.events/confirm-complete-auth-flow {:access_token "mock-token"
                                                                   :expires_in "3600"}])
        (real-handler url)))))

(mock-fx
  :practaid.events/initialize-spotify-sdk
  (fn [real-handler]
    (fn [_]
      (rf/dispatch [:practaid.events/player-instantiated {}])
      (rf/dispatch [:practaid.events/player-ready {:device_id "mock-device-id"}]))))

(mock-fx
  :http-xhrio
  (fn [real-handler]
    (fn [req]
      (let [{:keys [method uri]} req]
        (cond
          (and (= method :get)
               (str/starts-with? uri "https://api.spotify.com/v1/me/player"))
          (rf/dispatch [:practaid.events/confirm-refresh-playback-state
                        {:item mock-item
                         :device {:id "mock-device-id"}}])

          (and (= method :get)
               (str/starts-with? uri "https://api.spotify.com/v1/me/player/recently-played"))
          (rf/dispatch [:practaid.events/confirm-refresh-recently-played
                        {:items [{:track mock-item}]}])

          (and (= method :get)
               (str/starts-with? uri "https://api.spotify.com/v1/audio-analysis"))
          (rf/dispatch [:practaid.events/confirm-track-analysis
                        {:segments [{:duration 20000
                                     :start 0
                                     :loudness_max 0}]}])

          :else (real-handler req))))))

(mock-fx
  :practaid.events/player
  (fn [real-handler]
    (fn [{:keys [action]}]
      (let [[action-type & args] action]
        (case action-type
          :pause (rf/dispatch [:practaid.events/player-state-changed
                               (swap! mock-player-state
                                      #(-> %
                                           (assoc :paused true)
                                           (assoc :position (- (js/Date.) (:last-play-time %)))))])
          :resume (rf/dispatch [:practaid.events/player-state-changed
                                (swap! mock-player-state
                                       #(-> %
                                            (assoc :paused false)
                                            (assoc :last-play-time (js/Date.))))])
          :seek (rf/dispatch [:practaid.events/player-state-changed
                              (swap! mock-player-state
                                     #(assoc % :position (first args)))])
          ;; Same for next and prev
          (rf/dispatch [:practaid.events/player-state-changed
                        (swap! mock-player-state
                               #(-> %
                                    (assoc :position 0)
                                    (assoc-in [:track_window :current_track]
                                              (assoc mock-item
                                                :id (str (rand-int 1000000))
                                                :name (str "Mock Track " (rand-int 1000))))))]))))))

(comment
  (rf/dispatch [:practaid.events/player-state-changed
                (clj->js @mock-player-state)]))




