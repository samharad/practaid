(ns practaid.routes
  (:require [re-frame.core :as rf]
            [reitit.coercion.malli]
            [reitit.frontend]
            [reitit.frontend.easy :as rfe]
            [reitit.frontend.controllers :as rfc]
            [practaid.views.home-page :refer [home-page]]
            [practaid.views.callback-page :refer [callback-page]]
            [practaid.views.looper-page :refer [looper-page]]
            [practaid.interceptors :refer [check-db-spec-interceptor]]
            [cljs.spec.alpha :as s]))

(s/def ::current-route (s/nilable any?))
(s/def ::state (s/keys :req-un [::current-route]))

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
                (rf/dispatch [:practaid.auth/oauth-callback]))}]}]
   ["looper"
    {:name :routes/looper
     :view looper-page}]])

(def router
  (reitit.frontend/router
    routes
    {:data {:coercion reitit.coercion.malli/coercion}}))

(defn on-navigate [new-match]
  (when new-match
    (rf/dispatch [::navigated new-match])))

(defn init-routes! []
  (rfe/start!
    router
    on-navigate
    {:use-fragment false}))


;; Routing -----------------------------------------------

(rf/reg-event-fx
  ::navigate
  [check-db-spec-interceptor]
  (fn [_cofx [_ route]]
    {::navigate! route}))

(rf/reg-event-db
  ::navigated
  [check-db-spec-interceptor]
  (fn [db [_ new-match]]
    (let [old-match   (:current-route (::state db))
          controllers (rfc/apply-controllers (:controllers old-match) new-match)]
      (assoc-in db
                [::state :current-route]
                (assoc new-match :controllers controllers)))))

(rf/reg-fx
  ::navigate!
  (fn [route]
    (rfe/push-state route)))

;; For foreign-domain routes; TODO maybe move me?
(rf/reg-fx
  ::assign-url
  (fn [url]
    (-> js/window
        (.-location)
        (.assign url))))

(rf/reg-fx
  ::reload-page
  (fn [_]
    (.reload js/location)))

