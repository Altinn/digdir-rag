(ns chat-app.ui
  (:require [chat-app.debug :as debug]
            [chat-app.rhizome :as rhizome]
            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.electric-ui4 :as ui]))

;; Shared UI state
#?(:cljs (defonce !sidebar? (atom true)))
#?(:cljs (defonce !view-main (atom :home)))
#?(:cljs (defonce !view-main-prev (atom nil)))
#?(:cljs (defonce view-main-watcher (add-watch !view-main :main-prev (fn [_k _r os _ns]
                                                                      (println "this is os: " os)
                                                                      (when-not (= os :settings))
                                                                      (reset! !view-main-prev os)))))

;; UI Utility Functions
#?(:cljs (defn observe-resize [node callback]
           (let [observed-element node
                 resize-observer (js/ResizeObserver.
                                  (fn [entries]
                                    (doseq [entry entries]
                                      (let [content-rect (.-contentRect entry)
                                            rect (.getBoundingClientRect (.-target entry))
                                            y (.-y rect)
                                            width (.-width content-rect)
                                            height (.-height content-rect)]
                                        (println "Resized" width height y)
                                        (callback width height y)))))]
             (.observe resize-observer observed-element)
             (fn [] (.disconnect resize-observer)))))

;; Layout Components
(e/defn Topbar [conversation-entity]
  (e/client
   (let [sidebar? (e/watch !sidebar?)]
     (dom/div (dom/props {:class "sticky w-full top-0 h-14 z-10"})
              (dom/div (dom/props {:class (str "flex justify-between gap-4 px-4 py-4"
                                               (if sidebar?
                                                 " w-[260px] bg-slate-100"
                                                 " w-max"))})
                       (ui/button
                        (e/fn [] (reset! !sidebar? (not @!sidebar?)))
                        (dom/img (dom/props {:class "w-6 h-6"
                                             :src (if-not sidebar?
                                                    "icons/panel-left-open.svg"
                                                    "icons/panel-left-close.svg")})))
                       #_(ui/button (e/fn [] (reset! !view-main :entity-selection))
                                    (dom/img (dom/props {:class "w-6 h-6"
                                                         :src "icons/square-pen.svg"}))))
              #_(dom/div (dom/props {:class "flex gap-4 py-4 items-center text-slate-500"})
                       (dom/p (dom/text (:name conversation-entity))))))))


(e/defn Home []
  (e/client
   (dom/div
    (dom/props {:class "max-h-full overflow-x-hidden"})
    (dom/div
     (dom/props {:class "h-[calc(100vh-10rem)] w-full flex flex-col justify-center items-center"})
     (dom/div
      (dom/props {:class "flex flex-col items-center mx-auto max-w-3xl px-4"})

      (dom/div
       (dom/props {:class "w-full max-w-[600px]"})

      ;; Title
       (dom/div
        (dom/h1
         (dom/props {:class "text-2xl font-bold text-center text-gray-500"})
         (dom/text "Presis og pålitelig innsikt,"))
        (dom/h1
         (dom/props {:class "text-2xl font-bold text-center text-gray-500 mb-6"})
         (dom/text "skreddersydd for deg.")))

      ;; Subtitle
       (dom/p
        (dom/props {:class "text-center text-lg text-gray-500"})
        (dom/text "Utforsk Kudos-dokumenter fra 2020-2023"))
       (dom/p
        (dom/props {:class "text-center text-lg text-gray-500"})
        (dom/text "som tildelingsbrev, årsrapporter og evalueringer."))
       (dom/p
        (dom/props {:class "text-center text-lg text-gray-500"})
        (dom/text "Kjapt og enkelt."))))))))

