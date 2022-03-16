(ns practaid.views.looper-page
  (:require [practaid.views.common :refer [page-wrapper loading-ellipses]]
            [spade.core :refer [defclass defkeyframes]]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [practaid.player :refer [playback-pos-refresh-interval-ms]]
            ["multiple.js" :as Multiple]))

(defclass playback-metadata-style []
  {:display "flex"
   :justify-content "flex-start"
   :padding "20px"
   :max-height "400px"}
  [:div {:flex "1 1 0"}]
  [:.album-cover-container {:max-height "100%"
                            :max-width "300px"}]
                            ;:min-width "100px"}]
  [:.album-cover-image {:max-width "100%"
                        :max-height "100%"}]
  [:.text-metadata-container {:align-self "center"
                              :padding "0 20px"
                              :max-width "400px"}
   [:.track-name {:font-size "40px"
                  :font-weight "bolder"}]
   [:img.spotify-logo {:max-width "200px"}]
   [:artist-names {}]])

(defn playback-metadata [{:keys [album-cover-url album-name artist-names track-name track-id]}]
  [:div {:class (playback-metadata-style)}
   (and album-cover-url
        [:div.album-cover-container
         [:img.album-cover-image {:src album-cover-url
                                  :crossOrigin "Anonymous"
                                  :on-load #(rf/dispatch [:practaid.looper/album-cover-img-loaded
                                                          (-> js/document
                                                              (. querySelector "img.album-cover-image"))])}]])
   [:div.text-metadata-container
    [:a {:href (str "https://open.spotify.com/track/" track-id)
         :title "Play on Spotify"}
     [:img.spotify-logo {:src "/image/Spotify_Logo_RGB_Green.png"}]]
    [:div.track-name track-name]
    [:div.artist-names (str/join ", " artist-names)]
    [:div.album-nam album-name]]])




(defclass playback-controls-style []
  {:display "flex" :align-items "center" :justify-content "center"}
  [:.button-container {:display "flex" :justify-content "center" :padding "0 10px"}])

(defn playback-controls [{:keys [on-toggle-play on-next on-prev disabled]}]
  [:div {:class (playback-controls-style)}
   [:div.button-container
    [:button {:disabled disabled :on-click on-prev}
     "Prev"]]
   [:div.button-container
    [:button {:disabled disabled :on-click on-toggle-play}
     "Play/Pause"]]
   [:div.button-container
    [:button {:disabled disabled :on-click on-next}
     "Next"]]])




(defclass waveform-pos-marker-style []
  {:margin 0
   :padding 0
   :position "absolute"
   :width "3px"
   :pointer-events "none"
   :height "100%"})

(defn waveform-pos-marker [{:keys [location color]}]
  [:div {:class (waveform-pos-marker-style)
         :style {:left (str (* location 100) "%")
                 :background-color color}}])

(defn playback-pos-marker [{:keys [location]}]
  [:div {:class (waveform-pos-marker-style)
         :style {:left (str (* location 100) "%")
                 :background-color "green"}}])




(defclass waveform-style []
  {:margin 0
   :padding 0
   :display "flex"
   :justify-content "space-evenly"
   :align-items "center"
   :height "100%"
   :width "100%"
   :position "relative"
   :border "1px solid black"})

(defclass wave-style []
  {:margin 0
   :padding 0
   :color "red"
   :background-color "red"
   :width "1px"
   :pointer-events "none"})






(defn waveform [{:keys [loudnesses loop-start-frac loop-end-frac player-pos-frac handle-click album-colors]}]
  (let [rgbs (map #(str "rgb(" (str/join ", " %) ")")
                  album-colors)
        background (when rgbs (str "linear-gradient(to right," (str/join ", " rgbs)  ")"))
        waveform-style (waveform-style)
        _ (when rgbs (new ^js Multiple (clj->js {:selector "div.wave"
                                                 :background background})))]
    [:div {:class waveform-style
           :on-click handle-click}
     [waveform-pos-marker {:location loop-start-frac :color "black"}]
     [waveform-pos-marker {:location loop-end-frac :color "black"}]
     [playback-pos-marker {:location player-pos-frac}]
     (for [[i loudness] (map-indexed vector loudnesses)]
       [:div.wave {:key i
                   :class (wave-style)
                   ;; TODO move me?
                   :style {:height (str (int (* loudness 100)) "%")}}])]))







(defclass slider-style []
  {:width "100%"}
  [:input {:width "100%" :transform "scale(1.008)"}])

(defn slider [{:keys [min max value on-change on-commit disabled]}]
  [:div {:class (slider-style)}
   [:input {:type "range"
            :step "1"
            :min (str min)
            :max (str max)
            :value (str value)
            :on-change on-change
            :on-mouse-up on-commit
            :disabled disabled}]])








(defclass looper-page-style []
  {:display "flex"
   :flex-direction "column"}
  [:div {:margin "2px 0"}]
  [:.unset-loop {:text-align "center"
                 :align-self "center"}]
  [:.connection-status {:text-align "center"
                        :align-self "center"}
   [:.connected {:color "green"}]
   [:.connecting {:color "yellow"}]
   [:.button-wrapper {:display "flex"
                      :justify-content "center"}]]
  [:.field {:height "100px"}])

(defn looper-page []
  (let [device-id @(rf/subscribe [:practaid.subs/device-id])
        is-taking-over-playback @(rf/subscribe [:practaid.subs/is-taking-over-playback])
        playback-md @(rf/subscribe [:practaid.subs/playback-metadata])
        track @(rf/subscribe [:practaid.subs/track])
        is-playback-ours @(rf/subscribe [:practaid.subs/is-playback-ours])
        loudnesses @(rf/subscribe [:practaid.subs/loudness-samples])
        track-duration-ms @(rf/subscribe [:practaid.subs/track-duration-ms])
        loop-start-ms @(rf/subscribe [:practaid.subs/loop-start-ms])
        loop-end-ms @(rf/subscribe [:practaid.subs/loop-end-ms])
        defaulted-loop-start-ms @(rf/subscribe [:practaid.subs/defaulted-loop-start-ms])
        defaulted-loop-end-ms @(rf/subscribe [:practaid.subs/defaulted-loop-end-ms])
        defaulted-loop-start-frac @(rf/subscribe [:practaid.subs/defaulted-loop-start-frac])
        defaulted-loop-end-frac @(rf/subscribe [:practaid.subs/defaulted-loop-end-frac])
        player-pos-frac @(rf/subscribe [:practaid.subs/player-pos-frac])
        album-colors @(rf/subscribe [:practaid.subs/album-colors])
        ^js player @(rf/subscribe [:practaid.subs/player])]
    [page-wrapper
     [:div {:class (looper-page-style)}
      [:div.connection-status
       ;; TODO make me dumber
       (and (not device-id) [:h3.connecting "Connecting"])
       (and device-id
            (not is-playback-ours)
            [:div
             [:h3.connected "Connected"]
             [:div.button-wrapper
              [:button {:on-click #(do
                                     ;; NOTE: some browsers have autoplay restrictions which prevent audio playback
                                     ;; except in response to a user action; so, in order to start playback upon
                                     ;; taking over playback control, we need to call this function directly from
                                     ;; within this click handler; see:
                                     ;; https://developer.spotify.com/documentation/web-playback-sdk/reference/#api-spotify-player-activateelement
                                     (.activateElement player)
                                     (rf/dispatch [:practaid.player/takeover-playback]))}
               "Take over playback" (and is-taking-over-playback [loading-ellipses])]]])]
      (and playback-md
           [playback-metadata playback-md])
      (and track
           [playback-controls {:disabled (not is-playback-ours)
                               :on-toggle-play #(rf/dispatch [:practaid.looper/toggle-play])
                               :on-next #(rf/dispatch [:practaid.looper/next-track])
                               :on-prev #(rf/dispatch [:practaid.looper/prev-track])}])
      (and loudnesses
           [:div.field
            [slider {:min       0
                     :max       track-duration-ms
                     :value     defaulted-loop-start-ms
                     :on-change #(rf/dispatch [:practaid.looper/attempt-set-loop-start (-> % .-target .-value)
                                               defaulted-loop-end-ms])
                     :on-commit #(rf/dispatch [:practaid.looper/reset-looper])
                     :disabled  (not is-playback-ours)}]
            [waveform {:album-colors album-colors
                       :loudnesses      loudnesses
                       :loop-start-frac defaulted-loop-start-frac
                       :loop-end-frac   defaulted-loop-end-frac
                       :player-pos-frac player-pos-frac
                       :handle-click (fn [e]
                                       (let [rect (-> e .-target .getBoundingClientRect)
                                             rect-width (- (.-right rect) (.-left rect))
                                             click-x (- (.-clientX e)
                                                        (.-left rect))
                                             x-frac (/ click-x rect-width)]
                                         (rf/dispatch [:practaid.looper/seek-player x-frac])))}]
            [slider {:min       0
                     :max       track-duration-ms
                     :value     defaulted-loop-end-ms
                     :on-change #(rf/dispatch [:practaid.looper/attempt-set-loop-end (-> % .-target .-value)
                                               defaulted-loop-start-ms])
                     :on-commit #(rf/dispatch [:practaid.looper/reset-looper])
                     :disabled  (not is-playback-ours)}]
            ;; TODO dumber
            (and loop-start-ms
                 loop-end-ms
                 [:div.unset-loop
                  [:div.button-wrapper
                   [:button {:on-click #(rf/dispatch [:practaid.looper/unset-loop])}
                    "Unset loop"]]])])]]))
