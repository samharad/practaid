(ns practaid.views.home-page
  (:require [practaid.views.common :refer [page-wrapper]]
            [spade.core :refer [defclass]]
            [re-frame.core :as rf]))

(defclass home-page-style []
  {}
  [:.explanation {:text-align "center"}]
  [:.button-container {:display "flex"
                       :justify-content "center"
                       :align-items "center"}]
  [:.login-button {:margin "auto"
                   :text-align "center"}])

(defn handle-login-click []
  (rf/dispatch [:practaid.events/prepare-for-oauth]))

(defn home-page []
  [page-wrapper
   [:div.home-page {:class (home-page-style)}
    [:div.explanation
     [:p "Welcome to PractAid, a music practice tool integrated with your favorite streaming service."]
     [:p "You'll need to log in with Spotify to use this tool."]
     [:p "We don't read, store, or use any of your data."]
     [:p "Happy practicing!"]]
    [:div.button-container
     [:button.login-button {:on-click handle-login-click}
      "Login with Spotify"]]]])