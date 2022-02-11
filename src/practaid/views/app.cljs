(ns practaid.views.app
  (:require [re-frame.core :as rf]
            [practaid.views.looper-page :refer [looper-page]]
            [practaid.views.home-page :refer [home-page]]))

(defn app []
  (let [is-authorized @(rf/subscribe [:practaid.subs/is-authorized])]
    [:div
     (if is-authorized
       [looper-page]
       [home-page])]))