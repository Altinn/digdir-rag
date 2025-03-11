(ns chat-app.conversations
  (:require [chat-app.ui :as ui]
            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.electric-ui4 :as ui4]
            [chat-app.chat :as chat]
            #?(:clj [chat-app.auth :as auth]) 
            #?(:clj [models.db :refer [conn
                                       conversations
                                       conversations-in-folder
                                       folders
                                       rename-convo-topic
                                       rename-folder
                                       delete-convo
                                       delete-folder
                                       clear-all-conversations
                                       create-folder] :as db])))

;; Conversation state
#?(:cljs (defonce !edit-folder (atom nil)))
#?(:cljs (defonce !convo-dragged (atom nil)))
#?(:cljs (defonce !folder-dragged-to (atom nil)))
#?(:cljs (defonce !open-folders (atom #{})))

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
      (let [entity (first (filter #(= (:id %) entity-id) (:entities entities-cfg)))] 
        (reset! chat/!conversation-entity entity))
      (reset! ui/!view-main :conversation)
      (reset! chat/!active-conversation (:conversation-id new-convo-id)))
     new-convo-id)))

;; Conversation Components
(e/defn ConversationList [conversations]
  (e/client
   (let [inside-folder-atom? (atom false)
         inside-folder? (e/watch inside-folder-atom?)
         !edit-conversation (atom false)
         edit-conversation (e/watch !edit-conversation)
         active-conversation (e/watch chat/!active-conversation)
         conversation-entity (e/watch chat/!conversation-entity)]
     (dom/div (dom/props {:class "pt-2 flex-grow"})
              (dom/div (dom/props {:class (str (when-not inside-folder? "gap-1 ") "flex w-full flex-col")})
                       (e/for [[created eid convo-id topic entity-id folder-name] conversations]
                         (when folder-name (reset! inside-folder-atom? folder-name))
                         (let [editing? (= convo-id (:convo-id edit-conversation))]
                           (dom/div (when folder-name (dom/props {:class "ml-5 gap-2 border-l pl-2"}))
                                    (dom/div (dom/props {:class "relative flex items-center"})
                                             (if-not (and editing? (= :edit (:action edit-conversation)))
                                               (dom/button
                                                (dom/props {:class (str (when (= active-conversation convo-id) "bg-slate-200 ") "flex w-full cursor-pointer items-center gap-3 rounded-lg p-3 text-sm transition-colors duration-200 ")
                                                            :draggable true})
                                                (dom/on "click" (e/fn [_]
                                                                  (let [entity (first (filter #(= (:id %) entity-id) (:entities entities-cfg)))]
                                                                    (when (not= entity conversation-entity)
                                                                      (reset! chat/!conversation-entity entity)))
                                                                  (reset! chat/!active-conversation convo-id)
                                                                  (reset! ui/!view-main :conversation)))
                                                (dom/div (dom/props {:class "relative max-h-5 flex-1 overflow-hidden text-ellipsis whitespace-nowrap break-all text-left text-[12.5px] leading-3 pr-1"})
                                                         (dom/text topic)))
                                               (dom/div (dom/props {:class "flex w-full items-center gap-3 rounded-lg bg-[#343541]/90 p-3"})
                                                        (dom/input (dom/props {:class "mr-12 flex-1 overflow-hidden overflow-ellipsis border-neutral-400 bg-transparent text-left text-[12.5px] leading-3 text-white outline-none focus:border-neutral-100"
                                                                               :value topic})
                        ;; TODO: resolve bug on keyboard events
                                                                   (dom/on "keydown" (e/fn [e]
                                                                                       (when (= "Enter" (.-key e))
                                                                                         (when-some [v (not-empty (.. e -target -value))]
                                                                                           (let [new-topic (:changes @!edit-conversation)]
                                                                                             (e/server
                                                                                              (println "keydown enter")
                                                                                              (e/offload #(rename-convo-topic conn convo-id new-topic))
                                                                                              nil)
                                                                                             (reset! !edit-conversation false))))))
                                                                   (dom/on "keyup" (e/fn [e]
                                                                                     (when-some [v (not-empty (.. e -target -value))]
                                                                                       (swap! !edit-conversation assoc :changes v))))
                                                                   (.focus dom/node))))
                                             (when (= convo-id active-conversation)
                                               (dom/div (dom/props {:class "absolute right-1 z-10 flex text-gray-300"})
                                                        (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                                                                    (if-not editing?
                                                                      (dom/img (dom/props {:src "icons/old/edit.svg"}))
                                                                      (dom/img (dom/props {:src "icons/old/tick.svg"})))
                                                                    (if editing?
                                                                      (dom/on "click" (e/fn [_]
                                                                                        (case (:action edit-conversation)
                                                                                          :delete (do
                                                                                                    (e/server
                                                                                                     (e/offload #(delete-convo conn eid))
                                                                                                     nil)
                                                                                                    (when (= convo-id @chat/!active-conversation)
                                                                                                      (reset! chat/!active-conversation nil))
                                                                                                    (reset! !edit-conversation false))
                                                                                          :edit (let [new-topic (:changes @!edit-conversation)]
                                                                                                  (e/server
                                                                                                   (e/offload #(rename-convo-topic conn convo-id new-topic))
                                                                                                   nil)
                                                                                                  (reset! !edit-conversation false)))))
                                                                      (dom/on "click" (e/fn [_] (reset! !edit-conversation {:convo-id convo-id
                                                                                                                            :action :edit})))))
                                                        (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                                                                    (if editing?
                                                                      (dom/on "click" (e/fn [_] (reset! !edit-conversation false)))
                                                                      (dom/on "click" (e/fn [_] (reset! !edit-conversation {:convo-id convo-id
                                                                                                                            :action :delete}))))
                                                                    (dom/img (dom/props {:src (if editing?
                                                                                                "icons/old/x.svg"
                                                                                                "icons/old/delete.svg")}))))))))))))))


(e/defn FolderList [folders]
  (e/client
    (when (seq folders)
      (dom/div (dom/props {:class "flex border-b border-white/20 pb-2"})
        (dom/div (dom/props {:class "flex w-full flex-col pt-2"})
          (e/for [[_created eid folder-id name] folders]
            (let [edit-folder (e/watch !edit-folder)
                  editing? (= folder-id (:folder-id edit-folder))
                  open-folder? (contains? (e/watch !open-folders) folder-id)
                  conversations (e/server (e/offload #(conversations-in-folder conn folder-id)))]
              (dom/div (dom/props {:class "relative flex items-center"})
                (if-not (and editing? (= :edit (:action edit-folder)))
                  (dom/button (dom/props {:class (str (when (= folder-id (e/watch !folder-dragged-to)) "bg-[#343541]/90 ") "flex w-full cursor-pointer items-center gap-3 rounded-lg p-3 text-sm transition-colors duration-200 hover:bg-[#343541]/90")}) 
                    (dom/on "click" (e/fn [_] (if-not open-folder?
                                                (swap! !open-folders conj folder-id)
                                                (swap! !open-folders disj folder-id)))) 
                    (dom/img (dom/props {:src (if-not open-folder? "icons/old/right-arrow.svg" "icons/old/down-arrow.svg")}))
                    (dom/text name))
                  (dom/div (dom/props {:class "flex w-full items-center gap-3 rounded-lg bg-[#343541]/90 p-3"})
                    (dom/input (dom/props {:class "mr-12 flex-1 overflow-hidden overflow-ellipsis border-neutral-400 bg-transparent text-left text-[12.5px] leading-3 text-white outline-none focus:border-neutral-100"
                                           :value name})
                      ;; TODO: resolve bug on keyboard events
                      (dom/on "keydown" (e/fn [e]
                                          (when (= "Enter" (.-key e)) 
                                            (when-some [v (not-empty (.. e -target -value))]
                                              (let [new-name (:changes @!edit-folder)]
                                                (e/server
                                                  (e/offload #(rename-folder conn folder-id new-name))
                                                  nil)
                                                (reset! !edit-folder false))))))
                      (dom/on "keyup" (e/fn [e]
                                        (when-some [v (not-empty (.. e -target -value))]
                                          (swap! !edit-folder assoc :changes v))))
                      (.focus dom/node))))
                (dom/div (dom/props {:class "absolute right-1 z-10 flex text-gray-300"})
                  (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                    (if-not editing?
                      (dom/img (dom/props {:src "icons/old/edit.svg"}))
                      (dom/img (dom/props {:src "icons/old/tick.svg"})))
                    (if editing?
                      (dom/on "click" (e/fn [_]
                                        (case (:action edit-folder)
                                          :delete (do
                                                    (e/server
                                                      (e/offload #(delete-folder conn eid))
                                                      nil)
                                                    (reset! !edit-folder false))
                                          :edit (let [new-name (:changes @!edit-folder)]
                                                  (e/server
                                                    (e/offload #(rename-folder conn folder-id new-name))
                                                    nil)
                                                  (reset! !edit-folder false)))))
                      (dom/on "click" (e/fn [_] (reset! !edit-folder {:folder-id folder-id
                                                                      :action :edit})))))
                  (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                    (if editing?
                      (dom/on "click" (e/fn [_] (reset! !edit-folder false)))
                      (dom/on "click" (e/fn [_] (reset! !edit-folder {:folder-id folder-id
                                                                      :action :delete}))))
                    (dom/img (dom/props {:src (if editing?
                                                "icons/old/x.svg"
                                                "icons/old/delete.svg")})))))
              (when open-folder?
                (ConversationList. conversations)))))))))

(e/defn LeftSidebar []
  (e/server
   (let [db (e/watch conn)]
     (e/client
      (when (e/watch ui/!sidebar?)
        (let [folders (e/server (e/offload #(folders db)))
              !search-text (atom nil)
              search-text (e/watch !search-text)
              conversations  (Conversations. search-text) ;TODO: add db to the params in v3
              !clear-conversations? (atom false)
              clear-conversations? (e/watch !clear-conversations?)]
          (dom/div
           (dom/props {:class (str "bg-slate-100 pt-4 px-4 w-[260px] h-full flex flex-col gap-4")})
           (dom/div
            (dom/props {:class "flex flex-row items-center gap-2"})
            (dom/span (dom/props {:class "bg-gray-300 text-black text-sm font-bold px-2.5 py-0.5 rounded-full"})
                      (dom/text "BETA"))
            (dom/span (dom/props {:class "text-black text-sm font-medium px-2.5 py-0.5"})
                      (dom/text "Kunnskapsassistent")))

           (dom/div
            (dom/props {:class "flex flex-row items-center gap-2 pt-4"})
            (let [local-btn-style "flex items-center gap-4 py-2 px-4 w-full rounded hover:bg-slate-300"
                  entity (first (:entities entities-cfg))]
              (ui4/button
               NewThread
               (dom/props {:class local-btn-style})
               (dom/div
                (dom/props {:class "bg-gray-300 text-black text-sm font-bold px-2.5 py-0.5 rounded flex items-center gap-2"})
                (dom/span (dom/text "Ny tråd"))
                (dom/span (dom/img (dom/props {:class "w-4" :src "icons/pencil.svg"})))))))

         ;; Conversations 
           (dom/div (dom/props {:class "flex-grow overflow-auto flex flex-col"})
                    (if (or (seq conversations) (seq folders))
                      (do
                        (FolderList. folders)
                        (ConversationList. conversations))
                      (dom/div
                       (dom/div (dom/props {:class "flex flex-col justify-center items-center mt-8 select-none text-center opacity-50"})
                                (dom/img (dom/props {:src "icons/old/no-data.svg"}))
                                (dom/p (dom/text "No Data"))))))
           (dom/div
            (dom/props {:class "flex flex-col items-center space-y-1 border-t border-white/20 pt-1 text-sm"})

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
                                                  (dom/img (dom/props {:src "icons/old/x.svg"}))))))))))))))))))
