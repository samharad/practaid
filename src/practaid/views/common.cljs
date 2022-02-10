(ns practaid.views.common
  (:require [spade.core :refer [defclass defkeyframes]]
            [garden.selectors :as gs]))

(defclass page-wrapper-style []
  {}
  [:.title {:text-align "center"}]
  [:.title-link {:text-decoration "none"
                 :color "black"}])

(defn page-wrapper [children]
  [:div {:class (page-wrapper-style)}
   [:a.title-link {:href "/"}
    [:h1.title "PractAid"]]
   [:hr]
   children])



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
