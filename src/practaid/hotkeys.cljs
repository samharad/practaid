(ns practaid.hotkeys
  (:require [re-frame.core :as rf]
            [re-pressed.core :as rp]))

(def space-key-code 32)
(def p-key-code 80)
(def enter-key-code 13)
(def mac-enter-key-code 3)
(def left-key-code 37)
(def right-key-code 39)
(def up-key-code 38)
(def down-key-code 40)

(def keypress-looper-step-ms 10)

(rf/reg-event-fx
  ::register-hotkeys
  []
  (fn [_ _]
    {:fx [[:dispatch [::rp/add-keyboard-event-listener "keydown"]]
          [:dispatch [::rp/add-keyboard-event-listener "keyup"]]
          [:dispatch [::rp/set-keyup-rules {:event-keys [[[:practaid.events/reset-looper nil]
                                                          [{:keyCode left-key-code}]
                                                          [{:keyCode right-key-code}]
                                                          [{:keyCode up-key-code}]
                                                          [{:keyCode down-key-code}]]]
                                            :always-listen-keys [{:keyCode left-key-code}
                                                                 {:keyCode right-key-code}
                                                                 {:keyCode up-key-code}
                                                                 {:keyCode down-key-code}]}]]
          [:dispatch [::rp/set-keydown-rules {:event-keys [[[:practaid.events/toggle-play]
                                                            [{:keyCode space-key-code}]
                                                            [{:keyCode p-key-code}]]

                                                           [[:practaid.events/reset-looper nil]
                                                            [{:keyCode enter-key-code}]
                                                            [{:keyCode mac-enter-key-code}]]

                                                           [[:practaid.events/attempt-increment-loop-start (- keypress-looper-step-ms)]
                                                            [{:keyCode left-key-code}]]
                                                           [[:practaid.events/attempt-increment-loop-start keypress-looper-step-ms]
                                                            [{:keyCode right-key-code}]]
                                                           [[:practaid.events/attempt-increment-loop-end (- keypress-looper-step-ms)]
                                                            [{:keyCode down-key-code}]]
                                                           [[:practaid.events/attempt-increment-loop-end keypress-looper-step-ms]
                                                            [{:keyCode up-key-code}]]
                                                           ,]
                                              :always-listen-keys [{:keyCode space-key-code}
                                                                   {:keyCode p-key-code}
                                                                   {:keyCode enter-key-code}
                                                                   {:keyCode mac-enter-key-code}
                                                                   {:keyCode left-key-code}
                                                                   {:keyCode right-key-code}
                                                                   {:keyCode up-key-code}
                                                                   {:keyCode down-key-code}]
                                              :prevent-default-keys [{:keyCode space-key-code}
                                                                     {:keyCode p-key-code}
                                                                     {:keyCode enter-key-code}
                                                                     {:keyCode mac-enter-key-code}
                                                                     {:keyCode left-key-code}
                                                                     {:keyCode right-key-code}
                                                                     {:keyCode up-key-code}
                                                                     {:keyCode down-key-code}]}]]]}))
