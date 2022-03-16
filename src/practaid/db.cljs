(ns practaid.db
  (:require [cljs.spec.alpha :as s]
            [practaid.spot]))
            ;; TODO fix circular dep and uncomment me
            ;[practaid.player :as player]))

(s/def :re-pressed.core/keydown (s/nilable any?))
(s/def :re-pressed.core/keyup (s/nilable any?))

(s/def ::db-keys (s/keys :req [:re-pressed.core/keyup
                               :re-pressed.core/keydown

                               :practaid.player/state
                               :practaid.looper/state
                               :practaid.routes/state]))

(s/def ::db ::db-keys)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn default-db []
  {,
   :re-pressed.core/keydown nil
   :re-pressed.core/keyup nil

   :practaid.player/state {:player nil
                           :device-id nil
                           :playback-state nil
                           :pos-ms nil
                           :pos-query-interval-id nil
                           :external-playback-state nil
                           :is-taking-over-playback false
                           :recently-played nil}

   :practaid.routes/state {:current-route nil}

   ;; All looper functionality built atop the player;
   ;; looping, play, pause, seek, waveform stuff
   :practaid.looper/state {:loop-start-ms nil
                           :loop-end-ms nil
                           :loop-timeout-id nil
                           :track-analysis nil
                           :album-colors nil}

                 ,})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queries
;; TODO move these
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn is-paused [db]
  (get-in db [:practaid.player/state :playback-state :paused]))

(defn playback-track [db]
  (get-in db [:practaid.player/state :playback-state :track_window :current_track]))

(defn player-pos-ms [db]
  (get-in db [:practaid.player/state :pos-ms]))








