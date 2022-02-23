(ns practaid.views.home-page
  (:require [practaid.views.common :refer [page-wrapper]]
            [spade.core :refer [defclass]]
            [re-frame.core :as rf]))

(def spotify-green "#1DB954")

(defclass home-page-style []
  {}
  [:.explanation {:text-align "center"}]
  [:.button-container {:display "flex"
                       :justify-content "center"
                       :align-items "center"}]
  [:.spotify {:color spotify-green
              :font-weight "bold"}]
  [:.login-button {:margin "auto"
                   :text-align "center"
                   :background-color spotify-green
                   :color "white"
                   :font-size "20px"
                   :cursor "pointer"
                   :border "none"
                   :padding "10px 15px"
                   :border-radius "10px"}]
  [:.beta {:text-align "center"}]
  [:.beta-note {:color "red"}])

(defn handle-login-click []
  (rf/dispatch [:practaid.events/prepare-for-oauth]))

(defn home-page []
  [page-wrapper
   [:div.home-page {:class (home-page-style)}
    [:div.explanation
     [:p "Welcome to PractAid, a music practice tool integrated with "
      [:span.spotify "Spotify"]
      "."]
     [:p "You'll need to log in with Spotify to use this tool."]
     [:p
      [:b "We don't read, collect, store, or use any of your data."]]
     [:p "Happy practicing!"]]
    [:div.button-container
     [:button.login-button {:type "button"
                            :on-click handle-login-click}
      "Log In to Spotify"]]
    [:div.beta
     [:p
      [:b [:span.beta-note "Note:"] " this app is in beta and only accessible to allow-listed users."]]
     [:p "To request access, send an email including your Spotify username/email to "
      [:a {:href "mailto: support@practaid.com?subject=Request for Access to PractAid&body=My Spotify username is {PUT YOUR USERNAME HERE}"}
       "support@practaid.com"]]]]])