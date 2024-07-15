(ns practaid.views.home-page
  (:require [practaid.views.common :refer [page-wrapper]]
            [spade.core :refer [defclass]]
            [re-frame.core :as rf]))

(def pleasant-blue "#0C87E5FF")

(defclass home-page-style []
  {}
  [:.explanation {
                  :text-align "center"}]
                  ;:display "flex"
                  ;:flex-direction "column"}]
  [:.button-container {:display "flex"
                       :justify-content "center"
                       :align-items "center"
                       :padding-bottom "10px"}]
  [:img.spotify-logo {:width "100px"
                      :vertical-align "middle"}]
  [:.login-button {:margin           "auto"
                   :text-align       "center"
                   :background-color pleasant-blue
                   :color            "white"
                   :font-size        "20px"
                   :cursor           "pointer"
                   :border           "none"
                   :padding          "10px 15px"
                   :border-radius    "10px"}]
  [:.beta {:text-align "center"}]
  [:.beta-note {:color "red"}]
  [:.subsection {:border "1px solid black"
                 :margin "0 auto"
                 :margin-top "20px"
                 :max-width "800px"}
   [:.header {:font-style "italic"
              :font-weight "bold"}]])

(defn handle-login-click []
  (rf/dispatch [:practaid.auth/prepare-for-oauth]))

(defn boomeranged [& children]
  (let [l-pad "10px"
        r-pad "8px"
        boomerang "🪃"]
    [:span
     [:span {:style {:margin-right l-pad
                     :transform "rotate(0deg)"
                     :display "inline-block"}} boomerang]
     (for [child children]
       ^{:key child} [:span child])
     [:span {:style {:margin-left r-pad
                     :transform "rotate(180deg)"
                     :display "inline-block"}} boomerang]]))

(defn robotted [child]
  (let [l-pad "10px"
        r-pad "8px"
        robot "🤖"]
    [:span
     [:span {:style {:margin-right l-pad
                     :display      "inline-block"}} robot]
     child
     [:span {:style {:margin-left r-pad
                     :display     "inline-block"}} robot]]))

(defn home-page []
  [page-wrapper
   [:div.home-page {:class (home-page-style)}
    [:div.explanation
     [:p "PractAid is a music practice tool."]
     [:p [boomeranged
          [:span
            "It lets you loop segments of "
            [:a {:href (str "https://spotify.com")
                 :title "Spotify"}
             [:img.spotify-logo
              {:src "/image/Spotify_Logo_RGB_Green.png"}]]
            " songs."]]]
     [:p "Happy practicing!"]]
    [:div.beta
     [:div.subsection
      [:div
       [:p "PractAid requires permission from your "
        [:img.spotify-logo
         {:src "/image/Spotify_Logo_RGB_Green.png"}]
        " account."]]
      [:div.button-container
       [:button.login-button {:type "button"
                              :on-click handle-login-click}
        "Authenticate with Spotify"]]]
     [:div.subsection
      [:div
       [:p.header "Privacy Policy"]
       [:p
        "You'll need to log in with "
        [:a {:href (str "https://spotify.com")
             :title "Spotify"}
         [:img.spotify-logo
          {:src "/image/Spotify_Logo_RGB_Green.png"}]]
        " to use this tool."]
       [:p "We don't read, collect, store, or use any of your data."]]]
     [:div.subsection
      [:p.header "Why?"]
      [:p "I created this for myself as an aid to guitar practice."]]]]])