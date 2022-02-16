(ns practaid.views.global-styles
  (:require [spade.core :refer [defglobal]]))

(defglobal global
  [:body {:padding "0px 40px"
          :font-family "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Oxygen',
                        'Ubuntu', 'Cantarell', 'Fira Sans', 'Droid Sans', 'Helvetica Neue', sans-serif"
          :-webkit-font-smoothing "antialiased"
          :-moz-osx-font-smoothing "grayscale"}])
