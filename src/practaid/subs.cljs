(ns practaid.subs
  (:require
    [re-frame.core :as rf]
    [practaid.db :as q]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tier 1
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-sub
  :current-route
  (fn [db]
    (:current-route db)))

(rf/reg-sub
  ::device-id
  (fn [db]
    (:device-id db)))

(rf/reg-sub
  ::is-taking-over-playback
  (fn [db]
    (:is-taking-over-playback db)))

(rf/reg-sub
  ::track
  (fn [db]
    (q/playback-track db)))

(rf/reg-sub
  ::external-playback-state
  (fn [db]
    (:external-playback-state db)))

(rf/reg-sub
  ::recently-played
  (fn [db]
    (:recently-played db)))

(rf/reg-sub
  ::track-analysis
  (fn [db]
    (:track-analysis db)))

(rf/reg-sub
  ::loop-start-ms
  (fn [db]
    (:loop-start-ms db)))

(rf/reg-sub
  ::loop-end-ms
  (fn [db]
    (:loop-end-ms db)))

(rf/reg-sub
  ::player-pos-ms
  (fn [db]
    (q/player-pos-ms db)))

(rf/reg-sub
  ::is-authorized
  (fn [db]
    (:is-authorized db)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tier 2
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-sub
  ::playback-item
  :<- [::track]
  :<- [::external-playback-state]
  (fn [[track external-playback-state] _]
    (or track (:item external-playback-state))))

(rf/reg-sub
  ::track-duration-ms
  :<- [::playback-item]
  (fn [track _]
    (:duration_ms track)))

(rf/reg-sub
  ::defaulted-loop-start-ms
  :<- [::loop-start-ms]
  (fn [loop-start-ms _]
    (or loop-start-ms 0)))

(rf/reg-sub
  ::defaulted-loop-end-ms
  :<- [::loop-end-ms]
  :<- [::track-duration-ms]
  (fn [[loop-end-ms track-duration-ms] _]
    (or loop-end-ms track-duration-ms)))

(rf/reg-sub
  ::defaulted-loop-start-frac
  :<- [::track-duration-ms]
  :<- [::defaulted-loop-start-ms]
  (fn [[duration start] _]
    (/ start duration)))

(rf/reg-sub
  ::defaulted-loop-end-frac
  :<- [::track-duration-ms]
  :<- [::defaulted-loop-end-ms]
  (fn [[duration end] _]
    (/ end duration)))

(rf/reg-sub
  ::player-pos-frac
  :<- [::track-duration-ms]
  :<- [::player-pos-ms]
  (fn [[duration pos] _]
    (/ pos duration)))

(rf/reg-sub
  ::loudness-samples
  :<- [::track-analysis]
  (fn [{:keys [segments]} [_ opts]]
    (when (not-empty segments)
      (let [{:keys [num-samples decibel-floor decibel-ceil]}
            (merge {:num-samples 300 :decibel-floor -35 :decibel-ceil 0} opts)
            total-duration (->> segments
                                (map :duration)
                                (reduce +))
            regularized (loop [[seg & more-segs :as segments] segments
                               acc []]
                          (if (= (count acc) num-samples)
                            acc
                            (let [{:keys [start duration]} seg
                                  mark (/ (count acc) num-samples)
                                  seg-start (/ start total-duration)
                                  seg-end (/ (+ start duration) total-duration)]
                              (if (and (<= seg-start mark)
                                       (< mark seg-end))
                                (recur segments (conj acc (dissoc seg :start :duration)))
                                (recur more-segs acc)))))
            clamped (map (fn [seg]
                           (let [clamped (min decibel-ceil
                                              (max decibel-floor (:loudness_max seg)))]
                             (- 1 (/ clamped decibel-floor))))
                         regularized)
            max-loudness (reduce #(max %1 %2) clamped)]
        (map (fn [loudness]
               (/ (js/Math.round (* (/ loudness max-loudness) 100))
                  100))
             clamped)))))

(rf/reg-sub
  ::most-recently-played-track
  :<- [::recently-played]
  (fn [recently-played]
    (get-in recently-played [:items 0 :track])))

(rf/reg-sub
  ::playback-metadata
  :<- [::track]
  :<- [::external-playback-state]
  :<- [::most-recently-played-track]
  (fn [[track external-playback-state most-recently-played-track]]
    (let [item (or track
                   (get-in external-playback-state [:item])
                   most-recently-played-track)]
      {:album-cover-url (get-in item [:album :images 0 :url])
       :album-name (get-in item [:album :name])
       :artist-names (map :name (:artists item))
       :track-name (:name item)})))

(rf/reg-sub
  ::is-playback-ours
  (fn [db]
    (let [{:keys [device-id external-playback-state]} db]
      (and device-id
           external-playback-state
           (= device-id (get-in external-playback-state [:device :id]))))))


