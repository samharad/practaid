(ns practaid.db
  (:require [cljs.spec.alpha :as s]
            [clojure.set :as set]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Spec
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Spotify ------------------------------------------------

(s/def :spot.track/id string?)
(s/def :spot.track/name string?)
(s/def :spot.track.album/name string?)
(s/def :spot.track.album.image/url string?)
(s/def :spot.track.album/images (s/coll-of
                                  (s/keys :req-un
                                          [:spot.track.album.image/url])))
(s/def :spot.track/album (s/keys :req-un [:spot.track.album/name
                                          :spot.track.album/images]))
(s/def :spot.track.artist/name string?)
(s/def :spot.track/artists (s/coll-of (s/keys :req-un [:spot.track.artist/name])))
(s/def :spot/track (s/keys :req-un [:spot.track/id
                                    :spot.track/name
                                    :spot.track/album
                                    :spot.track/artists]))

(s/def :spot.playback-state/paused boolean?)
(s/def :spot.playback-state/position integer?)
(s/def :spot.playback-state.track_window/current_track :spot/track)
(s/def :spot.playback-state/track_window (s/keys :req-un
                                                 [:spot.playback-state.track_window/current_track]))
(s/def :spot/playback-state (s/keys :req-un [:spot.playback-state/paused
                                             :spot.playback-state/position
                                             :spot.playback-state/track_window]))



;; Auth ---------------------------------------------------

(s/def ::access-token (s/nilable string?))
(s/def ::nonce (s/nilable string?))
(s/def ::code-verifier (s/nilable string?))

;; Route --------------------------------------------------

(s/def ::current-route (s/nilable (s/map-of any? any?)))

(s/def ::device-id (s/nilable string?))
(s/def ::is-taking-over-playback boolean?)
(s/def ::external-playback-state any?)

(s/def ::player any?)
(s/def ::playback-state (s/nilable :spot/playback-state))


(s/def ::track-analysis any?)

(s/def ::player-pos-query-interval-id (s/nilable string?))

(s/def ::loop-start-ms (s/nilable integer?))
(s/def ::loop-end-ms(s/nilable integer?))
(s/def ::loop-timeout-id any?)

(s/def ::recently-played any?)

(s/def ::db-keys (s/keys :req-un [,
                                  ::access-token
                                  ::nonce
                                  ::code-verifier

                                  ::current-route

                                  ::device-id
                                  ::is-taking-over-playback
                                  ::external-playback-state

                                  ::player
                                  ::playback-state

                                  ::track-analysis

                                  ::player-pos-query-interval-id

                                  ::loop-start-ms
                                  ::loop-end-ms
                                  ::loop-timeout-id

                                  ::recently-played
                                  ,]))

(s/def ::db (s/and
              ::db-keys
              (fn [m]
                (let [m-keys (set (map #(keyword "practaid.db" %) (keys m)))
                      expected (set (.-req_un (s/get-spec ::db-keys)))
                      diff (set/difference m-keys expected)]
                  (if (not-empty diff)
                    (do
                      (js/console.error (clj->js diff))
                      false)
                    true)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn default-db []
  {,
   ;; Auth ------------------------------------------------
   :access-token nil
   :nonce nil
   :code-verifier nil

   ;; Routing ---------------------------------------------
   :current-route nil

   ;; Foreign ---------------------------------------------
   :external-playback-state nil
   :track-analysis nil
   :recently-played nil

   ;; Player ----------------------------------------------
   :player nil
   :playback-state nil
   :device-id nil

   ;; Looping & playback ----------------------------------
   :loop-start-ms nil
   :loop-end-ms nil
   :loop-timeout-id nil
   :player-pos-query-interval-id nil

   ;; Load flags -------------------------------------------
   :is-taking-over-playback false

                 ,})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn is-paused [db]
  (get-in db [:playback-state :paused]))

(defn playback-track [db]
  (get-in db [:playback-state :track_window :current_track]))

(defn player-pos-ms [db]
  (get-in db [:playback-state :position]))










