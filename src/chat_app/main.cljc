(ns chat-app.main
  (:require contrib.str
            #?(:clj [datahike.api :as d])
            #?(:clj [datahike-jdbc.core])
            #?(:cljs [goog.userAgent :as ua])
            #?(:cljs [goog.labs.userAgent.platform :as platform])
            #?(:clj [nextjournal.markdown :as md])
            #?(:clj [nextjournal.markdown.transform :as md.transform])
            #?(:clj [hiccup2.core :as h])
            [clojure.edn :as edn]
            [markdown.core :as md2]
            [clojure.string :as str]
            [clojure.string :as str]
            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [nano-id.core :refer [nano-id]]
            [hyperfiddle.electric-ui4 :as ui]
            [tick.core :as t]
            ;; [tick.locale-en-us]
            #?(:clj [wkok.openai-clojure.api :as openai])

            #?(:clj [chat-app.auth :as auth])
            [chat-app.webauthn :as webauthn]

            #?(:clj [models.db :as db :refer [delayed-connection]])
            #?(:clj [chat-app.rag :as rag])))

;; Can possibly remove the snapshot usage in next version of Electric
;; https://clojurians.slack.com/archives/C7Q9GSHFV/p1693947757659229?thread_ts=1693946525.050339&cid=C7Q9GSHFV

(e/def config-filename (e/server (System/getenv "ENTITY_CONFIG_FILE")))
(e/def entities-cfg (e/server (read-string (slurp config-filename))))
(e/def dh-conn) ; injected database ref; Electric defs are always dynamic
(e/def auth-conn)

#?(:cljs (defonce !view-main (atom :entity-selection)))
#?(:cljs (defonce !conversation-entity (atom nil))) ;; now holds the entire entity config, not just the name
#?(:cljs (defonce !view-main-prev (atom nil)))
#?(:cljs (defonce view-main-watcher (add-watch !view-main :main-prev (fn [_k _r os _ns]
                                                                       (println "this is os: " os)
                                                                       (when-not (= os :settings))
                                                                       (reset! !view-main-prev os)))))

#?(:cljs (defonce !edit-folder (atom nil)))
#?(:cljs (defonce !active-conversation (atom nil)))
#?(:cljs (defonce !convo-dragged (atom nil)))
#?(:cljs (defonce !prompt-dragged (atom nil)))
#?(:cljs (defonce !folder-dragged-to (atom nil)))
#?(:cljs (defonce !open-folders (atom #{})))

#?(:cljs (defonce !prompt-editor (atom {:action nil
                                        :name nil
                                        :text nil})))

#?(:cljs (def !select-prompt? (atom false)))
#?(:clj (defonce !stream-msgs (atom {}))) ;{:convo-id nil :content nil :streaming false}

(e/def stream-msgs (e/server (e/watch !stream-msgs)))
#?(:clj (defonce !gpt-model (atom "GPT-4")))

#?(:cljs (defonce !sidebar? (atom true)))
(e/def sidebar? (e/client (e/watch !sidebar?)))
#?(:cljs (defonce !prompt-sidebar? (atom false)))
(e/def prompt-sidebar? (e/client (e/watch !prompt-sidebar?)))

#?(:cljs (defonce !system-prompt (atom "")))
(defonce !debug? (atom false))
(e/def debug? (e/client (e/watch !debug?)))

(e/def open-folders (e/client (e/watch !open-folders)))
(e/def view-main (e/client (e/watch !view-main)))
(e/def conversation-entity (e/client (e/watch !conversation-entity)))
(e/def edit-folder (e/client (e/watch !edit-folder)))
(e/def active-conversation (e/client (e/watch !active-conversation)))
(e/def convo-dragged (e/client (e/watch !convo-dragged)))
(e/def prompt-dragged (e/client (e/watch !prompt-dragged)))
(e/def folder-dragged-to (e/client (e/watch !folder-dragged-to)))
(e/def prompt-editor (e/client (e/watch !prompt-editor)))
(e/def select-prompt? (e/client (e/watch !select-prompt?)))
(e/def system-prompt (e/client (e/watch !system-prompt)))
(e/def response-states (e/server (e/watch rag/!response-states)))

(def new-chat-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-plus\"><path d=\"M12 5l0 14\"></path><path d=\"M5 12l14 0\"></path></svg>")
(def search-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-folder-plus\"><path d=\"M12 19h-7a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2h4l3 3h7a2 2 0 0 1 2 2v3.5\"></path><path d=\"M16 19h6\"></path><path d=\"M19 16v6\"></path></svg>")
(def no-data-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"mx-auto mb-3\"><path d=\"M12 5h9\"></path><path d=\"M3 10h7\"></path><path d=\"M18 10h1\"></path><path d=\"M5 15h5\"></path><path d=\"M14 15h1m4 0h2\"></path><path d=\"M3 20h9m4 0h3\"></path><path d=\"M3 3l18 18\"></path></svg>")
(def side-bar-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-arrow-bar-left\"><path d=\"M4 12l10 0\"></path><path d=\"M4 12l4 4\"></path><path d=\"M4 12l4 -4\"></path><path d=\"M20 4l0 16\"></path></svg>")
(def user-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"30\" height=\"30\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-user\"><path d=\"M8 7a4 4 0 1 0 8 0a4 4 0 0 0 -8 0\"></path><path d=\"M6 21v-2a4 4 0 0 1 4 -4h4a4 4 0 0 1 4 4v2\"></path></svg>")
(def bot-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"30\" height=\"30\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-robot\"><path d=\"M7 7h10a2 2 0 0 1 2 2v1l1 1v3l-1 1v3a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-3l-1 -1v-3l1 -1v-1a2 2 0 0 1 2 -2z\"></path><path d=\"M10 16h4\"></path><circle cx=\"8.5\" cy=\"11.5\" r=\".5\" fill=\"currentColor\"></circle><circle cx=\"15.5\" cy=\"11.5\" r=\".5\" fill=\"currentColor\"></circle><path d=\"M9 7l-1 -4\"></path><path d=\"M15 7l1 -4\"></path></svg>")
(def edit-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-edit\"><path d=\"M7 7h-1a2 2 0 0 0 -2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2 -2v-1\"></path><path d=\"M20.385 6.585a2.1 2.1 0 0 0 -2.97 -2.97l-8.415 8.385v3h3l8.385 -8.415z\"></path><path d=\"M16 5l3 3\"></path></svg>")
(def delete-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-trash\"><path d=\"M4 7l16 0\"></path><path d=\"M10 11l0 6\"></path><path d=\"M14 11l0 6\"></path><path d=\"M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2 -2l1 -12\"></path><path d=\"M9 7v-3a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v3\"></path></svg>")
(def copy-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-copy\"><path d=\"M8 8m0 2a2 2 0 0 1 2 -2h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2z\"></path><path d=\"M16 8v-2a2 2 0 0 0 -2 -2h-8a2 2 0 0 0 -2 2v8a2 2 0 0 0 2 2h2\"></path></svg>")
(def send-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-send\"><path d=\"M10 14l11 -11\"></path><path d=\"M21 3l-6.5 18a.55 .55 0 0 1 -1 0l-3.5 -7l-7 -3.5a.55 .55 0 0 1 0 -1l18 -6.5\"></path></svg>")
(def msg-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-message\"><path d=\"M8 9h8\"></path><path d=\"M8 13h6\"></path><path d=\"M18 4a3 3 0 0 1 3 3v8a3 3 0 0 1 -3 3h-5l-5 3v-3h-2a3 3 0 0 1 -3 -3v-8a3 3 0 0 1 3 -3h12z\"></path></svg>")
(def tick-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-check\"><path d=\"M5 12l5 5l10 -10\"></path></svg>")
(def x-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-x\"><path d=\"M18 6l-12 12\"></path><path d=\"M6 6l12 12\"></path></svg>")
(def settings-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-settings\"><path d=\"M10.325 4.317c.426 -1.756 2.924 -1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543 -.94 3.31 .826 2.37 2.37a1.724 1.724 0 0 0 1.065 2.572c1.756 .426 1.756 2.924 0 3.35a1.724 1.724 0 0 0 -1.066 2.573c.94 1.543 -.826 3.31 -2.37 2.37a1.724 1.724 0 0 0 -2.572 1.065c-.426 1.756 -2.924 1.756 -3.35 0a1.724 1.724 0 0 0 -2.573 -1.066c-1.543 .94 -3.31 -.826 -2.37 -2.37a1.724 1.724 0 0 0 -1.065 -2.572c-1.756 -.426 -1.756 -2.924 0 -3.35a1.724 1.724 0 0 0 1.066 -2.573c-.94 -1.543 .826 -3.31 2.37 -2.37c1 .608 2.296 .07 2.572 -1.065z\"></path><path d=\"M9 12a3 3 0 1 0 6 0a3 3 0 0 0 -6 0\"></path></svg>")
(def folder-arrow-icon "<svg xmlns=\"http://www.w3.org/2000/sv\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-caret-right\"><path d=\"M10 18l6 -6l-6 -6v12\"></path></svg>")
(def folder-arrow-icon-down "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-caret-down\"><path d=\"M6 10l6 6l6 -6h-12\"></path></svg>")
(def transfer-data-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-file-export\"><path d=\"M14 3v4a1 1 0 0 0 1 1h4\"></path><path d=\"M11.5 21h-4.5a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v5m-5 6h7m-3 -3l3 3l-3 3\"></path></svg>")
(def key-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-key\"><path d=\"M16.555 3.843l3.602 3.602a2.877 2.877 0 0 1 0 4.069l-2.643 2.643a2.877 2.877 0 0 1 -4.069 0l-.301 -.301l-6.558 6.558a2 2 0 0 1 -1.239 .578l-.175 .008h-1.172a1 1 0 0 1 -.993 -.883l-.007 -.117v-1.172a2 2 0 0 1 .467 -1.284l.119 -.13l.414 -.414h2v-2h2v-2l2.144 -2.144l-.301 -.301a2.877 2.877 0 0 1 0 -4.069l2.643 -2.643a2.877 2.877 0 0 1 4.069 0z\"></path><path d=\"M15 9h.01\"></path></svg>")
(def prompt-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-bulb-filled\"><path d=\"M4 11a1 1 0 0 1 .117 1.993l-.117 .007h-1a1 1 0 0 1 -.117 -1.993l.117 -.007h1z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M12 2a1 1 0 0 1 .993 .883l.007 .117v1a1 1 0 0 1 -1.993 .117l-.007 -.117v-1a1 1 0 0 1 1 -1z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M21 11a1 1 0 0 1 .117 1.993l-.117 .007h-1a1 1 0 0 1 -.117 -1.993l.117 -.007h1z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M4.893 4.893a1 1 0 0 1 1.32 -.083l.094 .083l.7 .7a1 1 0 0 1 -1.32 1.497l-.094 -.083l-.7 -.7a1 1 0 0 1 0 -1.414z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M17.693 4.893a1 1 0 0 1 1.497 1.32l-.083 .094l-.7 .7a1 1 0 0 1 -1.497 -1.32l.083 -.094l.7 -.7z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M14 18a1 1 0 0 1 1 1a3 3 0 0 1 -6 0a1 1 0 0 1 .883 -.993l.117 -.007h4z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M12 6a6 6 0 0 1 3.6 10.8a1 1 0 0 1 -.471 .192l-.129 .008h-6a1 1 0 0 1 -.6 -.2a6 6 0 0 1 3.6 -10.8z\" fill=\"currentColor\" stroke-width=\"0\"></path></svg>")
(def ext-link-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"inline mr-1\"><path d=\"M11 7h-5a2 2 0 0 0 -2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2 -2v-5\"></path><path d=\"M10 14l10 -10\"></path><path d=\"M15 4l5 0l0 5\"></path></svg>")

#?(:cljs (defn mobile-device? []
           (or (or ua/IPHONE
                   ua/PLATFORM_KNOWN_ ua/ASSUME_IPHONE
                   (platform/isIphone))
               (or ua/ANDROID
                   ua/PLATFORM_KNOWN_ ua/ASSUME_ANDROID
                   (platform/isAndroid)))))

#?(:clj (defn lowercase-includes? [s1 s2]
          (and (string? s1) (string? s2)
               (clojure.string/includes? (clojure.string/lower-case s1) (clojure.string/lower-case s2)))))

#?(:clj (defn parse-text [s]
          (->> (md/parse s)
               md.transform/->hiccup
               h/html
               str)))

#?(:cljs (defn pretty-print [data]
           (with-out-str (cljs.pprint/pprint data))))


;; #?(:clj
;;    (defn fetch-convo-messages
;;      ([dh-conn convo-id-str]
;;       (fetch-convo-messages dh-conn convo-id-str nil))
;;      ([dh-conn convo-id-str voice]
;;       (if (nil? convo-id-str)
;;         (do
;;           (prn "returning nil, convo-id-str is nil")
;;           nil)
;;         (do
;;           (prn "fetching messages for convo id: " convo-id-str)
;;           (let [query (if voice
;;                         '[:find ?msg-created ?msg-id ?msg-text ?msg-role
;;                           :in $ ?conv-id ?voice
;;                           :where
;;                           [?e :conversation/id ?conv-id]
;;                           [?e :conversation/messages ?msg]
;;                           [?msg :message/id ?msg-id]
;;                           [?msg :message/role ?msg-role]
;;                           [?msg :message/voice ?voice]
;;                           [?msg :message/text ?msg-text]
;;                           [?msg :message/created ?msg-created]]
;;                         '[:find ?msg-created ?msg-id ?msg-text ?msg-role ?msg-voice
;;                           :in $ ?conv-id
;;                           :where
;;                           [?e :conversation/id ?conv-id]
;;                           [?e :conversation/messages ?msg]
;;                           [?msg :message/id ?msg-id]
;;                           [?msg :message/role ?msg-role]
;;                           [?msg :message/voice ?msg-voice]
;;                           [?msg :message/text ?msg-text]
;;                           [?msg :message/created ?msg-created]])
;;                 opt-args (if voice [voice] [])]
;;             (sort-by first < (apply d/q query dh-conn convo-id-str opt-args))))))))

#?(:clj
   (defn fetch-convo-messages-mapped
     ([dh-conn convo-id-str]
      (fetch-convo-messages-mapped dh-conn convo-id-str nil))
     ([dh-conn convo-id-str voice]
      (if (nil? convo-id-str)
        (do
          (prn "returning nil, convo-id-str is nil")
          nil)
        (do
          (prn "fetching messages for convo id: " convo-id-str)
          (let [query (if voice
                        '[:find ?msg-created ?msg-id ?msg-text ?msg-role ?msg-kind ?msg-completion
                          :in $ ?conv-id ?msg-voice
                          :where
                          [?e :conversation/id ?conv-id]
                          [?e :conversation/messages ?msg]
                          [?msg :message/id ?msg-id]
                          [?msg :message/role ?msg-role]
                          [?msg :message/voice ?msg-voice]
                          [?msg :message/completion ?msg-completion]
                          [?msg :message/kind ?msg-kind]
                          [?msg :message/text ?msg-text]
                          [?msg :message/created ?msg-created]]
                        '[:find ?msg-created ?msg-id ?msg-text ?msg-role ?msg-kind ?msg-completion ?msg-voice
                          :in $ ?conv-id
                          :where
                          [?e :conversation/id ?conv-id]
                          [?e :conversation/messages ?msg]
                          [?msg :message/id ?msg-id]
                          [?msg :message/role ?msg-role]
                          [?msg :message/voice ?msg-voice]
                          [?msg :message/completion ?msg-completion]
                          [?msg :message/kind ?msg-kind]
                          [?msg :message/text ?msg-text]
                          [?msg :message/created ?msg-created]])
                opt-args (if voice [voice] []) 
                results (apply d/q query dh-conn convo-id-str opt-args)]
            
            (mapv (fn [[created id text role kind completion voice]]
                    {:message/created created
                     :message/id id
                     :message/text text
                     :message/role role
                     :message/voice voice
                     :message/completion completion
                     :message/kind kind}) 
                  (sort-by first < results)
                  )))))))

#?(:clj (defn process-chunk [convo-id data]
          (let [conn @delayed-connection
                delta (get-in data [:choices 0 :delta])
                content (:content delta)]
            (if content
              (swap! !stream-msgs update-in [convo-id :content] (fn [old-content] (str old-content content)))
              (do
                (swap! !stream-msgs assoc-in [convo-id :streaming] false)
                (let [tx-msg (:content (get @!stream-msgs convo-id))]
                  (d/transact conn [{:conversation/id convo-id
                                     :conversation/messages [{:message/id (nano-id)
                                                              :message/text tx-msg
                                                              :message/role :assistant
                                                              :message/voice :assistant
                                                              :message/kind :kind/markdown
                                                              :message/completion true
                                                              :message/created (System/currentTimeMillis)}]}]))
                (swap! !stream-msgs assoc-in [convo-id :content] nil))))))

#?(:clj
   (defn process-rag-response [convo-id rag-response]
     (let [conn @delayed-connection
           full-prompt (:fullPrompt (:prompts rag-response))
           tx-msg (:english_answer rag-response)
           _ (prn "assistant reply:" tx-msg)]
       (d/transact conn [{:conversation/id convo-id
                              :conversation/messages [{:message/id (nano-id)
                                                       :message/text full-prompt
                                                       :message/role :user
                                                       :message/voice :agent
                                                       :message/completion true
                                                       :message/kind :kind/markdown
                                                       :message/created (System/currentTimeMillis)}
                                                      {:message/id (nano-id)
                                                       :message/text tx-msg
                                                       :message/role :assistant
                                                       :message/voice :assistant
                                                       :message/completion true
                                                       :message/kind :kind/markdown
                                                       :message/created (+ 1 (System/currentTimeMillis))}]}]))))

#?(:clj
   (defn answer-new-user-query-with-rag [conversation-entity convo-id user-query]
     (swap! !stream-msgs assoc-in [convo-id :streaming] true)
     (let [conn @delayed-connection
           time-point (System/currentTimeMillis)
           _ (prn "Starting rag-pipeline for query: " user-query)
           _ (swap! rag/!response-states conj "Starting rag-pipeline for query")
           _ (prn "conversation-entity:" conversation-entity)
           {:keys [id prompt image full-name name docs-collection chunks-collection phrases-collection phrase-gen-prompt]} conversation-entity
           _ (d/transact conn [{:conversation/id convo-id
                                :conversation/entity-id id
                                :conversation/topic user-query
                                :conversation/created time-point
                                :conversation/system-prompt "sys-prompt"
                                :conversation/messages [{:message/id (nano-id)
                                                         :message/text "You are a helpful assistant."
                                                         :message/role :system
                                                         :message/voice :agent
                                                         :message/completion true
                                                         :message/kind :kind/text
                                                         :message/created time-point}
                                                        {:message/id (nano-id)
                                                         :message/text user-query
                                                         :message/role :user
                                                         :message/voice :user
                                                         :message/completion false
                                                         :message/kind :kind/markdown
                                                         :message/created (+ 1 time-point)}]}])

           rag-response (rag/rag-pipeline
                         {;; :on-next #(process-chunk convo-id %)
                          :conversation-id convo-id
                          :translated_user_query user-query
                          :original_user_query user-query
                          :user_query_language_name "Norwegian"
                          :promptRagQueryRelax "You have access to a search API that returns relevant documentation.\nYour task is to generate an array of up to 7 search queries that are relevant to this question. Use a variation of related keywords and synonyms for the queries, trying to be as general as possible.\nInclude as many queries as you can think of, including and excluding terms. For example, include queries like ['keyword_1 keyword_2', 'keyword_1', 'keyword_2']. Be creative. The more queries you include, the more likely you are to find relevant results.\n",
                          :promptRagGenerate "Use the following pieces of information to answer the user's question.\nIf you don't know the answer, just say that you don't know, don't try to make up an answer.\n\nContext: {context}\n\nQuestion: {question}\n\nOnly return the helpful answer below.\n\nHelpful answer:\n",

                          ;;  :promptRagQueryRelax
                          ;;  "You have access to a search API that returns relevant documentation.\n"
                          ;;  "Your task is to generate an array of up to 7 search queries that are relevant to this question."
                          ;;  " Use a variation of related keywords and synonyms for the queries, trying to be as general as possible.\n"
                          ;;  "Include as many queries as you can think of, including and excluding terms. "
                          ;;  "For example, include queries like ['keyword_1 keyword_2', 'keyword_1', 'keyword_2']. "
                          ;;  "Be creative. The more queries you include, the more likely you are to find relevant results.\n",

                          ;;  :promptRagGenerate
                          ;;  "Use the following CONTEXT to answer the user's question.\n"
                          ;;  "If you don't know the answer, just say that you don't know, don't try to make up an answer.\n"
                          ;;  "\n<CONTEXT>{context}</CONTEXT> \n\n<QUESTION>\n{question}\n</QUESTION>\n\n"
                          ;;  "Only return the helpful answer below.\n\nHelpful answer:\n",
                          :maxSourceDocCount 200
                          :maxSourceLength 10000
                          :maxContextLength 40000
                          :docsCollectionName docs-collection
                          :chunksCollectionName chunks-collection
                          :phrasesCollectionName phrases-collection
                          :phrase-gen-prompt phrase-gen-prompt
                          :stream_callback_msg1 nil
                          :stream_callback_msg2 nil
                          :streamCallbackFreqSec 2.0
                          :maxResponseTokenCount nil}
                         conn)]
       (process-rag-response convo-id rag-response))))

#?(:clj
   (defn answer-followup-user-query [convo-id user-query]
     (let [conn @delayed-connection
           _ (prn "Storing followup query in db.")
           _ (swap! rag/!response-states conj "Storing followup query in db")
           time-point (System/currentTimeMillis)
           new-messages [;; this is the message exactly as the user types it
                         {:message/id (nano-id)
                          :message/text user-query
                          :message/role :user
                          :message/voice :user
                          :message/completion false
                          :message/kind :kind/markdown
                          :message/created time-point}
                         ;; this message might contain some agent modifications
                         {:message/id (nano-id)
                          :message/text user-query ;; not actually wrapping follow-up user-queries anymore
                          :message/role :user
                          :message/voice :agent
                          :message/completion true
                          :message/kind :kind/markdown
                          :message/created time-point}]
           tx-result (d/transact conn [{:conversation/id convo-id
                                        :conversation/messages new-messages}])
           _ (prn "stored followup query in db, fetching completion-messages")
           messages (vec (fetch-convo-messages-mapped @conn convo-id))
           ;; we send all completion-messages to the llm

           completion-messages (filter #(not= false (:message/completion %)) (concat messages new-messages))
           _ (prn "fetched" (count completion-messages) "completion-messages")

           ;;_ (prn "*** agent-messages: ***" agent-messages)
           _ (prn "Starting LLM call for followup query.")
           _ (swap! rag/!response-states conj "Starting LLM call for followup query")
           openai-messages (into []
                                 (mapv (fn [msg]
                                         {:role (name (:message/role msg))
                                          :content (:message/text msg)})
                                       completion-messages))
           ;;  _ (prn "openai-messages:" openai-messages)
           chat-response
           (if rag/use-azure-openai
             (openai/create-chat-completion
              {:model (rag/env-var "AZURE_OPENAI_DEPLOYMENT_NAME")
               :messages openai-messages
               :temperature 0.1
               :max_tokens nil}
              {:api-key (rag/env-var "AZURE_OPENAI_API_KEY")
               :api-endpoint (rag/env-var "AZURE_OPENAI_ENDPOINT")
               :impl :azure})
             (openai/create-chat-completion
              {:model (System/getenv "OPENAI_API_MODEL_NAME")
               :messages openai-messages
               :temperature 0.1
               :stream false
               :max_tokens nil}))
           _ (prn "chat-response completed. response:" chat-response)
           _ (reset! rag/!response-states [])
           assistant-reply (:content (:message (first (:choices chat-response))))
           _ (d/transact conn [{:conversation/id convo-id
                                :conversation/messages [{:message/id (nano-id)
                                                         :message/text assistant-reply
                                                         :message/role :assistant
                                                         :message/voice :assistant
                                                         :message/completion true
                                                         :message/kind :kind/markdown
                                                         :message/created (System/currentTimeMillis)}]}])]
       assistant-reply)))

#?(:clj (defn stream-chat-completion [convo-id msg-list model api-key]
          (swap! !stream-msgs assoc-in [convo-id :streaming] true)
          (try (openai/create-chat-completion
                {:model model
                 :messages msg-list
                 :stream true
                 :on-next #(process-chunk convo-id %)}
                {:api-key api-key})
               (catch Exception e
                 (println "This is the exception: " e)))))

(e/defn PromptInput [{:keys [convo-id messages selected-model temperature]}]
  (e/client
  ;; TODO: add the system prompt to the message list
   (let [api-key (e/server (e/offload #(d/q '[:find ?v .
                                              :where
                                              [?e :active-key-name ?name]
                                              [?k :key/name ?name]
                                              [?k :key/value ?v]] dh-conn)))
         !input-node (atom nil)]

      (dom/div (dom/props {:class (str (if (mobile-device?) "bottom-8" "bottom-0") " absolute left-0 w-full border-transparent bg-gradient-to-b from-transparent via-white to-white pt-6 dark:border-white/20 dark:via-[#343541] dark:to-[#343541] md:pt-2")})
        (dom/div (dom/props {:class "stretch mx-2 mt-4 flex flex-row gap-3 last:mb-2 md:mx-4 md:mt-[52px] md:last:mb-6 lg:mx-auto md:max-w-[90%] lg:max-w-xl"})
          (dom/div (dom/props {:class "flex flex-col w-full gap-2"})
            (dom/div (dom/props {:class "relative flex w-full flex-grow flex-col rounded-md border border-black/10 bg-white shadow-[0_0_10px_rgba(0,0,0,0.10)] dark:border-gray-900/50 dark:bg-[#40414F] dark:text-white dark:shadow-[0_0_15px_rgba(0,0,0,0.10)] sm:mx-4"})
              (dom/textarea (dom/props {:id "prompt-input"
                                        :class "sm:h-11 m-0 w-full resize-none border-0 bg-transparent p-0 py-2 pr-8 pl-10 text-black dark:bg-transparent dark:text-white md:py-3 md:pl-10"
                                        :placeholder (str "Spør " (:name conversation-entity))
                                        :value ""})
                (reset! !input-node dom/node)
                (dom/on "keydown" (e/fn [e]
                                    (when (= "Enter" (.-key e))
                                      (.preventDefault e)
                                      (when-some [v (not-empty (.. e -target -value))]
                                        (when-not (str/blank? v)
                                          (if-not @!active-conversation
                                            ;; New conversation
                                                                                   (let [convo-id (nano-id)
                                                                                         _ (prn "new convo-id:" convo-id)
                                                                                         _ (prn "user query:" v)]
                                                                                     (e/server (reset! rag/!response-states []))
                                                                                     (reset! !active-conversation convo-id)
                                                                                     (reset! !view-main :conversation)
                                                                                     (e/server
                                                                                      (let [v-str v] ; TODO: figure out why this needs to be done. Seems to be breaking without it.
                                                                                        (e/offload #(answer-new-user-query-with-rag conversation-entity convo-id v-str)))
                                                ;;
                                                                                      nil))

                                            ;; Add messages to an existing conversation 
                                                                                   (let [convo-id @!active-conversation]
                                                                                     (e/server
                                                                                      (let [v-str v]
                                                                                        (when convo-id
                                                                                          (e/offload #(answer-followup-user-query convo-id v-str))))
                                                                                      nil)))))
                                                                             (set! (.-value @!input-node) "")))))
                                         (ui/button (e/fn [] (set! (.-value @!input-node) ""))
                                                    (dom/props {:class "absolute right-2 top-2 rounded-sm p-1 text-neutral-800 opacity-60 hover:bg-neutral-200 hover:text-neutral-900 dark:bg-opacity-50 dark:text-neutral-100 dark:hover:text-neutral-200"})
                                                    (set! (.-innerHTML dom/node) send-icon)))))))))

#?(:cljs (defn copy-to-clipboard [text]
           (if (and (exists? js/navigator.clipboard)
                    (exists? js/navigator.clipboard.writeText))
             (let [promise (.writeText (.-clipboard js/navigator) text)]
               (if (exists? (.-then promise))
                 (.then promise
                        (fn [] (js/console.log "Text copied to clipboard!"))
                        (fn [err] (js/console.error "Failed to copy text to clipboard:" err)))
                 (js/console.error "writeText did not return a Promise")))
             (js/console.error "Clipboard API not supported in this browser"))))

#?(:cljs (defn speak-text [text]
           (if (exists? js/window.speechSynthesis)
             (let [utterance (js/SpeechSynthesisUtterance. text)]
               (.speak js/window.speechSynthesis utterance))
             (js/console.error "Speech Synthesis API is not supported in this browser"))))

(e/defn BotMsg [msg-map]
  (e/client
    (let [{:message/keys [created id text role kind voice]} msg-map
          {:keys [name image]} conversation-entity
          _ (prn "msg: " msg-map)]
      (dom/div (dom/props {:class "flex w-full flex-col items-start"})
        (let [msg-hovered? (dom/Hovered?.)]
          (dom/div (dom/props {:class "flex -ml-12"})
                   (dom/img (dom/props {:class "rounded-full w-8 h-8"
                                        :src image}))
                   
                   (dom/div (dom/props {:class "prose whitespace-pre-wrap px-4 pt-1 max-w-[600px]"})
                            (case kind
                              ;; :kind/hiccup (set! (.-innerHTML dom/node) text)
                              :kind/markdown (set! (.-innerHTML dom/node) (md2/md->html text))
                              (set! (.-innerHTML dom/node) (md2/md->html text)))))

          (dom/div (dom/props {:class (str "msg-controls flex gap-1 mt-4 rounded bg-white border p-2"
                                           (if-not msg-hovered?
                                             " invisible"
                                             " visible "))})
                   (e/for-by identity [{:keys [title file-name action]}
                                       [#_{:title "Read aloud"
                                           :action :read
                                           :file-name "speech"}
                                        {:title "Copy"
                                         :action :copy
                                         :file-name "copy"}
                                        #_{:title "Regenerate"
                                           :action :regenerate
                                           :file-name "refresh-cw"}]]
                             (ui/button (e/fn []
                                          (case action
                                            :copy (copy-to-clipboard text)
                                            :read (speak-text text)
                                            nil)
                                          (println "the action: " action))
                                        (dom/props {:class (str "hover:bg-slate-200 rounded-full flex justify-center items-center w-8 h-8")
                                                    :title title})
                                        (dom/img (dom/props {:class "w-4" :src (str "icons/" file-name ".svg")}))))))))))

(e/defn UserMsg [msg]
  (e/client
    (dom/div (dom/props {:class "flex w-full flex-col items-start"})
      (let [msg-hovered? (dom/Hovered?.)]
        (dom/div (dom/props {:class "relative max-w-[70%] rounded-3xl bg-[#b8e9f8] px-5 py-2.5 dark:bg-token-main-surface-secondary"})
          ;; disabling edit button for now, should make it configurable
                 #_(ui/button (e/fn [])
            (dom/props {:class (str "absolute -left-12 top-1 hover:bg-[#f4f4f4] rounded-full flex justify-center items-center w-8 h-8"
                                 (if-not msg-hovered?
                                   " invisible"
                                   " visible")) 
                        :title "Edit message"})
            (dom/img (dom/props {:class "w-4" :src "icons/pencil.svg"})))
          (dom/p (dom/text msg)))))))

(e/defn RenderMsg [msg-map]
  (e/client 
    (let [{:message/keys [created id text role kind voice]} msg-map
          _ (prn "message id" id "voice:" voice " kind: " kind)]
      (dom/div (dom/props {:class "w-full"})
        (dom/div
          (dom/props {:class "mx-auto flex flex-1 gap-4 text-base md:gap-5 lg:gap-6 md:max-w-3xl lg:max-w-[40rem] xl:max-w-[48rem]"})
          (case voice
            :user (UserMsg. text)
            :assistant  (BotMsg. msg-map)
            :agent (dom/div (dom/text))
            :system (dom/div (dom/props {:class "group md:px-4 border-b border-black/10 bg-white text-gray-800 dark:border-gray-900/50 dark:bg-[#343541] dark:text-gray-100"})
                      (dom/div (dom/props {:class "relative m-auto flex p-4 text-base md:max-w-2xl md:gap-6 md:py-6 lg:max-w-2xl lg:px-0 xl:max-w-3xl"})
                        #_(dom/div (dom/props {:class "min-w-[40px] text-right font-bold"})
                            (set! (.-innerHTML dom/node) bot-icon))
                        (dom/div (dom/props {:class "prose whitespace-pre-wrap dark:prose-invert flex-1"})
                          (set! (.-innerHTML dom/node) (md2/md->html text)))
                        #_(dom/div (dom/props {:class "md:-mr-8 ml-1 md:ml-0 flex flex-col md:flex-row gap-4 md:gap-1 items-center md:items-start justify-end md:justify-start"})
                            (dom/button (dom/props {:class "invisible group-hover:visible focus:visible text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"})
                              (set! (.-innerHTML dom/node) delete-icon)))))))))))

(e/defn Conversation []
  (e/client
    (let [convo-id active-conversation
          [model temp entity-id] (when convo-id
                                   (e/server
                                    (e/offload #(d/q '[:find [?entity-id #_?model #_?temp #_?system-prompt]
                                                       :in $ ?conv-id
                                                       :where
                                                       [?e :conversation/id ?conv-id]
                                                       [?e :conversation/entity-id ?entity-id]
                                          ;;  [?e :conversation/model ?model]
                                          ;;  [?e :conversation/temp ?temp]
                                          ;;  [?e :conversation/system-prompt ?system-prompt] 
                                                       ]
                                                     dh-conn convo-id))))

          ;; _ (prn "fetching messages for convo id: " convo-id "entity-id: " entity-id)
          messages (e/server (e/offload #(fetch-convo-messages-mapped dh-conn convo-id)))
          ;; _ (prn "fetched " (count messages) "messages")
          ;; _ (prn "conversation-entity" conversation-entity)
          entity (first (filter #(= (:id %) entity-id) (:entities entities-cfg)))
          ;; _ (prn "current entity:" entity)
          {:keys [prompt image full-name name]} entity
          ;; non-agent-messages (filter #(not= :agent (:agent %)) messages)
          ;; _ (prn "non-agent-messages count:" (count non-agent-messages) "messages")
          ]

     (dom/div
      (dom/props {:class "flex flex-col stretch justify-center items-center h-full lg:max-w-3xl mx-auto gap-4"})
      #_(dom/props {:class "anchored-scroller flex flex-col items-start w-full lg:max-w-3xl gap-4 mb-20"})
      (dom/div (dom/props {:class "flex flex-col gap-8 items-center"})
               #_(dom/img (dom/props {:class "w-10 mx-auto rounded-full"
                                      :src image}))
               (dom/h1 (dom/props {:class "text-2xl"}) (dom/text (or full-name name)))
          ;; Uncomment to check prompt
          #_(dom/p (dom/text (e/server (slurp (clojure.java.io/resource prompt))))))
        (when messages ;todo: check if this is still needed
          (e/for [msg messages]
            (RenderMsg. msg))
          ;; BOT STATUS
          #_(when (seq response-states)
            (dom/div (dom/props {:class "w-full"})
              (dom/div
                (dom/props {:class "mx-auto flex flex-1 gap-4 text-base md:gap-5 lg:gap-6 md:max-w-3xl lg:max-w-[40rem] xl:max-w-[48rem]"})
                (let [{:keys [name image]} conversation-entity]
                  (dom/div (dom/props {:class "flex w-full gap-4 items-start"})
                    (dom/div (dom/props {:class "flex -ml-12"})
                      (dom/img (dom/props {:class "rounded-full w-8 h-8"
                                           :src image})))
                    (dom/div (dom/props {:class "flex flex-col gap-4"})
                      (dom/img (dom/props {:class "w-8 h-8 animate-spin"
                                           :src "icons/loader.svg"})) 
                      (dom/ul (dom/props {:class "bg-[#51e2ff] py-3 gap-4 rounded"})
                        (e/for-by identity [n-state response-states] 
                          (dom/p (dom/text "Progress - " n-state)))))))))))
        
        #_(when (:streaming (get stream-msgs convo-id))
            (when-let [content (:content (get stream-msgs convo-id))]
              (BotMsg. content))) 
        (dom/div (dom/props {:class "scroller-anchor"})
          (dom/text " "))
        (PromptInput. {:convo-id convo-id
                       :messages nil #_messages})))))

(e/defn ConversationList [conversations]
  (e/client
    (let [inside-folder-atom? (atom false)
          inside-folder? (e/watch inside-folder-atom?)
          !edit-conversation (atom false)
          edit-conversation (e/watch !edit-conversation)]
      (dom/div (dom/props {:class "pt-2 flex-grow"})
        (dom/on "dragover" (e/fn [e] (.preventDefault e)))
        (dom/on "dragenter" (e/fn [_]
                              #_(.requestAnimationFrame js/window
                                  #(reset! !folder-dragged-to :default))))
        (dom/on "dragleave" (e/fn [_] (fn [_] (reset! !folder-dragged-to nil))))
        (dom/on "drop" (e/fn [_]
                         (println "drop ")
                         (let [convo-id @!convo-dragged]
                           (println "convo-id: " convo-id)
                           (e/server
                            (let [conn @delayed-connection]
                              (e/offload
                               #(do
                                  (println "Dropped convo-id on default: " convo-id)
                                  (when-let [eid (d/q '[:find ?e .
                                                        :in $ ?convo-id
                                                        :where
                                                        [?e :conversation/id ?convo-id]
                                                        [?e :conversation/folder]]
                                                      dh-conn convo-id)]
                                    (println "the folder: " eid)
                                    (d/transact conn [[:db/retract eid :conversation/folder]]))))))
                           (reset! !folder-dragged-to nil)
                           (reset! !convo-dragged nil))))
        (dom/div (dom/props {:class (str (when-not inside-folder? "gap-1 ") "flex w-full flex-col")})
          (e/for [[created eid convo-id topic folder-name] conversations]
            (when folder-name (reset! inside-folder-atom? folder-name))
            (let [editing? (= convo-id (:convo-id edit-conversation))]
              (dom/div (when folder-name (dom/props {:class "ml-5 gap-2 border-l pl-2"}))
                (dom/div (dom/props {:class "relative flex items-center"})
                  (if-not (and editing? (= :edit (:action edit-conversation)))
                    (dom/button (dom/props {:class (str (when (= active-conversation convo-id) "bg-slate-200 ") "flex w-full cursor-pointer items-center gap-3 rounded-lg p-3 text-sm transition-colors duration-200 ")
                                            :draggable true}) 
                      (dom/on "click" (e/fn [_]
                                        (reset! !active-conversation convo-id)
                                        (reset! !view-main :conversation)))
                      (dom/on "dragstart" (e/fn [_]
                                            (println "setting convo-dragged: " convo-id)
                                            (reset! !convo-dragged convo-id)
                                            (println "set convo-dragged: " @!convo-dragged)))
                      (dom/div (dom/props {:class "relative max-h-5 flex-1 overflow-hidden text-ellipsis whitespace-nowrap break-all text-left text-[12.5px] leading-3 pr-1"})
                        (dom/text topic)))
                    (dom/div (dom/props {:class "flex w-full items-center gap-3 rounded-lg bg-[#343541]/90 p-3"})
                      (set! (.-innerHTML dom/node) msg-icon)
                      (dom/input (dom/props {:class "mr-12 flex-1 overflow-hidden overflow-ellipsis border-neutral-400 bg-transparent text-left text-[12.5px] leading-3 text-white outline-none focus:border-neutral-100"
                                             :value topic})
                        (dom/on "keydown" (e/fn [e]
                                            (when (= "Enter" (.-key e))
                                              (when-some [v (not-empty (.. e -target -value))]
                                                (let [new-topic (:changes @!edit-conversation)]
                                                  (e/server
                                                   (let [conn @delayed-connection]
                                                     (e/offload #(d/transact conn [{:db/id [:conversation/id convo-id]
                                                                                    :conversation/topic new-topic}])))
                                                    nil)
                                                  (reset! !edit-conversation false))))))
                        (dom/on "keyup" (e/fn [e]
                                          (when-some [v (not-empty (.. e -target -value))]
                                            (swap! !edit-conversation assoc :changes v))))
                        (.focus dom/node))))
                  (when (= convo-id active-conversation)
                    (dom/div (dom/props {:class "absolute right-1 z-10 flex text-gray-300"})
                      (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                        (set! (.-innerHTML dom/node) (if editing? tick-icon edit-icon))
                        (if editing?
                          (dom/on "click" (e/fn [_]
                                            (case (:action edit-conversation)
                                              :delete (do
                                                        (e/server
                                                         (let [conn @delayed-connection]
                                                           (e/offload #(d/transact conn [[:db/retract eid :conversation/id]])))
                                                          nil)
                                                        (when (= convo-id @!active-conversation)
                                                          (reset! !active-conversation nil))
                                                        (reset! !edit-conversation false))
                                              :edit (let [new-topic (:changes @!edit-conversation)]
                                                      (e/server
                                                       (let [conn @delayed-connection]
                                                         (e/offload #(d/transact conn [{:db/id [:conversation/id convo-id]
                                                                                        :conversation/topic new-topic}])))
                                                        nil)
                                                      (reset! !edit-conversation false)))))
                          (dom/on "click" (e/fn [_] (reset! !edit-conversation {:convo-id convo-id
                                                                                :action :edit})))))
                      (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                        (if editing?
                          (dom/on "click" (e/fn [_] (reset! !edit-conversation false)))
                          (dom/on "click" (e/fn [_] (reset! !edit-conversation {:convo-id convo-id
                                                                                :action :delete}))))
                        (set! (.-innerHTML dom/node) (if editing? x-icon delete-icon))))))))))))))

(e/defn FolderList [folders]
  (e/client
    (when (seq folders)
      (dom/div (dom/props {:class "flex border-b border-white/20 pb-2"})
        (dom/div (dom/props {:class "flex w-full flex-col pt-2"})
          (e/for [[_created eid folder-id name] folders]
            (let [editing? (= folder-id (:folder-id edit-folder))
                  open-folder? (contains? open-folders folder-id)
                  conversations (e/server (e/offload #(sort-by first > (d/q '[:find ?created ?c ?c-id ?topic ?folder-name
                                                                              :in $ ?folder-id
                                                                              :where
                                                                              [?e :folder/id ?folder-id]
                                                                              [?e :folder/name ?folder-name]
                                                                              [?c :conversation/folder ?folder-id]
                                                                              [?c :conversation/id ?c-id]
                                                                              [?c :conversation/topic ?topic]
                                                                              [?c :conversation/created ?created]]
                                                                         dh-conn folder-id))))]
              (dom/div (dom/props {:class "relative flex items-center"})
                (if-not (and editing? (= :edit (:action edit-folder)))
                  (dom/button (dom/props {:class (str (when (= folder-id folder-dragged-to) "bg-[#343541]/90 ") "flex w-full cursor-pointer items-center gap-3 rounded-lg p-3 text-sm transition-colors duration-200 hover:bg-[#343541]/90")})
                    (dom/on "click" (e/fn [_] (if-not open-folder?
                                                (swap! !open-folders conj folder-id)
                                                (swap! !open-folders disj folder-id))))
                    (dom/on "dragover" (e/fn [e] (.preventDefault e)))
                    (dom/on "dragenter" (e/fn [_] (.requestAnimationFrame js/window
                                                    #(reset! !folder-dragged-to folder-id))))
                    (dom/on "dragleave" (e/fn [_] (fn [_] (reset! !folder-dragged-to nil))))
                    (dom/on "drop" (e/fn [_]
                                     (let [convo-id @!convo-dragged]
                                       (e/server 
                                        (let [conn @delayed-connection]
                                          (e/offload #(d/transact conn [{:db/id [:conversation/id convo-id]
                                                                         :conversation/folder folder-id}])))
                                         nil))
                                     (swap! !open-folders conj folder-id)
                                     (reset! !folder-dragged-to nil)
                                     (reset! !convo-dragged nil)))
                    (dom/div
                      (set! (.-innerHTML dom/node) (if-not open-folder? folder-arrow-icon folder-arrow-icon-down)))
                    (dom/text name))
                  (dom/div (dom/props {:class "flex w-full items-center gap-3 rounded-lg bg-[#343541]/90 p-3"})
                    (set! (.-innerHTML dom/node) folder-arrow-icon)
                    (dom/input (dom/props {:class "mr-12 flex-1 overflow-hidden overflow-ellipsis border-neutral-400 bg-transparent text-left text-[12.5px] leading-3 outline-none focus:border-neutral-100"
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
                                          :delete (e/server
                                                   (let [conn @delayed-connection]
                                                     (e/offload #(d/transact conn [[:db.fn/retractEntity eid]])))
                                                    nil)
                                          :edit (let [new-folder-name (:changes @!edit-folder)]
                                                  (e/server
                                                   (let [conn @delayed-connection]
                                                     (e/offload #(d/transact conn [{:db/id [:folder/id folder-id]
                                                                                    :folder/name new-folder-name}])))
                                                    nil)
                                                  (reset! !edit-folder false)))))
                      (dom/on "click" (e/fn [_] (reset! !edit-folder {:folder-id folder-id
                                                                      :action :edit}))))
                    (set! (.-innerHTML dom/node) (if editing? tick-icon edit-icon)))
                  (ui/button
                    (e/fn []
                      (if editing?
                        (reset! !edit-folder nil)
                        (reset! !edit-folder {:folder-id folder-id
                                              :action :delete})))
                    (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                    (set! (.-innerHTML dom/node) (if editing? x-icon delete-icon)))))
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


(e/defn LeftSidebar []
  (e/client
   (when sidebar?
     (let [folders (e/server  (e/offload #(sort-by first > (d/q '[:find ?created ?e ?folder-id ?name
                                                                  :where
                                                                  [?e :folder/id ?folder-id]
                                                                  [?e :folder/name ?name]
                                                                  [?e :folder/created ?created]]
                                                                dh-conn))))
           !search-text (atom nil)
           search-text (e/watch !search-text)
           conversations  (if-not search-text
                            (e/server
                             (e/offload #(sort-by first > (d/q '[:find ?created ?e ?conv-id ?topic
                                                                 :where
                                                                 [?e :conversation/id ?conv-id]
                                                                 [?e :conversation/topic ?topic]
                                                                 [?e :conversation/created ?created]
                                                                 (not [?e :conversation/folder])]
                                                               dh-conn))))

                            (e/server
                             (e/offload #(let [convo-eids (d/q '[:find [?c ...]
                                                                 :in $ search-txt ?includes-fn
                                                                 :where
                                                                 [?m :message/text ?msg-text]
                                                                 [?c :conversation/messages ?m]
                                                                 [?c :conversation/topic ?topic]
                                                                 (or-join [?msg-text ?topic]
                                                                          [(?includes-fn ?msg-text search-txt)]
                                                                          [(?includes-fn ?topic search-txt)])]
                                                               dh-conn search-text lowercase-includes?)]
                                           (sort-by first > (d/q '[:find ?created ?e ?conv-id ?topic
                                                                   :in $ [?e ...]
                                                                   :where
                                                                   [?e :conversation/id ?conv-id]
                                                                   [?e :conversation/topic ?topic]
                                                                   [?e :conversation/created ?created]]
                                                                 dh-conn convo-eids))))))
           !clear-conversations? (atom false)
           clear-conversations? (e/watch !clear-conversations?)]
       (dom/div (dom/props {:class (str "bg-slate-100 pt-8 px-4 w-[260px] h-full flex flex-col gap-4"
                                          ;; Old css
                                        #_"fixed top-0 left-0 z-40 flex h-full w-[260px] flex-none flex-col space-y-2 p-2 text-[14px] transition-all sm:relative sm:top-0")}) ;bg-[#202123]

                (dom/div (dom/props {:class "flex flex-col"})
                         (let [local-btn-style "flex items-center gap-4 py-2 px-4 w-full rounded hover:bg-slate-300"]
                           (ui/button
                            (e/fn []
                              (reset! !active-conversation nil)
                              (reset! !view-main :entity-selection))
                            (dom/props {:class local-btn-style})
                            (let [entity (first (:entities entities-cfg))]
                              (dom/img (dom/props {:class "w-8 rounded-full"
                                                   :src (:image entity)}))
                              (dom/p (dom/text (:name entity)))))
                           (ui/button
                            (e/fn []
                              (reset! !view-main :entity-selection)
                              (reset! !active-conversation nil))
                            (dom/props {:class local-btn-style})
                            (dom/img (dom/props {:class "w-8 rounded-full"
                                                 :src (:all-entities-image entities-cfg)}))
                            (dom/p (dom/text "All Entities")))))

          (dom/div (dom/props {:class "relative flex items-center gap-4 pt-4"})
            (dom/input (dom/props {:class "w-full flex-1 rounded-md border border-neutral-600 px-4 py-3 pr-10 text-[14px] leading-3" ;bg-[#202123]
                                   :placeholder "Search..."
                                   :value search-text})
              (dom/on "keyup" (e/fn [e]
                                (if-some [v (not-empty (.. e -target -value))]
                                  (reset! !search-text v)
                                  (reset! !search-text nil)))))
            (ui/button
              (e/fn []
                (e/server
                 (let [conn @delayed-connection]
                   (e/offload #(d/transact conn [{:folder/id (nano-id)
                                                  :folder/name "New folder"
                                                  :folder/created (System/currentTimeMillis)}])))
                  nil))
              (dom/props {:title "New folder"
                          :class "cursor-pointer w-8 h-8 flex items-center justify-center bg-slate-300 hover:bg-slate-400 rounded"})
              (set! (.-innerHTML dom/node) search-icon)))
          (when search-text (dom/p (dom/props {:class "text-gray-500 text-center"})
                              (dom/text (str (count  conversations)) #_(map second conversations) " results found")))

                 ;; Conversations 
                (dom/div (dom/props {:class "flex-grow overflow-auto flex flex-col"})
                         (if (or (seq conversations) (seq folders))
                           (do
                             (FolderList. folders)
                             (ConversationList. conversations))
                           (dom/div
                            (dom/div (dom/props {:class "mt-8 select-none text-center opacity-50"})
                                     (set! (.-innerHTML dom/node) no-data-icon)
                                     (dom/text "No Data")))))

                (let [http-cookie-jwt (e/server (get-in e/http-request [:cookies "auth-token" :value]))]
                  #_(dom/p (dom/text "created-by: " (e/server (let [user-email (:user-id (auth/verify-token (get-in e/http-request [:cookies "auth-token" :value])))]
                                                                (e/offload #(:user/id (d/pull auth-conn '[:user/id] [:user/email user-email])))))))
                  #_(dom/p (dom/text "HTTP Only cookie: " http-cookie-jwt))
                  (let [cookie (e/server (auth/verify-token http-cookie-jwt))
                        jwt-expiry (:expiry cookie)
                        user-id (:user-id cookie)
                        is-admin-user (e/server (auth/admin-user? user-id))]
                    ;;  
                    (when is-admin-user
                      #_(SessionTimer.)
                      (dom/button
                       (dom/props {:class "px-4 py-2 rounded bg-black text-white hover:bg-slate-800"})
                       (dom/on "click" (e/fn [e] (reset! !view-main :dashboard)))
                       (dom/text "Admin Dashboard"))
                      (dom/div (dom/props {:class "flex flex-col items-center space-y-1 border-t border-white/20 pt-1 text-sm"})
                               (if-not clear-conversations?
                                 (ui/button
                                  (e/fn []
                                    (println "clear conversations")
                                    (reset! !clear-conversations? true))
                                  (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 transition-colors duration-200 hover:bg-gray-500/10"})
                                  (dom/text "Clear conversations"))
                                 (dom/div (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 transition-colors duration-200 hover:bg-gray-500/10"})
                                          (set! (.-innerHTML dom/node) delete-icon)
                                          (dom/text "Are you sure?")
                                          (dom/div (dom/props {:class "absolute right-1 z-10 flex text-gray-300"})
                                                   (ui/button (e/fn []
                                                                (println "clearing all conversations")
                                                                (e/server
                                (let [conn @delayed-connection]
                                                                  (println "serverside call")
                                                                  (e/offload
                                                                  #(let [convo-eids (map :e (d/datoms @conn :avet :conversation/id))
                                                                         folder-eids (map :e (d/datoms @conn :avet :folder/id))
                                                                         m-eids  (set (map first (d/q '[:find ?m
                                                                                                        :in $ [?convo-id ...]
                                                                                                        :where
                                                                                                        [?convo-id :conversation/messages ?m]] dh-conn convo-eids)))
                                                                         retraction-ops (concat
                                                                                        (mapv (fn [eid] [:db.fn/retractEntity eid :conversation/id]) convo-eids)
                                                                                        (mapv (fn [eid] [:db.fn/retractEntity eid :folder/id]) m-eids)
                                                                                        (mapv (fn [eid] [:db.fn/retractEntity eid :folder/id]) folder-eids))]
                                                                     (d/transact conn retraction-ops))))
                                                                 nil)
                                                                (reset! !active-conversation nil)
                                                                (reset! !clear-conversations? false))
                                                              (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                                                              (set! (.-innerHTML dom/node) tick-icon))

                                                   (ui/button (e/fn [] (reset! !clear-conversations? false))
                                                              (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                                                              (set! (.-innerHTML dom/node) x-icon)))))
                               #_(ui/button (e/fn []
                                              (reset! !view-main :settings)
                                              (when (mobile-device?) (reset! !sidebar? false)))
                                            (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
                                            (set! (.-innerHTML dom/node) settings-icon)
                                            (dom/text "Settings")))))))))))

(e/defn TreeView [db-data]
  (e/client
   (dom/div (dom/props {:class "p-4 h-full overflow-auto"})
            (let [!expanded-views (atom #{})
                  expanded-views (e/watch !expanded-views)]
              (e/for-by identity [[k v] db-data]
                        (let [expanded? (contains? expanded-views k)]
                          (dom/div (dom/props {:class "cursor-pointer"})
                                   (dom/on "click" (e/fn [_]
                                                     (if-not expanded?
                                                       (swap! !expanded-views conj k)
                                                       (swap! !expanded-views disj k))))
                                   (dom/div (dom/props {:class "flex px-2 -mx-2 font-bold rounded"})
                                            (dom/p (dom/props {:class "w-4"})
                                                   (dom/text (if expanded? "▼" "▶")))
                                            (dom/p (dom/text k "  (count " (count (filter #(not (= :db/txInstant (:a %))) v)) ")")))
                                   (when expanded?
                                     (dom/div (dom/props {:class "pl-4"})
                                              (e/for-by identity [[k v] (group-by :e v)]
                                                        (e/for-by identity [{:keys [e a v t asserted]} (filter #(not (= :db/txInstant (:a %))) v)]
                      ;; (let [{:keys [e a v t]} v])
                                                                  (dom/div (dom/props {:class "flex gap-4"})
                                                                           (dom/p (dom/props {:class "w-4"})
                                                                                  (dom/text e))
                                                                           (dom/p (dom/props {:class "w-1/3"})
                                                                                  (dom/text a))
                                                                           (dom/p (dom/props {:class "w-1/3"})
                                                                                  (dom/text (if (string? v)
                                                                                              (subs v 0 (min 300 (count v)))
                                                                                              v)))
                                                                           (dom/p (dom/props {:class "w-8"})
                                                                                  (dom/text asserted))))))))))))))

(e/defn DBInspector []
  (e/server
   (let [!selected-db (atom :chat) selected-db (e/watch !selected-db)
         group-by-tx (fn [results] (reduce (fn [acc [e a v tx asserted]]
                                             (update acc tx conj {:e e :a a :v v :asserted asserted}))
                                           {}
                                           results))
         db (case selected-db
              :chat dh-conn
              :auth auth-conn)
         db-data (let [results (d/q '[:find ?e ?a ?v ?tx ?asserted
                                      :where
                                      [?e ?a ?v ?tx ?asserted]] db #_#_auth-conn dh-conn)]
                   (reverse (sort (group-by-tx results))))]
     (e/client
      (dom/div (dom/props {:class "absolute top-0 right-0 h-48 w-1/2 bg-red-500 overflow-auto"})
               (dom/p (dom/text "Selected db: "  selected-db))
               (dom/ul
                (dom/props {:class "flex gap-4 p-2"})
                (e/for-by identity [db-name [:chat :auth]]
                          (dom/button
                           (dom/props {:class "px-4 py-2 bg-black text-white rounded hover:"})
                           (dom/on "click" (e/fn [e] (e/server (reset! !selected-db db-name) nil)))
                           (dom/text db-name))))
               (TreeView. db-data))))))

(e/defn EntitySelector []
  (e/client
   (let [EntityCard (e/fn [id title img-src]
                      (ui/button (e/fn []
                                   (let [entity (some #(when (= (:id %) id) %) (:entities entities-cfg))]
                                     (when entity
                                       (reset! !view-main :conversation)
                                       (reset! !conversation-entity entity))))
                                 (dom/props {:class "flex flex-col gap-4 items-center hover:scale-110 hover:shadow-lg shadow rounded p-4 transition-all ease-in duration-150 bg-slate-200"}) ;bg-[#202123]
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
    (dom/props {:class "p-8 flex flex-col gap-4"})
    (let [token (e/client (get-from-local-storage "auth-token"))]
      (dom/div
       (dom/p (dom/text "Local storage JWT: " token))
       (dom/p (dom/text "Local storage JWT unsigned: " (e/server (auth/verify-token token))))
       (let [http-cookie-jwt (e/server (get-in e/http-request [:cookies "auth-token" :value]))]
         (dom/p (dom/text "created-by: " (e/server (let [user-email (:user-id (auth/verify-token (get-in e/http-request [:cookies "auth-token" :value])))]
                                                     (e/offload #(:user/id (d/pull auth-conn '[:user/id] [:user/email user-email])))))))
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
                                   admin-id (e/offload #(:user/id (d/pull auth-conn '[:user/id] [:user/email current-user])))]
                               (e/offload #(auth/create-new-user {:email email
                                                                 :creator-id admin-id})) 
                               nil)
                             (auth/generate-confirmation-code email)
                             nil))))
        (dom/text "Generate Code"))))

    (dom/p (dom/text "Auth db"))
    (dom/pre (dom/text (pretty-print (e/server (e/offload #(auth/all-accounts auth-conn))) )))
    

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
                               (dom/text "Remove"))))))))


(e/defn MainView []
  (e/client
   (dom/div (dom/props {:class "flex flex-1 h-full w-full"})
            (dom/on "drop" (e/fn [_] (println "drop ")))
            (dom/on "dragdrop" (e/fn [_] (println "drop ")))
            (dom/on "dragenter" (e/fn [_] (println "enter main")))
            (dom/on "dragleave" (e/fn [_] (println "leave main")))
            (dom/div (dom/props {:class "relative flex-1 overflow-hidden pb-[100px]"}) ;dark:bg-[#343541] bg-white 
                     (dom/div (dom/props {:class "max-h-full overflow-x-hidden"})
                              (case view-main
                                :entity-selection (EntitySelector.)
                                :conversation (Conversation.)
                                :dashboard (AuthAdminDashboard.))
                              (when debug? (DBInspector.)))))))

(e/defn DebugController []
  (e/client
   (ui/button
    (e/fn [] (swap! !debug? not))
    (dom/props {:class (str "absolute top-0 right-0 z-10 px-4 py-2 rounded text-black"
                            (if-not debug?
                              " bg-slate-500"
                              " bg-red-500"))})
    (dom/p (dom/text "Debug: " debug?)))))

(e/defn Topbar []
  (e/client
    (dom/div (dom/props {:class "sticky w-full top-0 h-16 z-10"})
      (dom/div (dom/props {:class "flex gap-4"})
        (dom/div (dom/props {:class (str "flex justify-between gap-4 px-4 py-4"
                                      (if sidebar?
                                        " w-[260px] bg-slate-100"
                                        " w-max"))})
          (ui/button
            (e/fn []
              (when (mobile-device?) (reset! !prompt-sidebar? false))
              (reset! !sidebar? (not @!sidebar?)))
            (dom/img (dom/props {:class "w-6 h-6"
                                 :src (if-not sidebar?
                                        "icons/panel-left-open.svg"
                                        "icons/panel-left-close.svg")})))
          (ui/button (e/fn [] (reset! !view-main :entity-selection))
            (dom/img (dom/props {:class "w-6 h-6"
                                 :src "icons/square-pen.svg"}))))
        (dom/div (dom/props {:class "flex gap-4 py-4 items-center text-slate-500"}) 
          (dom/p (dom/text (:name conversation-entity))))))))

(e/defn Main [ring-request]
  (e/server
   (binding [dh-conn (e/watch @delayed-connection)
             auth-conn (e/watch @delayed-connection)
             e/http-request ring-request]
     (e/client
      (binding [dom/node js/document.body]
        (Topbar.)
        (dom/main (dom/props {:class "flex h-full w-screen flex-col text-sm absolute top-0 pt-12"}) ;dark:text-white  dark
                  (dom/div (dom/props {:class "flex h-full w-full pt-[48px] sm:pt-0 items-start"})
                           (LeftSidebar.)
                           (MainView.)
                           #_(DebugController.))))))))


