(ns services.system
  (:require  [hyperfiddle.electric :as e]
             [hyperfiddle.electric-dom2 :as dom]
             [nano-id.core :refer [nano-id]]
             #?(:clj [datahike.api :as d])
             #?(:clj [models.db :as db :refer [delayed-connection]])
             #?(:clj [services.openai :as openai])
             #?(:clj [chat-app.rag :as rag])))

#?(:clj
   (defn answer-new-user-query-with-rag [{:keys [conversation-entity convo-id user-query] :as params}]
     #_(swap! !stream-msgs assoc-in [convo-id :streaming] true)
     (let [conn @delayed-connection
         ;;   _ (prn "answer-new-user-query-with-rag is starting with params: " params)
           _ (reset! rag/!response-states ["Vurderer spørsmålet ditt"])
         ;;   _ (prn "conversation-entity:" conversation-entity)
           {:keys [id prompt image full-name name docs-collection chunks-collection phrases-collection phrase-gen-prompt]} conversation-entity

           rag-response (rag/rag-pipeline
                         {;; :on-next #(process-chunk convo-id %)
                          :conversation-id convo-id
                          :entity-id id
                          :translated_user_query user-query
                          :original_user_query user-query
                          :user_query_language_name "Norwegian"
                          :promptRagQueryRelax "You have access to a search API that returns relevant documentation.\nYour task is to generate an array of up to 7 search queries that are relevant to this question. Use a variation of related keywords and synonyms for the queries, trying to be as general as possible.\nInclude as many queries as you can think of, including and excluding terms. For example, include queries like ['keyword_1 keyword_2', 'keyword_1', 'keyword_2']. Be creative. The more queries you include, the more likely you are to find relevant results.\n",
                          :promptRagGenerate "Use the following pieces of information to answer the user's question.\nIf you don't know the answer, just say that you don't know, don't try to make up an answer.\n\nContext: {context}\n\nQuestion: {question}\n\nOnly return the helpful answer below, using Markdown for improved readability.\n\nHelpful answer:\n",
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
                         conn)
           _ (reset! rag/!response-states nil)]
       rag-response)))

#?(:clj
   (defn answer-followup-user-query [{:keys [convo-id user-query]}]
     (let [_ (prn "Storing followup query in db.")
           conn @delayed-connection
           _ (reset! rag/!response-states ["Vurderer spørsmålet ditt"])
           _ (db/transact-user-msg conn
                                   {:convo-id convo-id
                                    :user-query user-query})

           _ (prn "stored followup query in db, fetching completion-messages")
           messages (vec (db/fetch-convo-messages-mapped @conn convo-id))

           ;; we send all completion-messages to the llm
           completion-messages (filter #(not= false (:message/completion %))
                                       messages
                                       ;; (concat messages new-messages)
                                       )
           _ (prn "fetched" (count completion-messages) "completion-messages")
           _ (prn "Starting LLM call for followup query.")
           ;; _ (swap! rag/!response-states conj "Starting LLM call for followup query")
           openai-messages (into []
                                 (mapv (fn [msg]
                                         {:role (name (:message/role msg))
                                          :content (:message/text msg)})
                                       completion-messages))
              ;;  _ (prn "openai-messages:" openai-messages)
           chat-response (openai/create-chat-completion openai-messages)
           _ (prn "chat-response completed. response:" chat-response)
           ;; _ (reset! rag/!response-states [])
           assistant-reply (:content (:message (first (:choices chat-response))))
           _ (db/transact-assistant-msg conn convo-id assistant-reply)
           _ (reset! rag/!response-states [])]
       assistant-reply)))

;; Add a watch function to process jobs continuously
#?(:clj
   (defn start-job-processor!
     "Start a background process to handle RAG jobs"
     []
     (add-watch rag/!rag-jobs :job-processor
                (fn [_ _ _ jobs]
                  (when (seq jobs)
                    (when-let [job (rag/dequeue-rag-job)]
                      (future
                        (try
                          (println "Processing job:" job)
                          (case (:type job)
                            :followup (answer-followup-user-query (:msg-data job))
                            :new (answer-new-user-query-with-rag (:msg-data job)))
                          (catch Exception e
                            (println "Error processing job:" e))))))))))

;; Initialize the job processor when the namespace is loaded
#?(:clj (start-job-processor!))
