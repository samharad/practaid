(ns practaid.spot
  "Specs for Spotify-API data."
  (:require [cljs.spec.alpha :as s]))

(s/def :track/id string?)
(s/def :track/name string?)
(s/def :track.album/name string?)
(s/def :track.album.image/url string?)
(s/def :track.album/images (s/coll-of
                             (s/keys :req-un
                                     [:track.album.image/url])))
(s/def :track/album (s/keys :req-un [:practaid.spot.track.album/name
                                     :track.album/images]))
(s/def :track.artist/name string?)
(s/def :track/artists (s/coll-of (s/keys :req-un [:practaid.spot.track.artist/name])))

(s/def ::track (s/keys :req-un [:track/id
                                :track/name
                                :track/album
                                :track/artists]))

(s/def :playback-state/paused boolean?)
(s/def :playback-state/position integer?)
(s/def :playback-state.track_window/current_track ::track)
(s/def :playback-state/track_window (s/keys :req-un
                                            [:playback-state.track_window/current_track]))

(s/def ::playback-state (s/keys :req-un [:playback-state/paused
                                         :playback-state/position
                                         :playback-state/track_window]))
