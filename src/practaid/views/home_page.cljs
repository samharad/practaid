(ns practaid.views.home-page
  (:require [practaid.views.common :refer [page-wrapper]]
            [spade.core :refer [defclass]]
            [re-frame.core :as rf]))

(def spotify-green "#1DB954")

(defclass home-page-style []
  {}
  [:.explanation {
                  :text-align "center"}]
                  ;:display "flex"
                  ;:flex-direction "column"}]
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
     children
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
     [:p "Welcome to PractAid, an open source music practice tool integrated with "
      [:a.spotify {:href "https://www.spotify.com/" :target "_blank"} "Spotify"]
      "."]
     [:p [boomeranged "PractAid lets you loop segments of Spotify songs."]]
     [:p [robotted [:span "Check out the source code " [:a {:href "https://github.com/samharad/practaid" :target "_blank"} "here"] "."]]]
     [:p "Happy practicing!"]]
    [:div.button-container
     [:button.login-button {:type "button"
                            :on-click handle-login-click}
      "Log In to Spotify"]]
    [:div.beta
     [:p
      [:b [:span.beta-note "Note:"] " this app is in "
       [:a {:href "https://developer.spotify.com/documentation/web-api/guides/development-extended-quota-modes/"} "development mode"]
       " and only accessible to allow-listed users."]]
     [:p "To request access, send an email including your Spotify username/email to "
      [:a {:href "mailto: support@practaid.com?subject=Request for Access to PractAid&body=My Spotify username is {PUT YOUR USERNAME HERE}"}
       "support@practaid.com"]
      "."]
     [:div.subsection
      [:div
       [:p.header "Privacy Policy"]
       [:p "You'll need to log in with Spotify to use this tool."]
       [:p "We don't read, collect, store, or use any of your data."]
       [:p "(We don't even have a backend or database -- your browser talks directly to Spotify, and that's it.)"]]]
     [:div.subsection
      [:p.header "Why?"]
      [:p "I created this for myself to solve my own pain point."]
      [:p "I often play guitar along with a Spotify song; sometimes I want to loop a section to practice it repeatedly."]
      [:p "I'm releasing it in case someone else finds it useful. (It's hosted for free on GitHub Pages, so why not?)"]]]]])