(ns practaid.auth
  (:require [clojure.string :as str]
            [cljs.core.async :refer [go <!]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(def client-id "5c6be04474984ab2a2a492967aa87002")

(defn random-bytes [n]
  (-> js/crypto
      (.getRandomValues (js/Uint8Array. n))))

(defn base64url [bytes]
  (-> (.-fromCharCode js/String)
      (apply bytes)
      (js/btoa)
      (str/replace #"=" "")
      (str/replace #"\+" "-")
      (str/replace #"/" "_")))

(defn go-code-challenge [code-verifier]
  (go
    (let [code-verifier-bytes (-> (js/TextEncoder.)
                                  (.encode code-verifier))
          hash-buffer (<p! (-> js/crypto
                               (.-subtle)
                               (.digest "SHA-256" code-verifier-bytes)))]
      (-> (js/Uint8Array. hash-buffer)
          (base64url)))))

(defn go-initial-auth-flow [location-origin]
  (go
    (let [code-verifier (base64url (random-bytes 96))
          nonce (base64url (random-bytes 96))
          code-challenge (<! (go-code-challenge code-verifier))
          url (str "https://accounts.spotify.com/authorize?"
                   (js/URLSearchParams. (clj->js {"response_type" "code"
                                                  "client_id" client-id
                                                  "scope" "streaming user-read-recently-played user-read-private user-read-email user-read-playback-state user-modify-playback-state user-read-currently-playing app-remote-control"
                                                  "redirect_uri" (str location-origin "/callback")
                                                  "state" nonce
                                                  "code_challenge_method" "S256"
                                                  "code_challenge" code-challenge})))]
      {:code-verifier code-verifier
       :nonce nonce
       :url url})))
