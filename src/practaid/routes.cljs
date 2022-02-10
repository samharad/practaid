(ns practaid.routes
  (:require [re-frame.core :as rf]
            [reitit.coercion.malli]
            [reitit.frontend]
            [reitit.frontend.easy :as rfe]
            [reitit.frontend.controllers :as rfc]
            [practaid.views.home-page :refer [home-page]]
            [practaid.views.callback-page :refer [callback-page]]
            [practaid.views.looper-page :refer [looper-page]]))

;;; Routes

(def routes
  ["/"
   [""
    {:name      :routes/home
     :view      home-page
     :link-text "Home"}]
   ["callback"
    {:name      :routes/callback
     :view      callback-page
     :link-text "Callback"
     :controllers
     [{:start (fn []
                (rf/dispatch [:practaid.events/oauth-callback]))}]}]
   ["looper"
    {:name :routes/looper
     :view looper-page}]])

(def router
  (reitit.frontend/router
    routes
    {:data {:coercion reitit.coercion.malli/coercion}}))

(defn on-navigate [new-match]
  (when new-match
    (rf/dispatch [:practaid.events/navigated new-match])))

(defn init-routes! []
  (rfe/start!
    router
    on-navigate
    {:use-fragment false}))