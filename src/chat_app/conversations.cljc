(ns chat-app.conversations
  (:require
   #?(:clj [chat-app.auth :as auth])
   #?(:clj [models.db :refer [conn clear-all-conversations conn conversations delete-convo] :as db])
   [chat-app.chat :as chat]
   [chat-app.react :refer [react-component-tree]]
   [chat-app.router :as router]
   [chat-app.ui :as ui]
   [designsystemet.typography :as typography]
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.electric-ui4 :as ui4]))

(e/def entities-cfg
  (e/server (:chat (e/watch db/!config))))

;; Utility Functions
(e/defn Conversations [search-text]
  (e/server
   (let [db (e/watch conn)]
     (e/client
      (if-not search-text
        (e/server (e/offload #(conversations db)))
        (e/server (e/offload #(conversations db search-text))))))))

(e/defn NewThread []
  (e/server
   (println "transacting...")
   (let [entity-id (-> entities-cfg
                       :entities
                       first
                       :id)
         new-convo-id
         (e/offload #(db/transact-new-msg-thread conn
                                                 (-> entities-cfg
                                                     :entities
                                                     first
                                                     :id)))]
     (println "New thread : " new-convo-id)
     (e/client
      (let [entity (first (filter #(= (:id %) entity-id) (:entities entities-cfg)))
            convo-id (:conversation-id new-convo-id)]
        (reset! chat/!conversation-entity entity)
        ;; Navigate to the new conversation URL
        #?(:cljs (router/navigate! {:route :conversation :id convo-id}))
        ;; Also update state directly for immediate UI update
        (reset! ui/!view-main :conversation)
        (reset! chat/!active-conversation convo-id)))
     new-convo-id)))

;; Electric function to handle the actual deletion
(e/defn DeleteConversation [eid]
  (e/server
   (println "Deleting conversation:" eid)
   (e/offload #(db/delete-convo db/conn eid))))

;; Conversation Components
(e/defn ConversationList [conversations]
  (e/client
   (let [!edit-conversation (atom false)
         edit-conversation (e/watch !edit-conversation)
         active-conversation (e/watch chat/!active-conversation)
         conversation-entity (e/watch chat/!conversation-entity)
         !conversation-menu (atom false)
         conversation-menu (e/watch !conversation-menu)]
     (dom/div (dom/props {:class "pt-4 flex-grow max-h-[317px] overflow-y-auto"})
              (dom/div (dom/props {:class "flex w-full flex-col gap-4"})
                       (e/for [[_ eid convo-id topic entity-id _] conversations]
                         (dom/div
                          (dom/props {:class "flex items-center justify-between"})
                          (dom/a ;; TODO: replace with designsystemet link
                           (dom/props {:href (str "/?conversation=" convo-id)
                                       :class (str (when (= active-conversation convo-id) "font-bold ") "text-[#002C54] text-md")})
                           (dom/on "click" (e/fn [e]
                                                                ;; Prevent default navigation
                                             #?(:cljs (.preventDefault e))
                                             (let [entity (first (filter #(= (:id %) entity-id) (:entities entities-cfg)))]
                                               (when (not= entity conversation-entity)
                                                 (reset! chat/!conversation-entity entity)))
                                                                ;; Update the state directly for immediate UI update
                                             (reset! chat/!active-conversation convo-id)
                                             (reset! ui/!view-main :conversation)
                                                                ;; Update the URL without causing a page reload
                                             #?(:cljs (let [url (str "/?conversation=" convo-id)]
                                                        (.pushState js/window.history nil nil url)))))
                           (dom/text topic))
                          (let [popover-id (str "popover-" (random-uuid))]
                            (dom/div
                             (dom/props {:class "flex items-center"})
                             (react-component-tree.
                              {:component "Button"
                               :props {:variant "tertiary"
                                       :popovertarget popover-id}
                               :children [{:component "Icon" :props {:src "/icons/ellipsis.svg" :size 24}}]})
                             (react-component-tree.
                              {:component "Popover"
                               :props {:id popover-id
                                       :placement "right"}
                               :children [{:component "Button"
                                           :props {:variant "tertiary" :data-color "danger" :data-size "small" :onClick (e/fn [] (DeleteConversation. eid))}
                                           :children [{:component "Icon" :props {:src "/icons/old/delete.svg" :size 24}}]}]}))))))))))


(e/defn LeftSidebar []
  (e/server
   (let [db (e/watch conn)]
     (e/client
      (let [!search-text (atom nil)
            search-text (e/watch !search-text)
            conversations  (Conversations. search-text) ;TODO: add db to the params in v3
            !clear-conversations? (atom false)
            clear-conversations? (e/watch !clear-conversations?)]
        (dom/div
         (dom/props {:class (str "pt-4 pb-12 px-9 w-[324px] h-full flex flex-col bg-white" (if (e/watch ui/!sidebar?) " block" " hidden"))})
         (dom/div
          (dom/props {:class "flex flex-row items-center gap-2 pt-4 pb-8"})
          (react-component-tree.
           {:component "Button"
            :props {:variant "secondary"
                    :data-color "neutral"
                    :onClick (fn [] (reset! ui/!sidebar? false))}
            :children [{:component "Icon"
                        :props {:src "/icons/sidebar_left.svg"
                                :size 24}}
                       "Skjul tråder"]}))
         (dom/div
          (dom/props {:class "flex flex-row items-center gap-2 pt-4"})
          (react-component-tree.
           {:component "Button"
            :props {:variant "primary"
                    :data-color "primary"
                    :onClick (fn [] (NewThread))}
            :children ["Ny tråd" {:component "Icon"
                                  :props {:src "/icons/pencil_writing.svg"
                                          :size 24}}]}))
         ;; Conversations 
         (dom/div (dom/props {:class "flex-grow overflow-auto flex flex-col mt-8"})
                  (typography/Heading. {:level 5 :size "xs" :children "Tidligere tråder"})

                  (if (seq conversations)
                    (ConversationList. conversations)
                    (dom/div
                     (dom/div (dom/props {:class "flex flex-col justify-center items-center mt-8 select-none text-center opacity-50"})
                              (dom/img (dom/props {:src "icons/old/no-data.svg"}))
                              (dom/p (dom/text "No Data"))))))
         (dom/div
          (dom/props {:class "flex flex-col space-y-1 border-t border-gray/20 pt-1 "})

          (e/server
           (let [user-token (auth/verify-token
                             (get-in e/http-request [:cookies "auth-token" :value]))]
             (e/client
              #_(dom/p (dom/text (str "User: " (:user-id user-token))))
              (when (e/server (auth/admin-user? (:user-id user-token)))
                (ui4/button
                 (e/fn [] (reset! ui/!view-main :edit-prompt))
                 (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 transition-colors duration-200 hover:bg-gray-500/10"})
                 (dom/text "Edit prompts"))

                (if-not clear-conversations?
                  (ui4/button
                   (e/fn [] (reset! !clear-conversations? true))
                   (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 transition-colors duration-200 hover:bg-gray-500/10"})
                   (dom/text "Clear conversations"))
                  (dom/div (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 transition-colors duration-200 hover:bg-gray-500/10"})
                           (dom/img (dom/props {:src "icons/old/delete.svg"}))
                           (dom/text "Are you sure?")
                           (dom/div (dom/props {:class "right-1 z-10 flex text-gray-300"})
                                    (ui4/button (e/fn []
                                                  (e/server (e/offload #(clear-all-conversations conn)) nil)
                                                  (reset! chat/!active-conversation nil)
                                                  (reset! !clear-conversations? false))
                                                (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                                                (dom/img (dom/props {:src "icons/old/tick.svg"})))
                                    (ui4/button (e/fn [] (reset! !clear-conversations? false))
                                                (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                                                (dom/img (dom/props {:src "icons/old/x.svg"}))))))))))
          (dom/div
           (dom/props {:class "flex flex-col gap-4 mt-9"})
           (react-component-tree.
            {:component "Link"
             :props {:href "https://github.com/Altinn/digdir-rag/commits/main/"}
             :class "flex gap-2"
             :children [{:component "Icon"
                         :props {:src "/icons/wrench.svg"
                                 :size 24}} "Endringslogg"]})
           (react-component-tree.
            {:component "Link"
             :props {:href "https://github.com/Altinn/digdir-rag/blob/main/README.md"}
             :class "flex gap-2"
             :children [{:component "Icon"
                         :props {:src "/icons/book.svg"
                                 :size 24}} "Om prosjektet"]})))))))))
