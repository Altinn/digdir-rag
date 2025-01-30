(ns chat-app.main
  (:require [chat-app.debug :as debug]
            [chat-app.kit :as kit]
            [chat-app.rhizome :as rhizome]
            [services.openai :as openai]
            #?(:clj [services.system :as system]) 
            #?(:clj [models.db
                     :refer [delayed-connection
                             fetch-convo-messages-mapped
                             fetch-convo-entity-id
                             fetch-user-id
                             conversations
                             create-folder
                             folders
                             rename-convo-topic
                             rename-folder
                             delete-convo
                             delete-folder
                             clear-all-conversations
                             conversations-in-folder]
                     :as db])
            #?(:clj [chat-app.rag :as rag])
            #?(:clj [chat-app.auth :as auth])
            [chat-app.webauthn :as webauthn]

            [nano-id.core :refer [nano-id]]
            #?(:clj [aero.core :as aero])

            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.electric-ui4 :as ui]

            [clojure.string :as str]
            [markdown.core :as md2]))

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

#?(:clj (defonce conn @delayed-connection))

(e/def config-filename (e/server (System/getenv "ENTITY_CONFIG_FILE")))
(e/def entities-cfg 
  (e/server
   (let [_ (println (str "reading config from file: " config-filename))]
     (:chat (aero/read-config config-filename)))))
(e/def db) ; injected database ref; Electric defs are always dynamic
(e/def auth-conn)



#?(:cljs (defonce !state (atom {:route nil})))
#?(:cljs (defonce !view-main (atom :home)))
#?(:cljs (defonce !conversation-entity (atom nil)))
#?(:cljs (defonce !view-main-prev (atom nil)))
#?(:cljs (defonce !edit-folder (atom nil)))
#?(:cljs (defonce !active-conversation (atom nil)))
#?(:cljs (defonce !convo-dragged (atom nil)))
#?(:cljs (defonce !folder-dragged-to (atom nil)))
#?(:cljs (defonce !open-folders (atom #{})))
#?(:cljs (defonce !sidebar? (atom true)))
#?(:clj (defonce !stream-msgs (atom {}))) ;{:convo-id nil :content nil :streaming false}
#?(:clj (defonce !wait? (atom false)))
#?(:clj (defonce !stream? (atom false)))
#?(:cljs (defonce view-main-watcher (add-watch !view-main :main-prev (fn [_k _r os _ns]
                                                                       (println "this is os: " os)
                                                                       (when-not (= os :settings))
                                                                       (reset! !view-main-prev os)))))


;; Utils

(e/defn Conversations [search-text]
  (e/client
    (if-not search-text
      (e/server (e/offload #(conversations db)))
      (e/server (e/offload #(conversations db search-text))))))


(e/defn HandleChatMsg [user-query last-message-is-filter?]
  (e/client
   (let [current-convo-id (e/watch !active-conversation)
         conversation-entity (e/watch !conversation-entity)
         ;; initialize new conversation id on the client, as it wasn't possible to get 
         ;; a server-side generated id back to the client reliably
         new-convo-id (nano-id)]
     (e/server
      (let [new-convo-id-srv new-convo-id ;; workaround for electric v2 bug
            call-open-ai? (e/watch debug/!call-open-ai?)
            stream? (e/watch !stream?)
            base-msg-data {:stream? stream?
                           :call-open-ai? call-open-ai?
                           :user-query user-query
                           :conversation-entity conversation-entity}
            msg-data (if current-convo-id
                       (assoc base-msg-data :convo-id current-convo-id)
                       (assoc base-msg-data :convo-id new-convo-id-srv :new-convo? true)) 
            job-data {:type (if last-message-is-filter? :new :followup)
                      :msg-data msg-data}]

         ;; Enqueue the job instead of running it immediately
        (rag/enqueue-rag-job job-data)

        (e/client
         (println "current-convo-id: " current-convo-id "new-convo-id: " new-convo-id)
         (when-not current-convo-id
           (reset! !active-conversation new-convo-id)
           (reset! !view-main :conversation))))
      nil))))

;; Views

(e/defn PromptInput [{:keys [convo-id messages]}]
  (e/client
  ;; TODO: add the system prompt to the message list
    (let [!input-node (atom nil)
          wait? (e/server (e/watch !wait?))
          conversation-entity (e/watch !conversation-entity)]
      (dom/div (dom/props {:class (str (if (rhizome/mobile-device?) "bottom-8" "bottom-0") " absolute left-0 w-full border-transparent bg-gradient-to-b from-transparent via-white to-white pt-6 md:pt-2")})
        (dom/div (dom/props {:class "stretch mx-2 mt-4 flex flex-row gap-3 last:mb-2 md:mx-4 md:mt-[52px] md:last:mb-6 lg:mx-auto lg:max-w-3xl"})
          (dom/div (dom/props {:class "flex flex-col w-full gap-2"})
            (dom/div (dom/props {:class "relative flex w-full flex-grow flex-col rounded-md border border-black/10 bg-white shadow-[0_0_10px_rgba(0,0,0,0.10)] sm:mx-4"})
              (dom/textarea
               (dom/props {:id "prompt-input"
                           :class "sm:h-11 m-0 w-full resize-none border-0 bg-transparent p-0 py-2 pr-8 pl-10 text-black md:py-3 md:pl-10"
                           :placeholder (str "Hva kan jeg hjelpe deg med?")
                           :value ""
                           :disabled wait?})
               (reset! !input-node dom/node)
               (.focus dom/node)
               (dom/on "keydown"
                       (e/fn [e]
                         (when (= "Enter" (.-key e))
                           (.preventDefault e)
                           (when-some [v (not-empty (.. e -target -value))]
                             (when-not (str/blank? v)
                               (HandleChatMsg. v
                                               (-> (last messages) :message.filter/value boolean))))
                           (set! (.-value @!input-node) "")))))
              (let [wait? (e/server (e/watch !wait?))]
                (ui/button
                 (e/fn []
                   (when-some [v (not-empty (.-value @!input-node))]
                     (when-not (str/blank? v)
                       (HandleChatMsg. v
                                       (-> (last messages) :message.filter/value boolean))))
                   (set! (.-value @!input-node) ""))
                 (dom/props {:title (when wait? "Functionality not implemented") ;TODO: implement functionality to stop process
                             :class "absolute right-2 top-2 rounded-sm p-1 text-neutral-800 opacity-60 hover:bg-neutral-200 hover:text-neutral-900"})
                 (if-not wait?
                   (dom/img (dom/props {:src "icons/old/send.svg"}))
                   (dom/img (dom/props {:src "icons/circle-stop.svg"}))))))))))))

(e/defn BotMsg [msg-map]
  (e/client
    (let [conversation-entity (e/watch !conversation-entity)
          {:message/keys [created id text role kind voice]} msg-map
          {:keys [name image]} conversation-entity
          ;; _ (prn "msg: " msg-map)
          ]
      (dom/div (dom/props {:class "flex w-full flex-col items-start"})
        (let [msg-hovered? (dom/Hovered?.)]
          (dom/div (dom/props {:class "flex"})
                   (dom/img (dom/props {:class "rounded-full w-8 h-8"
                                        :src image}))
                   #_(dom/div (dom/span (dom/text kind)))
                   (dom/div (dom/props {:class "prose whitespace-pre-wrap px-4 pt-1 max-w-[600px]"}) 
                            (case kind
                              :kind/html (set! (.-innerHTML dom/node) text)
                              :kind/markdown (set! (.-innerHTML dom/node) (md2/md-to-html-string text))
                              (set! (.-innerHTML dom/node) (md2/md-to-html-string text)))))

          #_(dom/div
           (dom/props
            {:class (str "msg-controls flex gap-1 mt-4 rounded bg-white border p-2"
                         (if-not msg-hovered? " invisible" " visible "))})
           (e/for-by identity [{:keys [title file-name action]}
                               [#_{:title "Read aloud"
                                 :action :read
                                 :file-name "speech"}
                                #_{:title "Copy"
                                 :action :copy
                                 :file-name "copy"}
                                #_{:title "Regenerate"
                                 :action :regenerate
                                 :file-name "refresh-cw"}]]
                     (ui/button (e/fn []
                                  (case action
                                    :copy (rhizome/copy-to-clipboard text)
                                    :read (rhizome/speak-text text)
                                    nil))
                                (dom/props {:class (str "hover:bg-slate-200 rounded-full flex justify-center items-center w-8 h-8"
                                                        (if-not msg-hovered?
                                                          " invisible"
                                                          " visible"))})
                                (dom/img (dom/props {:class "w-4" :src (str "icons/" file-name ".svg")}))))))))))

(e/defn UserMsg [msg]
  (e/client
    (dom/div (dom/props {:class "flex w-full flex-col items-start"})
      (let [msg-hovered? (dom/Hovered?.)]
        (dom/div (dom/props {:class "relative max-w-[70%] rounded-3xl bg-[#b8e9f8] px-5 py-2.5"})
         ;; disabling edit button for now, should make it configurable                  
                 #_(ui/button (e/fn [])
                              (dom/props {:class (str "absolute -left-12 top-1 hover:bg-[#f4f4f4] rounded-full flex justify-center items-center w-8 h-8"
                                                      (if-not msg-hovered?
                                                        " invisible"
                                                        " visible"))
                                          :title "Edit message"})
                              (dom/img (dom/props {:class "w-4" :src "icons/pencil.svg"})))
                 (dom/p (dom/text msg)))))))



(defn typesense-field->ui-name [field]
  ({"type" "dokumenttyper" 
    "orgs_short" "organisasjoner"
    "orgs_long" "organisasjoner"
    "owner_short" "eiere"
    "owner_long" "eiere"
    "publisher_short" "utgivere"
    "publisher_long" "utgivere"
    "recipient_short" "mottakere"
    "recipient_long" "mottakere"
    "source_published_year" "år publisert"} field field))


(e/defn FilterField [{:as x :keys [expanded? options field]} ToggleOption ToggleFieldExpanded? enabled?]
  (e/client
   (dom/div ;; NB: this extra div is required, otherwise the card
            ;;     will stretch to the bottom of the parent
            ;;     regardless of content height.
    (dom/div
     (dom/props {:class (str "mb-4 space-y-2 p-4 rounded-md shadow-md border " (if enabled? "bg-white" "bg-gray-300"))})
     (dom/div
      (dom/div
       (dom/props {:class "font-medium text-gray-800 mb-2"})
       (dom/text (str "Velg " (typesense-field->ui-name field))))

      (ui/button ToggleFieldExpanded?
                 (dom/props {:class "px-4 py-2 bg-blue-500 text-white rounded-md hover:bg-blue-600 focus:outline-none"})
                 (dom/text (str (count (filter :selected? options)) " valgt")))
      (when expanded?
        (dom/div
         (dom/props {:class "flex flex-col items-start space-y-2 mt-1 max-h-48 overflow-y-scroll"})
         (e/for [{:keys [selected? count value]} options]
           (ui/button (e/fn [] (ToggleOption. value))
                      (dom/span
                       (dom/props {:class "grid grid-cols-[16px_1fr_auto] items-center gap-2 w-full text-left p-2 hover:bg-gray-100 rounded-md"})
                       (dom/img (dom/props {:class "w-[16px] h-[16px] flex-shrink-0"
                                            :src (if selected?
                                                   "icons/checked_checkbox.svg"
                                                   "icons/unchecked_checkbox.svg")}))
                       (dom/span
                        (dom/props {:class "text-gray-800 whitespace-wrap"})
                        (dom/text value))
                       (dom/span
                        (dom/props {:class "text-gray-600 text-right"})
                        (dom/text (str "(" count ")")))))))))))))



(defn toggle [s k]
  (if (s k)
    (disj s k)
    (conj s k)))

(e/defn FilterMsg [msg enabled?]
  (e/client
   (let [mfilter (:message.filter/value msg)]
     (e/for [[idx field] (map vector (range) (mfilter :ui/fields))]
       (FilterField. field
                     (e/fn ToggleOption [option]
                       (if-not enabled?
                         (e/client
                          (js/alert "Filteret kan ikke endres etter oppfølgningspørsmål er sendt"))
                         (e/server
                          (e/offload
                           #(db/set-message-filter
                             db/conn
                             (:db/id msg)
                             (-> (update-in mfilter [:fields idx :selected-options] toggle option)
                                 (dissoc :ui/fields)))))))
                     (e/fn ToggleFieldExpanded? []
                       (e/server
                        (e/offload
                         #(db/set-message-filter
                           db/conn
                           (:db/id msg)
                           (-> (update-in mfilter [:fields idx :expanded?] not)
                               (dissoc :ui/fields))))))
                     enabled?)))))

(e/defn RenderMsg [msg-map last?]
  (e/client
   (let [{:message/keys [created id text role kind voice]} msg-map
         _ (prn "message id" id "voice:" voice " kind: " kind)]
     (dom/div (dom/props {:class "w-full"})
              (dom/div
               (dom/props {:class "mx-auto flex flex-1 gap-4 text-base md:gap-5 lg:gap-6 md:max-w-3xl lg:max-w-[40rem] xl:max-w-[48rem]"})
               (case voice
                 :user (UserMsg. text)
                 :assistant  (BotMsg. msg-map)
                 :filter (FilterMsg. msg-map last?)
                 :agent (dom/div (dom/text))
                 :system (dom/div (dom/props {:class "group md:px-4 border-b border-black/10 bg-white text-gray-800"})
                                  (dom/div (dom/props {:class "relative m-auto flex p-4 text-base md:max-w-2xl md:gap-6 md:py-6 lg:max-w-2xl lg:px-0 xl:max-w-3xl"})
                                           #_(dom/div (dom/props {:class "min-w-[40px] text-right font-bold"})
                                                      (set! (.-innerHTML dom/node) bot-icon))
                                           (dom/div (dom/props {:class "prose whitespace-pre-wrap flex-1"})
                                                    (set! (.-innerHTML dom/node) (md2/md->html text)))
                                           #_(dom/div (dom/props {:class "md:-mr-8 ml-1 md:ml-0 flex flex-col md:flex-row gap-4 md:gap-1 items-center md:items-start justify-end md:justify-start"})
                                                      (dom/button (dom/props {:class "invisible group-hover:visible focus:visible text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"})

                                                                  (set! (.-innerHTML dom/node) delete-icon)))))
                 ))))))









(e/defn ResponseState [state]
  (e/client (dom/div (dom/props {:class "w-full"})
              (dom/div
               (dom/props {:class "mx-auto flex flex-1 gap-4 text-base md:gap-5 lg:gap-6 md:max-w-3xl lg:max-w-[40rem] xl:max-w-[48rem]"})
               (dom/div (dom/props {:class "flex w-full flex-col items-start"})
                        (let [msg-hovered? (dom/Hovered?.)]
                          (dom/div (dom/props {:class "flex"})
                                   (dom/img (dom/props {:class "w-8 h-8"
                                                        :src "icons/progress-circle.svg"}))
                                   (dom/div (dom/props {:class "prose whitespace-pre-wrap px-4 pt-1 max-w-[600px]"}) 
                                            (dom/text state)))))))))


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
                                        (.call callback nil)))))]
             (.observe resize-observer observed-element))))

(e/defn Conversation []
  (e/client
   (let [convo-id (e/watch !active-conversation)
         conversation-entity (e/watch !conversation-entity)
         {:keys [prompt image full-name name]} conversation-entity
         response-states (e/server (e/watch rag/!response-states))
         !chat-container (atom nil)
         !is-at-bottom (atom false)
         scroll-to-bottom (fn []
                            (when-let [container @!chat-container]
                              (when @!is-at-bottom
                                (println "Scrolling to bottom")
                                (set! (.-scrollTop container) (.-scrollHeight container)))))]
     (dom/div
      (dom/props {:class "max-h-full overflow-x-hidden"})
      (reset! !chat-container dom/node)
      (dom/on "scroll" (e/fn [e]
                         (let [target (.-target e)
                               scroll-bottom (+ (.-scrollTop target) (.-clientHeight target))
                               scroll-height (.-scrollHeight target)
                               at-bottom (<= (- scroll-height scroll-bottom) 1.0)]
                           (when at-bottom
                             (println "Scrolling, at bottom?" at-bottom "scroll values - bottom:" scroll-bottom 
                                      "height:" scroll-height "diff:" (- scroll-height scroll-bottom)))
                           (reset! !is-at-bottom at-bottom))))

      (when
       (and (some? convo-id)
            (some? conversation-entity))
        (let [messages (e/server (e/offload #(rag/prepare-conversation db convo-id conversation-entity)))]
          (dom/div
           (dom/props {:class "max-h-full overflow-x-hidden"})
           (observe-resize dom/node scroll-to-bottom)

           (dom/div
            (dom/props {:class "flex flex-col stretch justify-center items-center h-full lg:max-w-3xl mx-auto gap-4"})

            (dom/div (dom/props {:class "flex flex-col gap-8 items-center"})

                     #_(dom/img (dom/props {:class "w-48 mx-auto rounded-full"
                                            :src image}))
                     (dom/h1 (dom/props {:class "text-2xl"}) (dom/text (or full-name name))))
            (when messages ;todo: check if this is still needed
              (e/for [msg (butlast messages)]
                (RenderMsg. msg false))
              (RenderMsg. (last messages) true)
              (when-let [rs (first response-states)] (ResponseState. rs)))

            (let [stream-msgs (e/server (e/watch !stream-msgs))]
              (when (:streaming (get stream-msgs convo-id))
                (when-let [content (:content (get stream-msgs convo-id))]
                  (BotMsg. content))))

            (PromptInput. {:convo-id convo-id
                           :messages messages})))))))))


(e/defn ConversationList [conversations]
  (e/client
    (let [inside-folder-atom? (atom false)
          inside-folder? (e/watch inside-folder-atom?)
          !edit-conversation (atom false)
          edit-conversation (e/watch !edit-conversation)
          active-conversation (e/watch !active-conversation)
          conversation-entity (e/watch !conversation-entity)]
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
                                           (reset! !conversation-entity entity)))
                                       (reset! !active-conversation convo-id)
                                       (reset! !view-main :conversation)))
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
                                                        (when (= convo-id @!active-conversation)
                                                          (reset! !active-conversation nil))
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
                  conversations (e/server (e/offload #(conversations-in-folder db folder-id)))]
              (dom/div (dom/props {:class "relative flex items-center"})
                (if-not (and editing? (= :edit (:action edit-folder)))
                  (dom/button (dom/props {:class (str (when (= folder-id (e/watch !folder-dragged-to)) "bg-[#343541]/90 ") "flex w-full cursor-pointer items-center gap-3 rounded-lg p-3 text-sm transition-colors duration-200 hover:bg-[#343541]/90")}) 
                    (dom/on "click" (e/fn [_] (if-not open-folder?
                                                (swap! !open-folders conj folder-id)
                                                (swap! !open-folders disj folder-id)))) 
                    (dom/img (dom/props {:src (if-not open-folder? "icons/old/right-arrow.svg" "icons/old/down-arrow.svg")}))
                    (dom/text name))
                  (dom/div (dom/props {:class "flex w-full items-center gap-3 rounded-lg bg-[#343541]/90 p-3"})
                    (dom/img (dom/props {:src "icons/old/down-arrow.svg"})) 
                    (dom/input (dom/props {:class "mr-12 flex-1 overflow-hidden overflow-ellipsis border-neutral-400 bg-transparent text-left text-[12.5px] leading-3 text-white outline-none focus:border-neutral-100"
                                           :value name})
                      (dom/on "keyup" (e/fn [e]
                                        (when-some [v (not-empty (.. e -target -value))]
                                          (swap! !edit-folder assoc :changes v))))
                      (.focus dom/node))))
                (dom/div (dom/props {:class "absolute right-1 z-10 flex text-gray-300"})
                  (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                    (if editing?
                      (dom/on "click" (e/fn [_]
                                        (case (:action edit-folder)
                                          :delete (e/server (e/offload #(delete-folder conn eid))
                                                    nil)
                                          :edit (let [new-folder-name (:changes @!edit-folder)]
                                                  (e/server
                                                    (e/offload #(rename-folder conn folder-id new-folder-name))
                                                    nil)
                                                  (reset! !edit-folder false)))))
                      (dom/on "click" (e/fn [_] (reset! !edit-folder {:folder-id folder-id
                                                                      :action :edit}))))
                    (dom/img (dom/props {:src (if editing?
                                                "icons/old/tick.svg"
                                                "icons/old/edit.svg")})))
                  (ui/button
                    (e/fn []
                      (if editing?
                        (reset! !edit-folder nil)
                        (reset! !edit-folder {:folder-id folder-id
                                              :action :delete})))
                    (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                    (dom/img (dom/props {:src (if editing?
                                                "icons/old/x.svg"
                                                "icons/old/delete.svg")})))))
              (when (and (seq conversations) open-folder?)
                (ConversationList. conversations)))))))))

(defonce !prepared-opts (atom nil))

(e/defn HandleRegistration []
  (e/client 
   (println "this is a test")
   (let [create-opts (e/server
                      (let [current-user (:user-id (auth/verify-token (get-in e/http-request [:cookies "auth-token" :value])))
                            session-id (get-in e/http-request [:headers "sec-websocket-key"])]
                        (webauthn/create-public-key-options
                         session-id
                         {:name current-user
                          :display-name current-user
                          :rp-name "company-name"
                          :rp-id "localhost"})))
         cb #(reset! webauthn/!created-key %) 
         prepared-opts (webauthn/prepare-for-creation create-opts)]
     (reset! !prepared-opts create-opts)
     (webauthn/create-credential prepared-opts cb)
     nil)))



#_(e/defn SessionTimer []
  (e/client
   (let [jwt-expiry (e/server (:expiry (auth/verify-token (get-in e/http-request [:cookies "auth-token" :value]))))
         positive-duration? (fn [d] (t/< (t/new-duration 0 :seconds) d))
         !t-between (atom (t/between (t/now) (t/instant jwt-expiry))) t-between (e/watch !t-between)
         !interval-running (atom nil) interval-running (e/watch !interval-running)
         _ (when-not interval-running
             (println "Setting interval")
             (reset! !interval-running
                     (js/setInterval #(reset! !t-between
                                              (t/between (t/now) (t/instant jwt-expiry))) 1000)))
         time-dist [(t/hours t-between)
                    (t/minutes t-between)
                    (t/seconds t-between)]
         [hours minutes seconds] time-dist
         message (cond
                   (<= 1 hours) (str hours " hour and " (mod minutes 60) " minutes")
                   (<= 5 minutes) (str minutes " minutes")
                   (< 0 minutes) (str minutes " minutes " (mod seconds 60) " seconds")
                   :else (str seconds " seconds"))]
     
     ;; When session expires cancel the websocket session
     (when-not (positive-duration? t-between)
       (set! (.-href js/window.location) "/login"))
     
     ;; Handle when a new passkey is generated 
     (dom/p (dom/text "Created key: " (str (e/watch webauthn/!created-key))))
     (dom/p (dom/text "Session challenges: " (e/server (e/watch webauthn/!session-challenges))))
     (when-let [created-key (e/watch webauthn/!created-key)]
       (let [data (webauthn/serialize-public-key-credential created-key)]
         (e/server
          (let [conn @delayed-connection
                session-id (get-in e/http-request [:headers "sec-websocket-key"])
                success-cb (fn [username passkey]
                             (let [passkey-str (pr-str (-> passkey
                                                           (update-in [:public-key -2] webauthn/byte-array-to-base64)
                                                           (update-in [:public-key -3] webauthn/byte-array-to-base64)))]
                               (println "This is the username: " username)
                               (println "this is the auth-data: " (get-in passkey [:public-key -2]))
                               (println "this is the auth-data: " (webauthn/byte-array-to-base64 (get-in passkey [:public-key -2])))
                               (println "round trip: " (webauthn/base64-to-byte-array (webauthn/byte-array-to-base64 (get-in passkey [:public-key -2]))))
                               (println "cose map: " (update-in passkey [:public-key -2] webauthn/byte-array-to-base64))
                               (d/transact conn [{:db/id [:user/email username]
                                                  :user/key-created (str (t/now))
                                                  :user/key passkey-str}])))]
            (println "This is the session-id: " session-id)
            (e/offload #(webauthn/handle-new-user-creds session-id data success-cb)))
          nil)))
     
     ;; View for session time and creating a passkey
     (dom/div (dom/props {:class "p-4 border rounded flex flex-col gap-4 bg-red-100"})
              (dom/div
               (dom/p (dom/text "Session:"))
               (dom/p (dom/props {:class "font-bold"})
                      (dom/text message)))
              (dom/div
               (dom/p (dom/props {:class "text-xs mb-1 font-light"})
                      (dom/text "Complete registration"))
               (dom/button
                (dom/props {:class "px-4 py-2 rounded bg-black text-white hover:bg-slate-800"})
                (dom/on "click" (e/fn [e] (HandleRegistration.)))
                (dom/text "Create passkey")))))))


(e/defn NewThread []
  (e/server
   (println "transacting...")
   (let [entity-id (-> entities-cfg
                       :entities
                       first
                       :id)
         new-convo-id 
         (e/offload #(db/transact-new-msg-thread2 db/conn
                                                  (-> entities-cfg
                                                      :entities
                                                      first
                                                      :id)))]
     (println "New thread : " new-convo-id)
     (e/client 
      (let [entity (first (filter #(= (:id %) entity-id) (:entities entities-cfg)))] 
        (reset! !conversation-entity entity))
      (reset! !view-main :conversation)
      (reset! !active-conversation (:conversation-id new-convo-id)))
     new-convo-id))) 

(e/defn LeftSidebar []
  (e/client
    (when (e/watch !sidebar?)
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
            (ui/button
             NewThread
             (dom/props {:class local-btn-style})
             (dom/div
              (dom/props {:class "bg-gray-300 text-black text-sm font-bold px-2.5 py-0.5 rounded flex items-center gap-2"})
              (dom/span (dom/text "Ny tråd"))
              (dom/span (dom/img (dom/props {:class "w-4" :src "icons/pencil.svg"}))))
             )))




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
         (dom/div (dom/props {:class "flex flex-col items-center space-y-1 border-t border-white/20 pt-1 text-sm"})
                  (let [stream? (e/server (e/watch !stream?))]
                    #_(ui/button
                     (e/fn [] (e/server (swap! !stream? not)))
                     (dom/props {:class (str "px-4 py-2 rounded"
                                             (if stream? " bg-blue-500" " bg-red-500"))})
                     (dom/text "Stream message? " stream?)))
                  (if-not clear-conversations?
                    (ui/button
                     (e/fn [] (reset! !clear-conversations? true))
                     (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 transition-colors duration-200 hover:bg-gray-500/10"})
                     (dom/text "Clear conversations"))
                    (dom/div (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 transition-colors duration-200 hover:bg-gray-500/10"})
                             (dom/img (dom/props {:src "icons/old/delete.svg"}))
                             (dom/text "Are you sure?")
                             (dom/div (dom/props {:class "right-1 z-10 flex text-gray-300"})
                                      (ui/button (e/fn []
                                                   (e/server (e/offload #(clear-all-conversations conn)) nil)
                                                   (reset! !active-conversation nil)
                                                   (reset! !clear-conversations? false))
                                                 (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                                                 (dom/img (dom/props {:src "icons/old/tick.svg"})))
                                      (ui/button (e/fn [] (reset! !clear-conversations? false))
                                                 (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                                                 (dom/img (dom/props {:src "icons/old/x.svg"}))))))))))))



(e/defn EntitySelector []
  (e/client
    (let [EntityCard (e/fn [id title img-src]
                       (ui/button (e/fn []
                                    (let [entity (some #(when (= (:id %) id) %) (:entities entities-cfg))]
                                      (when entity
                                        (reset! !view-main :pre-conversation)
                                        (reset! !conversation-entity entity))))
                                  (dom/props {:class "flex flex-col gap-4 items-center hover:scale-110 hover:shadow-lg shadow rounded p-4 transition-all ease-in duration-150"}) ;bg-[#202123]
                                  (dom/img (dom/props {:class "w-48 mx-auto rounded"
                                                       :src img-src}))
                                  (dom/p (dom/props {:class "text-bold text-lg"})
                                         (dom/text title))))]
      (dom/div (dom/props {:class "flex justify-center items-center h-full gap-8"})
        (e/for-by identity [{:keys [id name image]} (:entities entities-cfg)]
          (EntityCard. id name image))))))

#?(:cljs (defn get-from-local-storage [key]
           (.getItem js/localStorage key)))


(e/defn AuthAdminDashboard []
  (e/client
   (dom/div 
    (dom/props {:class "max-h-full overflow-x-hidden"}) 
    (dom/div
     (dom/props {:class "p-8 flex flex-col gap-4"})
     (let [token (e/client (get-from-local-storage "auth-token"))]
       (dom/div
        (dom/p (dom/text "Local storage JWT: " token))
        (dom/p (dom/text "Local storage JWT unsigned: " (e/server (auth/verify-token token))))
        (let [http-cookie-jwt (e/server (get-in e/http-request [:cookies "auth-token" :value]))]
          (dom/p
           (dom/text
            "created-by: "
            (e/server
             (let [user-email (:user-id (auth/verify-token
                                         (get-in e/http-request [:cookies "auth-token" :value])))]
               (e/offload #(fetch-user-id db user-email))))))
          (dom/p (dom/text "HTTP Only cookie: " http-cookie-jwt))
          (let [cookie (e/server (auth/verify-token http-cookie-jwt))
                jwt-expiry (:expiry cookie)]
            (dom/p (dom/text "HTTP Only cookie: " cookie))
            (dom/p (dom/text "HTTP Only cookie user: " (:user-id cookie)))
            (dom/p (dom/text "HTTP Only cookie expiry: " jwt-expiry))
            #_(dom/p (dom/text "HTTP Only cookie expiry intant: " (t/instant jwt-expiry)))

            #_(let [!t-between (atom (t/between (t/now) (t/instant jwt-expiry)))
                    t-between (e/watch !t-between)
                    !interval-running (atom nil) interval-running (e/watch !interval-running)
                    positive-duration? (fn [d] (t/< (t/new-duration 0 :seconds) d))
                    _ (when-not interval-running
                        (println "Setting interval")
                        (reset! !interval-running
                                (js/setInterval #(reset! !t-between
                                                         (t/between (t/now) (t/instant jwt-expiry))) 1000)))
                    _ (println "the running interval" interval-running)
                    time-dist [(t/days t-between)
                               (t/hours t-between)
                               (t/minutes t-between)
                               (t/seconds t-between)]
                    [days hours minutes seconds] time-dist
                    message (cond
                              (< 1 days) (str days " days")
                              (< 24 hours) (str days " day")
                              (and (< hours 24) (< 2 hours)) (str hours " hours")
                              (< 1 hours) (str hours " hour and " minutes " minutes")
                              :else (str minutes " minutes " (mod seconds 60) "seconds"))]
                (dom/p (dom/text "Positive duration: " (positive-duration? t-between)))
                (when-not (positive-duration? t-between)
                  (set! (.-href js/window.location) "/auth"))
                (dom/p (dom/text t-between))
                (dom/p (dom/text message)))))))

     (dom/button
      (dom/props {:class "px-4 py-2 bg-black text-white rounded"})
      (dom/on "click" (e/fn [e] (set! (.-href js/window.location) "/logout")))
      (dom/text "Sign out"))
                          ;;
     (let [!email (atom nil) email (e/watch !email)]
       (dom/div
        (dom/props {:class "flex gap-4"})
        (dom/input
         (dom/props {:class "px-4 py-2 border rounded"
                     :placeholder "Enter email"
                     :value email})
         (dom/on "keyup" (e/fn [e]
                           (if-some [v (not-empty (.. e -target -value))]
                             (reset! !email v)
                             (reset! !email nil)))))
        (dom/button (dom/props {:class "px-4 py-2 rounded bg-black hover:bg-slate-800 text-white"})
                    (dom/on "click" (e/fn [_]
                                      (when-let [email @!email]
                                        (e/server
                                         (auth/send-confirmation-code "kunnskap@digdir.cloud" (auth/generate-confirmation-code email))
                                         nil))))
                    (dom/text "Send Code"))
        (dom/button
         (dom/props {:class "px-4 py-2 rounded bg-black hover:bg-slate-800 text-white"})
         (dom/on "click" (e/fn [_]
                           (when-let [email @!email]
                             (e/server
                              (let [current-user (:user-id (auth/verify-token (get-in e/http-request [:cookies "auth-token" :value])))
                                    admin-id (e/offload #(fetch-user-id db current-user))]
                                (e/offload #(auth/create-new-user {:email email
                                                                   :creator-id admin-id}))
                                nil)
                              (auth/generate-confirmation-code email)
                              nil))))
         (dom/text "Generate Code"))))

     (dom/p (dom/text "Auth db"))
     (dom/pre (dom/text (rhizome/pretty-print (e/server (e/offload #(auth/all-accounts auth-conn))))))


     (dom/p (dom/text "Active Confirmation Codes"))
     (dom/ul (dom/props {:class "flex flex-col gap-2"})
             (e/for-by identity [user-code (e/server (map (fn [[key value]] [key (update value :expiry str)])
                                                          (e/watch auth/confirmation-codes)))]

                       (dom/li (dom/props {:class "flex"})
                               (dom/p (dom/text user-code))
                               (dom/button
                                (dom/props {:class "px-2 py-1 rounded bg-black text-white"})
                                (dom/on "click" (e/fn [_]
                                                  (e/server
                                                   (swap! auth/confirmation-codes dissoc (first user-code))
                                                   nil)))
                                (dom/text "Remove")))))))))

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
        (dom/text "Kjapt og enkelt."))))))

   #_(dom/div
      (dom/props {:class "h-[calc(100vh-10rem)] w-full flex flex-col"})
      (dom/div
       (dom/props {:class "mt-auto flex flex-col items-center mx-auto max-w-3xl px-4 "})

       (dom/div
        (dom/props {:class "flex flex-col items-center mx-auto max-w-3xl px-4"})
        (dom/div
         (dom/props {:class "w-full max-w-[600px]"})
         (dom/div
          (dom/h2
           (dom/props {:class "text-2xl font-bold"})
           (dom/text "Presis og pålitelig innsikt,"))
          (dom/h2
           (dom/props {:class "text-2xl font-bold"})
           (dom/text "skreddersydd for deg.")))

         (dom/p
          (dom/text "Skriv stikkord om hva du leter etter:"))

         (dom/ul
          (dom/props {:class "list-disc px-4"})
          (dom/li
           (dom/text "Type dokumenter (tildelingsbrev, årsrapport)"))
          (dom/li
           (dom/text "Årstall"))
          (dom/li
           (dom/text "Bransje eller sektor (f.eks teknologi, offentlig sektor)"))
          (dom/li
           (dom/text "Tema (bærekraft, likestilling, innovasjon)")))))))))


(e/defn MainView []
  (e/client
    (dom/div (dom/props {:class "flex flex-1 h-full w-full"})
      (dom/div (dom/props {:class "relative flex-1 overflow-hidden pb-[120px]"})
               (case (e/watch !view-main)
                 :home (Home.)
                 :conversation (Conversation.)
                 :dashboard (AuthAdminDashboard.))
               (when (e/watch debug/!debug?)
                 (debug/DBInspector. {:!active-conversation !active-conversation
                                      :!conversation-entity !conversation-entity}))))))

(e/defn Topbar []
  (e/client
   (let [sidebar? (e/watch !sidebar?)
         conversation-entity (e/watch !conversation-entity)]
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

(e/defn Main [ring-request]
  (e/server
    (binding [db (e/watch @delayed-connection)
              auth-conn (e/watch @delayed-connection)
              e/http-request ring-request]
      (e/client
        (binding [dom/node js/document.body]
          (Topbar.)
          (dom/main (dom/props {:class "flex h-full w-screen flex-col text-sm absolute top-0 pt-12"})
            (dom/div (dom/props {:class "flex h-full w-full pt-[48px] sm:pt-0 items-start"})
              ;; Topbar 
              (LeftSidebar.)
              (MainView.)
              (debug/DebugController.))))))))
