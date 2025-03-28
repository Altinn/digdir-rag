(ns chat-app.chat
  (:require [chat-app.ui :as ui]
            [chat-app.rhizome :as rhizome]
            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.electric-ui4 :as ui4]
            [nano-id.core :refer [nano-id]]
            [clojure.string :as str]
            [markdown.core :as md2]
            [chat-app.debug :as debug]
            [chat-app.filters :refer [FilterMsg]]
            #?(:clj [models.db :refer [conn] :as db])
            #?(:clj [chat-app.rag :as rag])))

;; Chat state
#?(:cljs (defonce !active-conversation (atom nil)))
#?(:cljs (defonce !conversation-entity (atom nil)))
#?(:clj (defonce !stream-msgs (atom {}))) ;{:convo-id nil :content nil :streaming false}
#?(:clj (defonce !wait? (atom false)))
#?(:clj (defonce !stream? (atom false)))

;; Message Components
(e/defn BotMsg [msg-map]
  (e/client
    (let [conversation-entity (e/watch !conversation-entity)
          {:message/keys [created id text role kind voice]} msg-map
          {:keys [name image]} conversation-entity]
      (dom/div (dom/props {:class "flex w-full flex-col items-start"})
               (dom/div (dom/props {:class "flex"})
                        (dom/img (dom/props {:class "rounded-full w-8 h-8"
                                             :src image}))
                        (dom/div (dom/props {:class "prose whitespace-pre-wrap px-4 pt-1 max-w-[600px]"})
                                 (case kind
                                   :kind/html (set! (.-innerHTML dom/node) text)
                                   :kind/markdown (set! (.-innerHTML dom/node) (md2/md-to-html-string text))
                                   (set! (.-innerHTML dom/node) (md2/md-to-html-string text)))))))))

(e/defn UserMsg [msg]
  (e/client
    (dom/div (dom/props {:class "flex w-full flex-col items-start"})
      (let [msg-hovered? (dom/Hovered?.)]
        (dom/div (dom/props {:class "relative max-w-[70%] rounded-3xl bg-[#b8e9f8] px-5 py-2.5"})
         ;; disabling edit button for now, should make it configurable                  
                 #_(ui4/button (e/fn [])
                              (dom/props {:class (str "absolute -left-12 top-1 hover:bg-[#f4f4f4] rounded-full flex justify-center items-center w-8 h-8"
                                                      (if-not msg-hovered?
                                                        " invisible"
                                                        " visible"))
                                          :title "Edit message"})
                              (dom/img (dom/props {:class "w-4" :src "icons/pencil.svg"})))
                 (dom/p (dom/text msg)))))))

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
           (reset! ui/!view-main :conversation))))
      nil))))

(e/defn PromptInput [{:keys [convo-id messages]}]
  (e/client
  ;; TODO: add the system prompt to the message list
    (let [!input-node (atom nil)
          wait? (e/server (e/watch !wait?))]
      (dom/div (dom/props {:class (str (if (rhizome/mobile-device?) "bottom-8" "bottom-0") " absolute left-0 w-full border-transparent bg-gradient-to-b from-transparent via-white to-white pt-6 md:pt-1")})
        (dom/div (dom/props {:class "stretch mx-2 mt-4 flex flex-row gap-3 last:mb-2 md:mx-4 md:mt-[52px] md:last:mb-6 lg:mx-auto lg:max-w-3xl"})
          (dom/div (dom/props {:class "flex flex-col w-full gap-2"})
                   (dom/div (dom/props {:class "relative flex w-full flex-grow flex-col rounded-md border border-black/10 bg-white shadow-[0_0_10px_rgba(0,0,0,0.10)] sm:mx-4"})
                            (dom/textarea
                             (dom/props {:id "prompt-input"
                                         :class "min-h-[44px] max-h-[200px] m-0 w-full resize-none border-0 bg-transparent p-0 py-2 pr-8 pl-10 text-black md:py-3 md:pl-10 overflow-y-auto"
                                         :placeholder (str "Hva kan jeg hjelpe deg med?")
                                         :value ""
                                         :rows "4"
                                         :disabled wait?})
                             (reset! !input-node dom/node)
                             (.focus dom/node)
                             (dom/on "keydown"
                                     (e/fn [e]
                                       (when (= "Enter" (.-key e))
                                         ;; Only submit message if shift key is not pressed
                                         (if (.-shiftKey e)
                                           ;; Allow shift+enter to create a new line
                                           nil
                                           ;; Regular enter submits the message
                                           (do
                                             (.preventDefault e)
                                             (when-some [v (not-empty (.. e -target -value))]
                                               (when-not (str/blank? v)
                                                 (HandleChatMsg. v
                                                                 (-> (last messages) :message.filter/value boolean))))
                                             (set! (.-value @!input-node) "")))))))
                            (let [wait? (e/server (e/watch !wait?))]
                              (ui4/button
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
                                 (dom/img (dom/props {:src "icons/circle-stop.svg"}))))))
                   
            ;; Disclaimer text space below the input field
                   (dom/div (dom/props {:class "mt-3 px-4 text-xs text-gray-500 text-center"})
                            (dom/text "​Kunnskapsassistenten kan gjøre feil. Husk å sjekke viktig informasjon."))))))))

(e/defn Conversation []
  (e/server 
   (let [db (e/watch conn)]
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
                                  at-bottom (<= (- scroll-height scroll-bottom) 5.0)]
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
              (ui/observe-resize dom/node scroll-to-bottom)

              (dom/div
               (dom/props {:class "flex flex-col stretch justify-center items-center h-full lg:max-w-3xl mx-auto gap-4 pb-[110px]"})

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
                              :messages messages})))))))))))

