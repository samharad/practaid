(ns practaid.core
  (:require
   [reagent.dom :as rdom]
   [re-frame.core :as re-frame]
   [practaid.events :as events]
   [practaid.views.root :as view-root]
   [practaid.config :as config]
   [practaid.routes :as routes]
   [practaid.subs]))



(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (routes/init-routes!)
  (let [root-el (.getElementById js/document "app")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [view-root/main-page] root-el)))

(defn init []
  (re-frame/dispatch-sync [:practaid.events/initialize-db])
  (dev-setup)
  (mount-root))
