(ns chat-app.router
  (:require [hyperfiddle.electric :as e]
            [clojure.string :as str]
            #?(:cljs [goog.events :as events])
            #?(:cljs [goog.history.EventType :as EventType]))
  #?(:cljs (:import [goog.history Html5History])))

;; Router state
#?(:cljs (defonce !current-route (atom nil)))
#?(:cljs (defonce !history (atom nil)))

;; Route parsing and generation
(defn parse-url [url]
  (println "Raw URL input:" url)
  (let [[path query] (if (str/blank? url)
                       ["/" nil]
                       (str/split url #"\?" 2))
        path-parts (-> (if (str/starts-with? path "/")
                         path
                         (str "/" path))
                       (str/replace #"^/" "")
                       (str/split #"/")
                       (as-> parts (if (empty? parts) [""] parts)))
        query-params (when query
                       (into {}
                             (map (fn [pair]
                                    (let [[k v] (str/split pair #"=")]
                                      [(keyword k) v]))
                                  (str/split query #"&"))))]
    (println "Path parts:" path-parts)
    (println "Query params:" query-params)
    (case (first path-parts)
      "" (if-let [conv-id (:conversation query-params)]
           {:route :conversation :id conv-id}
           {:route :home})
      "conversation" {:route :conversation
                      :id (or (second path-parts) (:conversation query-params))}
      "dashboard" {:route :dashboard}
      "edit-prompt" {:route :edit-prompt}
      {:route :not-found})))

(defn generate-url [route-map]
  (case (:route route-map)
    :home "/"
    :conversation (str "/?conversation=" (:id route-map))
    :dashboard "/dashboard"
    :edit-prompt "/edit-prompt"
    "/"))

;; History management
#?(:cljs
   (defn init-history []
     (when-not @!history
       (let [history (doto (Html5History.)
                       (.setUseFragment false)
                       (.setPathPrefix "")
                       (.setEnabled true))]
         (events/listen history EventType/NAVIGATE
                        (fn [^js e]
                          (let [token (.-token e)  ;; Full token includes path + query
                                route (parse-url token)]
                            (reset! !current-route route))))
         (reset! !history history)
         ;; Use pathname + search for initial route
         (reset! !current-route (parse-url (str js/window.location.pathname js/window.location.search)))))))

#?(:cljs
   (defn navigate! [route-map]
     (when @!history
       (let [url (generate-url route-map)]
         (.setToken ^Html5History @!history url)
         (reset! !current-route route-map)))))

;; Initialize router on client
(e/defn InitRouter []
  (e/client
   (e/on-unmount #(when-let [history @!history]
                    (.setEnabled ^Html5History history false)
                    (reset! !history nil)))
   (init-history)))