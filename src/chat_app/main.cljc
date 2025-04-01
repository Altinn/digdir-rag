(ns chat-app.main
  (:require
   #?(:clj [models.db])
   #?(:clj [chat-app.rag-test])
   [chat-app.auth-ui :as auth-ui]
   [chat-app.chat :as chat]
   [chat-app.config-ui :as config-ui]
   [chat-app.conversations :as conversations]
   [chat-app.debug :as debug]
   [chat-app.router :as router]
   [chat-app.react :refer [react-component-tree]]
   [chat-app.ui :as ui]
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom] ;; rag tests
   ))

(defn T
  "For debugging
  Input → ___ → Output
           |
           |
           ↓
        Console"
  ([x]
   (prn x)
   x)
  ([tag x]
   (prn tag x)
   x))

(hyperfiddle.rcf/enable!)

(defn toggle [s k]
  (if (s k)
    (disj s k)
    (conj s k)))

(e/defn MainView [debug-props]
  (e/client
   (let [current-route (e/watch router/!current-route)]
     ;; Sync route with UI view
     (when current-route
       (case (:route current-route)
         :home (reset! ui/!view-main :home)
         :conversation (do
                         (println "Current route: " current-route)
                         (when-let [convo-id (:id current-route)]
                           (reset! chat/!active-conversation convo-id))
                         (reset! ui/!view-main :conversation))
         :dashboard (reset! ui/!view-main :dashboard)
         :edit-prompt (reset! ui/!view-main :edit-prompt)
         nil))

     (dom/div (dom/props {:class "h-full w-full overflow-hidden p-8"})
              (case (e/watch ui/!view-main)
                :home (ui/Home.)
                :conversation (chat/Conversation.)
                :dashboard (auth-ui/AuthAdminDashboard.)
                :edit-prompt (config-ui/PromptEditor.))
              (when (e/watch debug/!debug?)
                (debug/DBInspector. debug-props))))))


(e/defn Main [ring-request]
  (e/server
   (binding [e/http-request ring-request]
     (e/client
      (binding [dom/node js/document.body]
        ;; Initialize router
        (router/InitRouter.)

        ;; Main app structure
        (dom/main (dom/props {:class "flex h-full w-screen flex-col absolute top-0"})
                  (dom/div (dom/props {:class "flex h-full w-full pt-[48px] sm:pt-0 items-start bg-[#F3F4F4]"})
                           (dom/div
                            (dom/props {:class (str "flex flex-col w-[324px]" (if (e/watch ui/!sidebar?) " hidden" " block"))})
                            (react-component-tree.
                             {:component "Button"
                              :class "ml-8 mt-8"
                              :props {:variant "secondary"
                                      :data-color "neutral"
                                      :onClick (fn [] (reset! ui/!sidebar? true))}
                              :children [{:component "Icon"
                                          :props {:src "/icons/sidebar_left.svg"
                                                  :size 24}}
                                         "Vis tråder"]}))
                           (conversations/LeftSidebar.)
                           (MainView. {:!active-conversation chat/!active-conversation
                                       :!conversation-entity chat/!conversation-entity})
                           (debug/DebugController.))))))))
