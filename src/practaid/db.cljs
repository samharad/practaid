(ns practaid.db
  (:require [cljs.spec.alpha :as s]
            [clojure.set :as set]))

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
(s/def ::playback-state :spot/playback-state)


(s/def ::is-paused boolean?)
(s/def ::track-analysis any?)
(s/def ::track any?)

(s/def ::player-pos-ms (s/nilable integer?))
(s/def ::player-pos-query-interval any?)

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

                                  ::is-paused
                                  ::track-analysis
                                  ::track

                                  ::player-pos-ms
                                  ::player-pos-query-interval

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

(defn default-db []
  {,
   :access-token nil
   :nonce nil
   :code-verifier nil

   :current-route nil

   :device-id nil
   :is-taking-over-playback false
   :external-playback-state nil

   :player nil

   :is-paused false
   :track-analysis nil
   :track nil

   :player-pos-ms nil
   :player-pos-query-interval nil

   :loop-start-ms nil
   :loop-end-ms nil
   :loop-timeout-id nil

   :recently-played nil
   ,})

