(ns chat-app.main
  (:require [chat-app.debug :as debug]
            [chat-app.kit :as kit]
            [chat-app.rhizome :as rhizome]
            [services.openai :as openai]
            #?(:clj [services.system :as system])
            #?(:clj [models.db])
            #?(:clj [chat-app.rag :as rag])
            #?(:clj [chat-app.auth :as auth])
            [chat-app.webauthn :as webauthn]
            [chat-app.ui :as ui]
            [chat-app.chat :as chat]
            [chat-app.conversations :as conversations]
            [chat-app.config-ui :as config-ui]
            [chat-app.auth-ui :as auth-ui]

            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.rcf :refer [tests tap %]]

            ;; rag tests
            #?(:clj [chat-app.rag-test])))

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
   (dom/div (dom/props {:class "flex flex-1 h-full w-full"})
            (dom/div (dom/props {:class "relative flex-1 overflow-hidden pb-[160px]"})
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
        (ui/Topbar. (e/watch chat/!conversation-entity))
        (dom/main (dom/props {:class "flex h-full w-screen flex-col text-sm absolute top-0 pt-12"})
                  (dom/div (dom/props {:class "flex h-full w-full pt-[48px] sm:pt-0 items-start"})
                           (conversations/LeftSidebar.)
                           (MainView. {:!active-conversation chat/!active-conversation
                                          :!conversation-entity chat/!conversation-entity})
                           (debug/DebugController.))))))))
