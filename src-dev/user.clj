(ns user
  (:require [dev]
            [datahike.api :as d]
            [datahike-jdbc.core]
            [chat-app.main :as main]
            [chat-app.auth :as auth]
            [portal.api :as p]))


(comment
  ;; here is where we can do stuff in the portal repl
  (def portal (p/open))
  (add-tap #'p/submit)

  (def db (d/db main/conn))

  (defn latest-convo-id []
    (d/q '[:find ?convo-id]))

  (defn newest-convo-id []
    (let [results (d/q '[:find ?convo-id ?created
                         :where
                         [?e :conversation/id ?convo-id]
                         [?e :conversation/created ?created]]
                    db)
          sorted-results (sort-by second > results)]
      (when (seq sorted-results)
        (first (first sorted-results)))))

  (newest-convo-id)

  (defn fetch-messages-for-newest-convo []
    (let [newest-id (newest-convo-id)]
      (when newest-id
        (main/fetch-convo-messages newest-id))))

  (tap> (fetch-messages-for-newest-convo))
  (def convo-id (:convo-id (newest-convo-id)))

  (newest-convo-id)

  (def all-messages (main/fetch-convo-messages db convo-id))
  (defn agent-messages [convo-id] (main/fetch-convo-messages db convo-id :agent))
  (defn user-messages [convo-id] (main/fetch-convo-messages db convo-id :user))

  (tap> (user/agent-messages convo-id))
  (tap> (user/user-messages convo-id))
  )

;; auth tests

(comment
  
  (auth/generate-confirmation-code "wd@itonomi.com")

  @confirmation-codes
  (def token (auth/create-token "wd@itonomi.com" (auth/create-expiry {:multiplier 5
                                                            :timespan :seconds})))
  (auth/verify-token token)


  ;; Email validation
  (auth/validate-email? "examplemydomaincom")
  (auth/validate-email? "example@test@my-domain.com")
  (auth/validate-email? "example.@my-domain.com")
  (auth/validate-email? "example@com")
  (auth/validate-email? "example..test@my-domain.com")
  (auth/validate-email? "-example@my-domain.com")
  (auth/validate-email? "exam&ple@my-domain.com")
  (auth/validate-email? "example@my-domain.com.123")

  (auth/send-confirmation-code "bdb@itonomi.com" "benjamin@bdbrodie.com" "8983"))


;; rag tests

   (def kudos-params
     {:translated_user_query "Hva har vegvesenet gjort innen innovasjon?"
      :original_user_query "Hva har vegvesenet gjort innen innovasjon?"
      :user_query_language_name "Norwegian"
      :promptRagQueryRelax "You have access to a search API that returns relevant documentation.\nYour task is to generate an array of up to 7 search queries that are relevant to this question. Use a variation of related keywords and synonyms for the queries, trying to be as general as possible.\nInclude as many queries as you can think of, including and excluding terms. For example, include queries like ['keyword_1 keyword_2', 'keyword_1', 'keyword_2']. Be creative. The more queries you include, the more likely you are to find relevant results.\n",
      :promptRagGenerate "Use the following pieces of information to answer the user's question.\nIf you don't know the answer, just say that you don't know, don't try to make up an answer.\n\nContext: {context}\n\nQuestion: {question}\n\nOnly return the helpful answer below, using Markdown for improved readability.\n\nHelpful answer:\n",
      :phrase-gen-prompt "keyword-search"
      :maxSourceDocCount 10
      :maxContextLength 10000
      :maxSourceLength 40000
      :docsCollectionName "DEV_kudos-docs"
      :chunksCollectionName "DEV_kudos-chunks"
      :phrasesCollectionName "DEV_kudos-phrases"
      :stream_callback_msg1 nil
      :stream_callback_msg2 nil
      :streamCallbackFreqSec 2.0
      :maxResponseTokenCount nil})


   (def studio-params
     {:translated_user_query "Can you translate the resource texts to \"nynorsk\"?"
      :original_user_query "Kan du oversette ressurstekstene til nynorsk?"
      :user_query_language_name "Norwegian"
      :promptRagQueryRelax "You have access to a search API that returns relevant documentation.\nYour task is to generate an array of up to 7 search queries that are relevant to this question. Use a variation of related keywords and synonyms for the queries, trying to be as general as possible.\nInclude as many queries as you can think of, including and excluding terms. For example, include queries like ['keyword_1 keyword_2', 'keyword_1', 'keyword_2']. Be creative. The more queries you include, the more likely you are to find relevant results.\n",
      :promptRagGenerate "Use the following pieces of information to answer the user's question.\nIf you don't know the answer, just say that you don't know, don't try to make up an answer.\n\nContext: {context}\n\nQuestion: {question}\n\nOnly return the helpful answer below.\n\nHelpful answer:\n",
      :phrase-gen-prompt "keyword-search"
      :maxSourceDocCount 10
      :maxContextLength 10000
      :maxSourceLength 40000
      :docsCollectionName "DEV_studio-docs"
      :chunksCollectionName "DEV_studio-chunks"
      :phrasesCollectionName "DEV_studio-phrases"
      :stream_callback_msg1 nil
      :stream_callback_msg2 nil
      :streamCallbackFreqSec 2.0
      :maxResponseTokenCount nil})


   (def altinn-entity-id "ec8c8be0-587d-4269-804a-78ce493801b5")


   (def kudos-entity-id "71e1adbe-2116-478d-92b8-40b10a612d7b")

   (def ai-guide-entity-id "7i8dadbe-0101-f0e1-92b8-40b10a61cdcd")


   (comment

     (def conn @delayed-connection)

     (def ai-rag-params {:conversation-id (nano-id)
                         :entity-id ai-guide-entity-id
                         :translated_user_query "hvilke kategorier gjelder for KI system risiko?"
                         :original_user_query "hvilke kategorier gjelder for KI system risiko?"
                         :user_query_language_name "Norwegian"
                         :promptRagQueryRelax "You have access to a search API that returns relevant documentation.\nYour task is to generate an array of up to 7 search queries that are relevant to this question. Use a variation of related keywords and synonyms for the queries, trying to be as general as possible.\nInclude as many queries as you can think of, including and excluding terms. For example, include queries like ['keyword_1 keyword_2', 'keyword_1', 'keyword_2']. Be creative. The more queries you include, the more likely you are to find relevant results.\n",
                         :promptRagGenerate "Use the following pieces of information to answer the user's question.\nIf you don't know the answer, just say that you don't know, don't try to make up an answer.\n\nContext: {context}\n\nQuestion: {question}\n\nOnly return the helpful answer below.\n\nHelpful answer:\n",
                         :phrase-gen-prompt "keyword-search"
                         :maxSourceDocCount 10
                         :maxContextLength 10000
                         :maxSourceLength 40000
                         :docsCollectionName "AI-GUIDE_docs_2024-10-28"
                         :chunksCollectionName "AI-GUIDE_chunks_2024-10-28"
                         :phrasesCollectionName "AI-GUIDE_phrases_2024-10-28"
                         :stream_callback_msg1 nil
                         :stream_callback_msg2 nil
                         :streamCallbackFreqSec 2.0
                         :maxResponseTokenCount nil})

     (def kudos-rag-params {:conversation-id (nano-id)
                            :entity-id kudos-entity-id
                            :translated_user_query "DSB 2022"
                            :original_user_query "DSB 2022"
                            :user_query_language_name "Norwegian"
                            :promptRagQueryRelax "You have access to a search API that returns relevant documentation.\nYour task is to generate an array of up to 7 search queries that are relevant to this question. Use a variation of related keywords and synonyms for the queries, trying to be as general as possible.\nInclude as many queries as you can think of, including and excluding terms. For example, include queries like ['keyword_1 keyword_2', 'keyword_1', 'keyword_2']. Be creative. The more queries you include, the more likely you are to find relevant results.\n",
                            :promptRagGenerate "Use the following pieces of information to answer the user's question.\nIf you don't know the answer, just say that you don't know, don't try to make up an answer.\n\nContext: {context}\n\nQuestion: {question}\n\nOnly return the helpful answer below.\n\nHelpful answer:\n",
                            :phrase-gen-prompt "keyword-search"
                            :filter {:type :typesense
                                     :fields [{:type :multiselect
                                               :selected-options #{"Tildelingsbrev"}
                                               :field "type"}]}
                            :maxSourceDocCount 10
                            :maxContextLength 10000
                            :maxSourceLength 40000
                            :docsCollectionName "TEST_kudos_docs"
                            :chunksCollectionName "TEST_kudos_chunks"
                            :phrasesCollectionName "TEST_kudos_phrases"
                            :stream_callback_msg1 nil
                            :stream_callback_msg2 nil
                            :streamCallbackFreqSec 2.0
                            :maxResponseTokenCount nil})

     (def rag-results (rag-pipeline kudos-rag-params conn))

     ;;  (answer-followup-user-query conn (:conversation-id rag-results) "hvilke kriterie definerer høyrisiko?")

     (defn get-newest-conversation-id
       "Retrieves the ID of the most recently created conversation."
       []
       (let [query '[:find (max ?created) ?id
                     :where
                     [?e :conversation/id ?id]
                     [?e :conversation/created ?created]]
             result (d/q query @conn)]
         (when (seq result)
           (second (first result)))))

     (defn get-recent-conversations
       "Retrieves the 10 most recent conversations with their metadata.
           Returns a sequence of maps containing :id, :created, :topic"
       []
       (let [query '[:find ?id (max ?created) ?topic
                     :where
                     [?e :conversation/id ?id]
                     [?e :conversation/created ?created]
                     [?e :conversation/topic ?topic]
                     :limit 10]
             results (d/q query @conn)]
         (when (seq results)
           (->> results
                (map (fn [[id created topic]]
                       {:id id
                        :created created
                        :topic topic}))
                (sort-by :created >)))))

     (get-recent-conversations)

     (def rag-params (assoc kudos-rag-params :conversation-id "7zvrhkUE_3I2sEoSBiJ16"))

     (def rag-params (assoc kudos-rag-params :conversation-id (get-newest-conversation-id)))
     (def durations {:total 0
                     :analyze 0
                     :generate_searches 0
                     :execute_searches 0
                     :phrase_similarity_search 0
                     :colbert_rerank 0
                     :rag_query 0
                     :translation 0})


     ;; RAG stuff


     (def convo-id (:conversation-id rag-params))
     (db/transact-new-msg-thread conn {:convo-id convo-id
                                       :user-query (:original_user_query rag-params)
                                       :entity-id (:entity-id rag-params)})

     (def extract-search-queries (query-relaxation (:translated_user_query rag-params)
                                                   (:promptRagQueryRelax rag-params)))
     ;; durations (assoc durations :generate_searches (- (System/currentTimeMillis) start))

     (def search-phrase-hits (lookup-search-phrases-similar
                              (:phrasesCollectionName rag-params)
                              (:docsCollectionName rag-params)
                              extract-search-queries
                              (:phrase-gen-prompt rag-params)
                              (:filter rag-params)))
     (def retrieved-chunks (retrieve-chunks-by-id
                            (:docsCollectionName rag-params)
                            (:chunksCollectionName rag-params)
                            search-phrase-hits))

     ;; debug retrieve-chunks-by-id
     ;; seems to be a bug in Typesense. 
     ;; - Iif you recreate the docs collection, 
     ;; the chunks and phrases connection will fail silently in this query:
     (ts-client/multi-search ts-cfg
                             {:searches
                              (vector {:collection "TEST_kudos_chunks", :q "26803-1",
                                       :include_fields "id,chunk_id,doc_num,$TEST_kudos_docs(title,source_document_url)",
                                       :filter_by "chunk_id:=`26803-1`",
                                       :page 1, :per_page 1}),
                              :limit_multi_searches 40}
                             {:query_by "chunk_id"})


     (retrieve-chunks-by-id "KUDOS_docs_2024-12-10" "KUDOS_chunks_2024-12-10" (vector {:chunk_id "26803-1", :rank 0.699999988079071, :index 0}))

     (count retrieved-chunks)

     (def reranked-results (rerank-chunks retrieved-chunks rag-params))

     rag-params

     (def new-system-message
       (d/transact conn [{:conversation/id (:conversation-id rag-params)
                          :conversation/messages [{:message/id (nano-id)
                                                   :message/text formatted-message
                                                   :message/role :system
                                                   :message/voice :assistant
                                                   :message/created (System/currentTimeMillis)}]}]))

     (def rag-params (assoc rag-params :conversation-id (get-newest-conversation-id)))

     (rag-pipeline rag-params conn)

     (defn get-conversation-messages
       "Retrieves all messages for the specified conversation ID."
       [conn conversation-id]
       (let [query '[:find ?text ?role ?voice ?created
                     :in $ ?conv-id
                     :where
                     [?e :conversation/id ?conv-id]
                     [?e :conversation/messages ?m]
                     [?m :message/text ?text]
                     [?m :message/role ?role]
                     [?m :message/voice ?voice]
                     [?m :message/created ?created]]
             results (d/q query @conn conversation-id)]
         (->> results
              (map (fn [[text role voice created]]
                     {:text text
                      :role role
                      :voice voice
                      :created created}))
              (sort-by :created))))

     (get-conversation-messages conn "7zvrhkUE_3I2sEoSBiJ16")


     ;;
     )
