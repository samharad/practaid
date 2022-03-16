(ns practaid.common
  "For shared re-frame infra; use sparingly."
  (:require [re-frame.core :as rf]
            [cljs.spec.alpha :as s]
            [akiroz.re-frame.storage :refer [reg-co-fx!]]))

;; TODO move store stuff into own ns
(reg-co-fx! :practaid {:fx :store
                       :cofx :store})

(s/def :store/access-token (s/nilable string?))
(s/def :store/expires-at (s/nilable string?))
(s/def :store/refresh-token (s/nilable string?))
(s/def :store/nonce (s/nilable string?))
(s/def :store/code-verifier (s/nilable string?))
(s/def ::store (s/nilable (s/keys :opt-un [:store/access-token
                                           :store/expires-at
                                           :store/refresh-token
                                           :store/nonce
                                           :store/code-verifier])))

(defn check-and-throw
  "Throws an exception if `x` doesn't match the Spec `a-spec`."
  ;; TODO: make the console error more legible
  [a-spec x]
  (when-not (s/valid? a-spec x)
    (cljs.pprint/pprint (->> (s/explain-data a-spec x)
                             :cljs.spec.alpha/problems
                             (map #(dissoc % :val))))
    (throw (ex-info (str "spec check failed: " (s/explain-str a-spec x)) {}))))

(def check-db-spec-interceptor (rf/after (partial check-and-throw :practaid.db/db)))

(def check-store-spec-interceptor
  (rf/->interceptor
    :id :check-store-spec-interceptor
    :after (fn [context]
             (check-and-throw ::store (get-in context [:coeffects :store]))
             context)))

(def inject-store (rf/inject-cofx :store))

(rf/reg-event-fx
  ::http-request-failure
  [check-db-spec-interceptor]
  (fn [_ [_ result]]
    (let [{:keys [status]} result]
      (if (#{401} status)
        {:fx [[:dispatch [:practaid.looper/reset-app-completely]]]}
        ;; Innocent side effect
        (js/console.error result)))))


(rf/reg-fx
  ::clear-timeout
  (fn [id]
    (js/clearTimeout id)))

(rf/reg-fx
  ::set-timeout
  (fn [{:keys [f timeout-ms on-set]}]
    (let [id (js/setTimeout f timeout-ms)]
      (rf/dispatch (conj on-set id)))))

(rf/reg-fx
  ::set-interval
  (fn [{:keys [f interval-ms on-set]}]
    (let [id (js/setInterval f interval-ms)]
      (when on-set
        (rf/dispatch (conj on-set id))))))

(rf/reg-fx
  ::clear-interval
  (fn [id]
    (js/clearInterval id)))
