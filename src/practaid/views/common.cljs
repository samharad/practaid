(ns practaid.views.common
  (:require [spade.core :refer [defclass defkeyframes]]
            [garden.selectors :as gs]
            [re-frame.core :as rf]))

(defclass page-wrapper-style []
  {}
  [:.title {:text-align "center"
            :margin-bottom "8px"}]
  [:.title-link {:text-decoration "none"
                 :color "black"}]
  [:.logout {:text-align "center"}])
             ;:padding "0"
             ;:margin "auto"}])
             ;:display "inline"}])

(defn page-wrapper [children]
  (let [is-authorized @(rf/subscribe [:practaid.subs/is-authorized])]
    [:div {:class (page-wrapper-style)}
     [:a.title-link {:href "/"}
      [:h1.title "PractAid"]]
     (and is-authorized [:div.logout [:button {:on-click #(rf/dispatch [:practaid.auth/logout])} "Log Out"]])
     [:hr]
     children]))



;; Courtesy: https://martinwolf.org/before-2018/blog/2015/01/pure-css-savingloading-dots-animation/
(defkeyframes blink-frames []
  ["0%" {:opacity 0.2}]
  ["20%" {:opacity 1}]
  ["100%" {:opacity 0.2}])

(defclass loading-ellipses-style []
  {}
  [:span {:animation-name (blink-frames)
          :animation-duration "1.4s"
          :animation-iteration-count "infinite"
          :animation-fill-mode "both"}
   [(gs/& (gs/nth-child 2)) {:animation-delay "0.2s"}]
   [(gs/& (gs/nth-child 3)) {:animation-delay "0.4s"}]])

(defn loading-ellipses []
  [:span {:class (loading-ellipses-style)}
   [:span "."]
   [:span "."]
   [:span "."]])
