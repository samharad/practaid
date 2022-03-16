(ns practaid.core
  (:require
   [reagent.dom :as rdom]
   [re-frame.core :as re-frame]
   [day8.re-frame.http-fx]
   [practaid.spot]
   [practaid.looper]
   [practaid.auth]
   [practaid.player]
   [practaid.hotkeys]
   [practaid.subs]
   [practaid.views.app :as view-root]
   [practaid.views.global-styles]
   [practaid.config :as config]
   [practaid.routes :as routes]))
   ;; MOCKS:
   ;[dev.practaid.mock-events]))

(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (routes/init-routes!)
  (let [root-el (.getElementById js/document "app")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [view-root/app] root-el)))

(defn init []
  (re-frame/dispatch-sync [:practaid.looper/initialize-app])
  (dev-setup)
  (mount-root))
