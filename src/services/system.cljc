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
           _ (reset! rag/!response-states ["Vurderer spørsmålet ditt"])
           messages (vec (db/fetch-convo-messages-mapped @conn convo-id))
           filter-by (-> (last messages) :message.filter/value) 
           {:keys [id
                   docs-collection chunks-collection
                   phrases-collection phrase-gen-prompt
                   promptRagQueryRelax promptRagGenerate]} conversation-entity

           rag-response (rag/rag-pipeline
                         {;; :on-next #(process-chunk convo-id %)
                          :conversation-id convo-id
                          :entity-id id
                          :translated_user_query user-query
                          :original_user_query user-query
                          :user_query_language_name "Norwegian"
                          :filter-by filter-by
                          :promptRagQueryRelax promptRagQueryRelax,
                          :promptRagGenerate promptRagGenerate,
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
                                       messages)
           _ (prn "fetched" (count completion-messages) "completion-messages")
           _ (prn "Starting LLM call for followup query.")
           _ (reset! rag/!response-states ["Skriver svar"])
           openai-messages (into []
                                 (mapv (fn [msg]
                                         {:role (name (:message/role msg))
                                          :content (:message/text msg)})
                                       completion-messages)) 
           chat-response (openai/create-chat-completion openai-messages)
           _ (prn "chat-response completed. response:" chat-response)
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
                            (println "Error processing job:" e))
                          (finally
                            (future
                              (Thread/sleep 7000)
                              (reset! rag/!response-states nil)))))))))))

;; Initialize the job processor when the namespace is loaded
#?(:clj (start-job-processor!))
