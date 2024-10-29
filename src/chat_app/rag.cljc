(ns chat-app.rag
  (:require #?(:clj [clojure.data.json :as json])
            #?(:clj [typesense.client :as ts-client])
            #?(:clj [medley.core :as medley])
            #?(:clj [cheshire.core :as cheshire])
            #?(:clj [clojure.string :as str])
            #?(:clj [nano-id.core :refer [nano-id]])
            ;; [hashp.core :refer [p]]
            #?(:clj [clj-http.client :as http])
            #?(:clj [wkok.openai-clojure.api :as openai])
            #?(:clj [datahike.api :as d])
            #?(:clj [models.db :refer [delayed-connection]])))

(def stage-name "DOCS_QA_RAG")


#?(:clj (defonce !response-states (atom [])))

#?(:clj (defn env-var [key]
          (System/getenv key)))

#?(:clj (def use-azure-openai (= "true" (env-var "USE_AZURE_OPENAI_API"))))

#?(:clj (def ts-cfg {:uri (str "https://" (env-var "TYPESENSE_API_HOST"))
                     :key (env-var "TYPESENSE_API_KEY")}))

#?(:clj 
   (def search-results-tools
          [{:type "function"
            :function
            {:name "searchPhrases"
             :parameters
             {:type "object"
              :properties
              {:searchPhrases
               {:type "array"
                :items {:type "string"}}}}}}]))

#?(:clj
   (defn do-query-relaxation
     [user-input & [prompt-rag-query-relax]]
     (let [prompt (or prompt-rag-query-relax "")
           query-result (atom nil)
           _ (prn "user-input:" user-input)]
       (println (str stage-name " model name: " (env-var "OPENAI_API_MODEL_NAME")))
       #_(when (= (env-var "LOG_LEVEL") "debug")
           (println (str "prompt.rag.queryRelax: \n" prompt)))
       
       (reset! query-result
               (if use-azure-openai
                 (openai/create-chat-completion
                  {:model (env-var "AZURE_OPENAI_DEPLOYMENT_NAME")
                   :messages [{:role "system" :content "You are a helpful assistant. Reply with supplied JSON format."}
                              {:role "user" :content (str "[User query]\n" user-input)}]
                   :tools search-results-tools
                   :tool_choice {:type "function"
                                 :function {:name "searchPhrases"}}
                   :temperature 0.1
                   :max_tokens nil}
                  {:api-key (env-var "AZURE_OPENAI_API_KEY")
                   :api-endpoint (env-var "AZURE_OPENAI_ENDPOINT")
                   :impl :azure
                   :trace (fn [request response]
                            #_(println "Request:" request)
                            (println "Response:" response))})
                 (openai/create-chat-completion
                  {:model (env-var "OPENAI_API_MODEL_NAME")
                   :messages [{:role "system" :content "You are a helpful assistant. Reply with supplied JSON format."}
                              {:role "user" :content (str "[User query]\n" user-input)}]
                   :tools search-results-tools
                   :tool_choice {:type "function"
                                 :function {:name "searchPhrases"}}
                   :temperature 0.1
                   :max_tokens nil}
                  {:trace (fn [request response]
                              #_(println "Request:" request)
                              (println "Response:" response))})))
       (when @query-result
         (let [json (get-in @query-result [:choices 0 :message :tool_calls 0 :function :arguments])
               decoded-results (try
                                 (clojure.data.json/read-str json :key-fn keyword)
                                 (catch Exception e
                                   (println "Error decoding JSON:" (.getMessage e))
                                   nil))
               {:keys [searchPhrases]} decoded-results]
           searchPhrases)))))

;;       #_(doseq [i (range (count (:searchQueries @query-result)))]
;;          (swap! query-result update-in [:searchQueries i] #(-> % (.replace "GitHub" "") .trim)))

#?(:clj

   (defn query-relaxation [user-input & [prompt-rag-query-relax]]
     (let [max-retries 5
           retry-delay 500] ; 1 second delay between retries
       (loop [attempt 1]
         (let [result (try
                        (do-query-relaxation user-input prompt-rag-query-relax)
                        (catch Exception e
                          (println "Error in do-query-relaxation attempt" attempt ":" (.getMessage e))
                          nil))]
           (if (or result (>= attempt max-retries))
             result
             (do
               (Thread/sleep retry-delay)
               (recur (inc attempt)))))))))

#?(:clj
   (defn lookup-search-phrases-similar
     [phrases-collection-name relaxed-queries prompt]
     (if (or (nil? relaxed-queries) (nil? phrases-collection-name) (nil? prompt))
       (do
         (println "typesenseSearchMultiple() - search terms not provided")
         [])
       (let [multi-search-args {:searches (map (fn [query]
                                                 {:collection phrases-collection-name
                                                  :q query
                                                  :include_fields "chunk_id,search_phrase"
                                                  :exclude_fields "phrase_vec"
                                                  :filter_by (str "prompt:=`" prompt "`") ;; check if backtick escape needed when filter value has a space
                                                  ;; :group_by "chunk_id"
                                                  ;; :group_limit 1
                                                  :limit 20
                                                  :sort_by "_text_match:desc"
                                                  :prioritize_exact_match false
                                                  :drop_tokens_threshold 5})
                                            relaxed-queries)}]
         (when (= 1 1) #_(or true (= (env-var "LOG_LEVEL") "debug-relaxation"))
          ;;  (prn "lookupSearchPhraseSimilar query args:")
           (prn multi-search-args))
         (let [response (ts-client/multi-search ts-cfg multi-search-args {:query_by "search_phrase,phrase_vec"})
               indexed-search-phrase-hits (->> (:results response)
                                               (mapcat :hits)
                                               (map-indexed (fn [idx phrase]
                                                              (assoc phrase :index idx))))
               chunk-id-list (map (fn [phrase]
                                    {:chunk_id (get-in phrase [:document :chunk_id])
                                     :rank (get-in phrase [:hybrid_search_info :rank_fusion_score])
                                     :index (:index phrase)})
                                  indexed-search-phrase-hits)]
           (when (= 1 1) #_(= (env-var "LOG_LEVEL") "debug-relaxation")
            ;;  (prn "lookupSearchPhraseSimilar results:")
             (prn response))
           chunk-id-list)))))

#?(:clj
   (defn retrieve-all-by-url
     [chunks-collection-name url-list]
     (let [url-searches (map (fn [ranked-url]
                               {:collection chunks-collection-name
                                :q (:url ranked-url)
                                :include_fields "id,doc_num,url_without_anchor,type,content_markdown"
                                :filter_by (str "url_without_anchor:=`" (:url ranked-url) "`")
                                :group_by "url_without_anchor"
                                :group_limit 1
                                :page 1
                                :per_page 1})
                       ;; must be at least xx results, otherwise endpoint returns empty list
                       ;; TODO: add minimum check
                          (take 20 url-list))
           multi-search-args {:searches url-searches}]
       #_(prn "retrieve-chunks-by-id queries:")
       #_(prn multi-search-args)
       (ts-client/multi-search ts-cfg multi-search-args {:query_by "url_without_anchor"}))))

#?(:clj
   (defn retrieve-chunks-by-id
     [docs-collection-name chunks-collection-name chunk-id-list]
     (let [chunk-searches (map (fn [chunk-matches]
                                 {:collection chunks-collection-name
                                  :q (:chunk_id chunk-matches)
                                  :include_fields (str "id,chunk_id,doc_num,type,content_markdown,$" 
                                                       docs-collection-name "(title,type,source_document_url)")
                                  :filter_by (str "chunk_id:=`" (:chunk_id chunk-matches) "`")
                                  :page 1
                                  :per_page 1})
                       ;; must be at least xx results, otherwise endpoint returns empty list
                       ;; TODO: add minimum check
                               (take 20 (medley/distinct-by :chunk_id chunk-id-list)))
           multi-search-args {:searches chunk-searches
                              :limit_multi_searches 40}
          ;;  _ (prn "retrieve-chunks-by-id queries:")
          ;;  _ (prn multi-search-args)
           chunk-results (ts-client/multi-search ts-cfg multi-search-args {:query_by "chunk_id"})
           processed-results (->> chunk-results
                                  :results
                                  (mapcat :hits)
                                  (map :document))]
       processed-results)))


#?(:clj
   (defn retrieve-all-by-id
     [docs-collection-name id-list]

     (let [url-searches (map (fn [id]
                               {:collection docs-collection-name
                                :q "*"
                                :filter_by (str "id:=" id)
                                :include_fields "id,title,url_without_anchor"})
                             id-list)
           multi-search-args {:searches url-searches}
           response (ts-client/multi-search ts-cfg multi-search-args {:query_by "id"})]
       (map (fn [hit] (:document hit)) (:hits response)))
     ))

#?(:clj
   (defn rag-pipeline [params !dh-conn]
     (let [durations {:total 0
                      :analyze 0
                      :generate_searches 0
                      :execute_searches 0
                      :phrase_similarity_search 0
                      :colbert_rerank 0
                      :rag_query 0
                      :translation 0}
           total-start (System/currentTimeMillis)
           _ (println "rag-pipeline input params: " params)
           extract-search-queries (query-relaxation (:translated_user_query params)
                                                    (:promptRagQueryRelax params))
        ;; durations (assoc durations :generate_searches (- (System/currentTimeMillis) start))

           search-phrase-hits (lookup-search-phrases-similar
                               (:phrasesCollectionName params)
                               extract-search-queries
                               (:phrase-gen-prompt params))
          ;;  _ (println "first search-phrase-hit:" (first search-phrase-hits))
           search-hits (retrieve-chunks-by-id
                        (:docsCollectionName params)
                        (:chunksCollectionName params)
                        search-phrase-hits)

           ;; Add a new message to the db with top 4 URLs
           loaded-chunks (->> search-hits
                              (map-indexed
                               (fn [idx doc]
                                 (str
                                  "\n<details>\n<summary> "
                                  (get-in doc [(keyword (:docsCollectionName params)) :title])
                                  " <a href=\""
                                  (get-in doc [(keyword (:docsCollectionName params)) :source_document_url])
                                  "\" target=\"_blank\" title=\"Open source document\">&nbsp;&#8599;</a>\n"
                                  "</summary>\n" 
                                  (get-in doc [:content_markdown])
                                  "\n\n</details>\n")))
                              (clojure.string/join "\n"))
           formatted-message (str "Relevante kilder\n" loaded-chunks)
          ;;  _ (println "formatted-message: " formatted-message)
           _ (d/transact !dh-conn [{:conversation/id (:conversation-id params)
                                    :conversation/messages [{:message/id (nano-id)
                                                             :message/text formatted-message
                                                             :message/role :system
                                                             :message/voice :assistant
                                                             :message/completion false ;; this message doesn't get sent to the llm
                                                             :message/kind :kind/hiccup
                                                             :message/created (System/currentTimeMillis)}]}])
           all-chunk-ids (atom [])
           all-docs (atom [])
           loaded-docs (atom [])
           loaded-chunk-ids (atom [])
           loaded-search-hits (atom [])
           doc-index (atom 0)
           docs-length (atom 0)]

    ;; (prn durations)
    ;; (prn "Current time" (System/currentTimeMillis))
    ;; search-phrase-hits
    ;; search-response

    ;; Make list of all markdown content
       (while (< @doc-index (count search-hits))
         (let [search-hit (nth search-hits @doc-index)
               unique-chunk-id (:chunk_id search-hit)
               doc-md (:content_markdown search-hit)]
           (swap! doc-index inc)
           (when (and doc-md
                   (not (some #(= unique-chunk-id %) @all-chunk-ids)))
             (let [loaded-doc {:page_content doc-md
                               :metadata {:source unique-chunk-id}}]
               (swap! all-docs conj loaded-doc)
               (swap! all-chunk-ids conj unique-chunk-id)))))

    ;; Rerank results using ColBERT
       (let [rerank-url (env-var "COLBERT_API_URL")]
         (when (nil? rerank-url)
           (throw (ex-info (str "Environment variable 'COLBERT_API_URL' is invalid: '" rerank-url "'") {})))
         (let [rerank-data {:user_input (:translated_user_query params)
                            :documents (map
                                      ;; #(subs (:content_markdown %) 0 (:maxSourceLength params))  
                                         (fn [doc]
                                           (let [content (:content_markdown doc)]
                                             (if (> (count content) (:maxSourceLength params))
                                               (subs content 0 (:maxSourceLength params))
                                               content)))
                                         search-hits)}
            ;; Debugging the post operation
            ;; _ (prn "rerank-data" rerank-data)
            ;; _ (prn "rewrank-url" rerank-url)
               rerank-response (http/post rerank-url {:body (json/write-str rerank-data) :content-type :json})
            ;; _ (prn "rerank-response" rerank-response)
               rerank-response-body (json/read-str (:body rerank-response) :key-fn keyword)
            ;; _ (prn "rerank-response-body" rerank-response-body)
               search-hits-reranked (map #(nth search-hits (:result_index %)) rerank-response-body)]
           #_(swap! durations assoc :colbert_rerank (round (lap-timer start)))

          ;; Need to preserve order in documents list
           (reset! doc-index 0)
           (while (and (< @doc-index (count search-hits-reranked))
                    (or (< @docs-length (:maxContextLength params))
                      (< (count @loaded-docs) (:maxSourceDocCount params))))
             (let [search-hit (nth search-hits-reranked @doc-index)
                   unique-chunk-id (:chunk_id search-hit)
                   doc-md (:content_markdown search-hit)
                   source-desc (str
                                "\n```\nTitle: "
                                (get-in search-hit [(keyword (:docsCollectionName params)) :title])
                                "\n```\n\n")
                   doc-trimmed (if (> (count doc-md) (:maxSourceLength params))
                                 (subs doc-md 0 (:maxSourceLength params))
                                 doc-md)]
               (swap! doc-index inc)
               (when (and doc-trimmed
                       (not (some #(= unique-chunk-id %) @loaded-chunk-ids)))
                 (let [loaded-doc {:page_content (str source-desc doc-trimmed)
                                   :metadata {:source unique-chunk-id}}]
                   (swap! docs-length + (count doc-trimmed))
                   (swap! loaded-docs conj loaded-doc)
                   (swap! loaded-chunk-ids conj unique-chunk-id)
                   (swap! loaded-search-hits conj search-hit)
                   (when (or (>= @docs-length (:maxContextLength params))
                           (>= (count @loaded-docs) (:maxSourceDocCount params)))
                     (println (str "Limits reached, loaded " (count @loaded-docs) " docs.")))))))

    ;; Collect not loaded URLs
        ;; #_(let [not-loaded-urls (filter #(not (some (fn [loaded-url] (= loaded-url (:url %))) @loaded-urls)) search-hits)]
        ;;   (println "Not loaded URLs:" not-loaded-urls))
           (prn (str "Starting RAG structured output chain, llm: " (env-var "OPENAI_API_MODEL_NAME")))
        ;; (reset! start (System/currentTimeMillis))

           (let [context-yaml (clojure.string/join "\n\n" (map :page_content @loaded-docs))
                 partial-prompt (:promptRagGenerate params)
                 full-prompt (-> partial-prompt
                                 (str/replace "{context}" context-yaml)
                                 (str/replace "{question}" (:translated_user_query params)))
                 start-time (System/currentTimeMillis)
              ;; _ (prn full-prompt)
              ;; (if (nil? (:stream_callback_msg1 params)))
                 chat-response
                 (if use-azure-openai
                   (openai/create-chat-completion
                    {:model (env-var "AZURE_OPENAI_DEPLOYMENT_NAME")
                     :messages [{:role "system" :content "You are a helpful assistant."}
                                {:role "user" :content full-prompt}]
                     :temperature 0.1
                     :max_tokens nil}
                    {:api-key (env-var "AZURE_OPENAI_API_KEY")
                     :api-endpoint (env-var "AZURE_OPENAI_ENDPOINT")
                     :impl :azure
                     :trace (fn [request response]
                              #_(println "Request:" request)
                              (println "Response:" response))})
                   (openai/create-chat-completion
                    {:model (env-var "OPENAI_API_MODEL_NAME")
                     :messages [{:role "system" :content "You are a helpful assistant."}
                                {:role "user" :content full-prompt}]
                     :temperature 0.1
                     :stream false
                                  ;; :on-next (:on-next params)
                     :max_tokens nil}
                    {:trace (fn [request response]
                              #_(println "Request:" request)
                              (println "Response:" response))}))
                 end-time (System/currentTimeMillis)
                 duration (- end-time start-time)
                 _ (println (str "RAG query duration: " duration " ms"))
                 _ (prn "chat-response completed. response:" chat-response) ;; chat-response 
                ;;  _ (when-let [callback (:on-next params)]
                ;;      (do
                ;;        (callback chat-response)
                ;;        ;; to finalize the response
                ;;       ;;  (callback nil)
                ;;        ))
                 assistant-reply (:content (:message (first (:choices chat-response))))
                            ;; OpenAI streaming
                 #_(openai/chat-stream [{:role "system" :content "You are a helpful assistant."}
                                        {:role "user" :content full-prompt}]
                                       (:stream_callback_msg1 params)
                                       (or (:streamCallbackFreqSec params) 2.0)
                                       (:maxResponseTokenCount params))
                 english-answer (or assistant-reply "")
                 translated-answer english-answer
                 rag-success true
              ;; durations (assoc durations :rag_query (round (lap-timer start)))
                 translation-enabled false
              ;; durations (assoc durations :translation (round (lap-timer start)))
              ;; durations (assoc durations :total (round (lap-timer total-start)))
                 response {:original_user_query (:original_user_query params)
                           :english_user_query (:translated_user_query params)
                           :user_query_language_name (:user_query_language_name params)
                           :english_answer english-answer
                           :translated_answer translated-answer
                           :rag_success rag-success
                           :search_queries (or (:searchQueries extract-search-queries) [])
                           :source_urls @loaded-chunk-ids
                           :source_documents @loaded-docs
                           :relevant_urls []
                        ;; :not_loaded_urls not-loaded-urls
                           :durations durations
                           :prompts {:queryRelax (or (:promptRagQueryRelax params) "")
                                     :generate (or (:promptRagGenerate params) "")
                                     :fullPrompt full-prompt}}]
              ;;

             response))))
     ))

#?(:clj
   (def kudos-params
     {:translated_user_query "Hva har vegvesenet gjort innen innovasjon?"
      :original_user_query "Hva har vegvesenet gjort innen innovasjon?"
      :user_query_language_name "Norwegian"
      :promptRagQueryRelax "You have access to a search API that returns relevant documentation.\nYour task is to generate an array of up to 7 search queries that are relevant to this question. Use a variation of related keywords and synonyms for the queries, trying to be as general as possible.\nInclude as many queries as you can think of, including and excluding terms. For example, include queries like ['keyword_1 keyword_2', 'keyword_1', 'keyword_2']. Be creative. The more queries you include, the more likely you are to find relevant results.\n",
      :promptRagGenerate "Use the following pieces of information to answer the user's question.\nIf you don't know the answer, just say that you don't know, don't try to make up an answer.\n\nContext: {context}\n\nQuestion: {question}\n\nOnly return the helpful answer below.\n\nHelpful answer:\n",
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
      :maxResponseTokenCount nil}))

#?(:clj
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
      :maxResponseTokenCount nil}))

#?(:clj
   (def altinn-entity-id "ec8c8be0-587d-4269-804a-78ce493801b5"))

#?(:clj
   (def kudos-entity-id "71e1adbe-2116-478d-92b8-40b10a612d7b"))

#?(:clj
   (comment

     (def conn @delayed-connection)

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

     (def rag-params (assoc params :conversation-id (get-newest-conversation-id)))
     (def durations {:total 0
                     :analyze 0
                     :generate_searches 0
                     :execute_searches 0
                     :phrase_similarity_search 0
                     :colbert_rerank 0
                     :rag_query 0
                     :translation 0})

;; add arbitrary messages to the convo

     (defn new-convo-with-msg [entity-id topic user-query]
       (let [time-point (System/currentTimeMillis)
             convo-id (nano-id)]
         (d/transact conn [{:conversation/id convo-id
                            :conversation/entity-id entity-id
                            :conversation/topic topic
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
         convo-id))

     (defn add-convo-msg [convo-id role voice msg-text]
       (let [new-msg {:conversation/id convo-id
                      :conversation/messages [{:message/id (nano-id)
                                               :message/text msg-text
                                               :message/role role
                                               :message/voice voice
                                               :message/completion true
                                               :message/kind :kind/markdown
                                               :message/created (System/currentTimeMillis)}]}
             _ (prn new-msg)]
         (d/transact conn [new-msg])))

     ;; DEMO start - Translate text resources to nynorsk

     (def new-convo-id
       (new-convo-with-msg altinn-entity-id "oversette tekster til nynorsk" "kan du oversette tekstressursene til nynorsk?"))

     (add-convo-msg new-convo-id :assistant :assistant
                    "Her er oversettelsen til nynorsk:
```json
{
  \"language\": \"nn\",
  \"resources\": [
    {
      \"id\": \"appName\",
      \"value\": \"Byggjesøknad\"
    },
    {
      \"id\": \"next\",
      \"value\": \"Neste\"
    },
    {
      \"id\": \"back\",
      \"value\": \"Tilbake\"
    },
    {
      \"id\": \"eiendomsinformasjon.title\",
      \"value\": \"Eigedomsinformasjon\"
    },
    {
      \"id\": \"gårdsnummer.title\",
      \"value\": \"Gardsnummer\"
    },
    {
      \"id\": \"gårdsnummer.description\",
      \"value\": \"Skriv inn eigedomen sitt gardsnummer. Dette finn du i matrikkelen eller på eigedomsskatteseddelen din.\"
    },
    {
      \"id\": \"bruksnummer.title\",
      \"value\": \"Bruksnummer\"
    },
    {
      \"id\": \"bruksnummer.description\",
      \"value\": \"Skriv inn eigedomen sitt bruksnummer. Dette finn du saman med gardsnummeret.\"
    },
    {
      \"id\": \"festenummer.title\",
      \"value\": \"Festenummer (valfritt)\"
    },
    {
      \"id\": \"festenummer.description\",
      \"value\": \"Viss eigedomen har eit festenummer, skriv det inn her. La feltet stå tomt viss det ikkje er relevant.\"
    },
    {
      \"id\": \"seksjonsnummer.title\",
      \"value\": \"Seksjonsnummer (valfritt)\"
    },
    {
      \"id\": \"seksjonsnummer.description\",
      \"value\": \"Viss bustaden er ei seksjonert eining, skriv inn seksjonsnummeret her. La feltet stå tomt viss det ikkje er relevant.\"
    },
    {
      \"id\": \"tiltakshaver.title\",
      \"value\": \"Tiltakshavar (søkjar)\"
    },
    {
      \"id\": \"fornavn.title\",
      \"value\": \"Fornamn\"
    },
    {
      \"id\": \"fornavn.description\",
      \"value\": \"Skriv inn fornamnet til den som søkjer om byggjeløyve.\"
    },
    {
      \"id\": \"etternavn.title\",
      \"value\": \"Etternamn\"
    },
    {
      \"id\": \"etternavn.description\",
      \"value\": \"Skriv inn etternamnet til den som søkjer om byggjeløyve.\"
    },
    {
      \"id\": \"fødselsnummer.title\",
      \"value\": \"Fødselsnummer (11 siffer)\"
    },
    {
      \"id\": \"fødselsnummer.description\",
      \"value\": \"Skriv inn ditt 11-sifra fødselsnummer. Dette blir brukt for sikker identifisering.\"
    },
    {
      \"id\": \"epostadresse.title\",
      \"value\": \"E-postadresse\"
    },
    {
      \"id\": \"epostadresse.description\",
      \"value\": \"Oppgje ei gyldig e-postadresse. Vi vil sende viktig informasjon og oppdateringar til denne adressa.\"
    },
    {
      \"id\": \"telefonnummer.title\",
      \"value\": \"Telefonnummer\"
    },
    {
      \"id\": \"telefonnummer.description\",
      \"value\": \"Oppgje eit telefonnummer vi kan nå deg på viss vi treng å kontakte deg angåande søknaden.\"
    },
    {
      \"id\": \"tiltaksdetaljer.title\",
      \"value\": \"Tiltaket sin art\"
    },
    {
      \"id\": \"tiltakstype.title\",
      \"value\": \"Type tiltak\"
    },
    {
      \"id\": \"tiltakstype.description\",
      \"value\": \"Vel den typen byggjetiltak du søkjer om. Viss du er usikker, vel den som passar best og forklar nærare i skildringsfeltet.\"
    },
    {
      \"id\": \"beskrivelse.title\",
      \"value\": \"Skildring av tiltaket\"
    },
    {
      \"id\": \"beskrivelse.description\",
      \"value\": \"Gje ei kort skildring av kva du planlegg å byggje eller endre. Inkluder relevante detaljar som storleik, plassering på tomta, og føremål med tiltaket.\"
    },
    {
      \"id\": \"ansvarligSøker.title\",
      \"value\": \"Ansvarleg søkjar (viss aktuelt)\"
    },
    {
      \"id\": \"firma.title\",
      \"value\": \"Firma\"
    },
    {
      \"id\": \"firma.description\",
      \"value\": \"Viss du brukar eit firma som ansvarleg søkjar, skriv inn firmanamnet her. La feltet stå tomt viss du søkjer sjølv.\"
    },
    {
      \"id\": \"organisasjonsnummer.title\",
      \"value\": \"Organisasjonsnummer\"
    },
    {
      \"id\": \"organisasjonsnummer.description\",
      \"value\": \"Skriv inn firmaet sitt 9-sifra organisasjonsnummer. Dette feltet er obligatorisk viss du har fylt ut firmanamn.\"
    },
    {
      \"id\": \"nabovarsling.title\",
      \"value\": \"Nabovarsling\"
    },
    {
      \"id\": \"nabovarsling.options.label\",
      \"value\": \"Stadfest at du har varsla naboar og gjenbuarar om byggjeplanane. Dette er påkravd før du kan sende inn søknaden.\"
    },
    {
      \"id\": \"vedlegg.title\",
      \"value\": \"Vedlegg\"
    }
  ]
}
```
    
Om du vil, kan jeg lagre disse endringene direkte i appen eller som en PR.
                                                        ")

     (add-convo-msg new-convo-id :user :user "lagre direkte")

     (add-convo-msg new-convo-id :assistant :assistant "Endringene ble lagret.")


     ;; DEMO START - add a validation rule

     (def new-convo-id
       (new-convo-with-msg altinn-entity-id "validering, fornavn og etternavn" "fornavn og etternavn feltene må være minst 2 bokstaver"))

     (add-convo-msg new-convo-id :assistant :assistant

                    "For å implementere kravet om at \"fornavn\" og \"etternavn\" feltene må være minst 2 tegn lange, må vi modifisere dataskjemaet og legge til en valideringsregel. Her er hvordan vi kan gjøre dette:

1. Oppdater dataskjemaet:
I \"BegrensetTekst\" simple type, som brukes for både \"Fornavn\" og \"Etternavn\" i \"Tiltakshaver\" complex type, må vi endre minLength-verdien fra 1 til 2. Her er den oppdaterte definisjonen:

```xml
<xs:simpleType name=\"BegrensetTekst\">
  <xs:restriction base=\"xs:string\">
    <xs:minLength value=\"2\" />
    <xs:maxLength value=\"255\" />
  </xs:restriction>
</xs:simpleType>
```

2. Legg til en valideringsregel:
For å håndheve denne regelen i applikasjonen kan vi bruke \"stringLength\"-funksjonen i kombinasjon med \"greaterThanEq\"-funksjonen. Her er et eksempel på hvordan man kan lage en valideringsregel for begge feltene:

For \"Fornavn\":
```
greaterThanEq(stringLength(dataModel(\"Byggesøknad.Tiltakshaver.Fornavn\")), 2)
```

For \"Etternavn\":
```
greaterThanEq(stringLength(dataModel(\"Byggesøknad.Tiltakshaver.Etternavn\")), 2)
```

Disse valideringsreglene vil returnere true hvis lengden på det respektive feltet er større enn eller lik 2 tegn.

Du kan legge til disse valideringsreglene til de aktuelle komponentene i din Altinn Studio-applikasjon. Hvis valideringen mislykkes, bør du vise en feilmelding til brukeren som informerer dem om at fornavn og etternavn må være minst 2 tegn lange.
 ")



     ;; DEMO END


     ;; START DEMO - subscribe to events

     ;;  Q1:   How do I subscribe to events from a Java-based end user system?

     ;;  Q2:   How does Altinn figure out which objects to send event notifications for?


     ;; END DEMO



     ;; more RAG stuff

     (def extract-search-queries (query-relaxation (:translated_user_query params)
                                                   (:promptRagQueryRelax params)))

     (prn "extract-search-queries" extract-search-queries)
    ;;  (def multi-search-args {:searches (map (fn [query]
    ;;                                           {:collection (:phrasesCollectionName params)
    ;;                                            :q query
    ;;                                            :include_fields "chunk_id,search_phrase"
    ;;                                            :exclude_fields "phrase_vec"
    ;;                                            :filter_by (str "prompt:=`" "keyword-search" "`") ;; check if backtick escape needed when filter value has a space
    ;;                                                              ;; :group_by "chunk_id"
    ;;                                                              ;; :group_limit 1
    ;;                                            :limit 20
    ;;                                            :sort_by "_text_match:desc"
    ;;                                            :prioritize_exact_match false
    ;;                                            :drop_tokens_threshold 5})
    ;;                                         extract-search-queries)})

    ;;  (def response (ts-client/multi-search ts-cfg multi-search-args {:query_by "search_phrase,phrase_vec"}))

    ;;  (def indexed-search-phrase-hits (->> (:results response)
    ;;                                       (mapcat :hits)
    ;;                                       (map-indexed (fn [idx phrase]
    ;;                                                      (assoc phrase :index idx)))))

    ;;  (def chunk-id-list (map (fn [phrase]
    ;;                            {:chunk_id (get-in phrase [:document :chunk_id])
    ;;                             :rank (get-in phrase [:hybrid_search_info :rank_fusion_score])
    ;;                             :index (:index phrase)})
    ;;                          indexed-search-phrase-hits))

    ;;  (def chunk-searches (map (fn [chunk-matches]
    ;;                             {:collection (:chunksCollectionName params)
    ;;                              :q (:chunk_id chunk-matches)
    ;;                              :include_fields "id,chunk_id,doc_num,url_without_anchor,type,content_markdown" ;; ,$DEV_kudos-docs(title,type)
    ;;                              :filter_by (str "chunk_id:=`" (:chunk_id chunk-matches) "`")
    ;;                              :page 1
    ;;                              :per_page 1})
    ;;                              ;; must be at least xx results, otherwise endpoint returns empty list
    ;;                              ;; TODO: add minimum check
    ;;                           (take 20 (medley/distinct-by :chunk_id chunk-id-list))))

     #_(def multi-search-args {:searches chunk-searches
                               :limit_multi_searches 40})

     #_(def search-phrase-hits (ts-client/multi-search ts-cfg multi-search-args {:query_by "chunk_id"}))

     (def search-phrase-hits (lookup-search-phrases-similar
                              (:phrasesCollectionName params)
                              extract-search-queries
                              (:phrase-gen-prompt params)))
     ;; OBS: this (prn) will overload the console
     ;;   _ (prn "search-phrase-hits:" search-phrase-hits)

    ;;  (def chunk-results (ts-client/multi-search ts-cfg multi-search-args {:query_by "chunk_id"}))

    ;;  (def  processed-results (->> chunk-results
    ;;                               :results
    ;;                               (mapcat :hits)
    ;;                               (map :document)))

     (def search-hits (retrieve-chunks-by-id (:chunksCollectionName params)
                                             search-phrase-hits))



     (prn "retrieved docs, count:" (count search-hits))
                ;; Add a new message to the db with top 4 URLs
     (def top-4-urls (->> search-hits
                          (take 4)
                          (map-indexed
                           (fn [idx doc]
                             (str (inc idx) ". [" (get-in doc [(keyword (:docsCollectionName params)) :title]) "]("
                                  (get-in doc [(keyword (:docsCollectionName params)) :source_document_url]) ")")))
                          (clojure.string/join "\n")))
     (def formatted-message (str "Top 4 URLs:\n" top-4-urls))

     params

     (def new-system-message
       (d/transact conn [{:conversation/id (:conversation-id rag-params)
                          :conversation/messages [{:message/id (nano-id)
                                                   :message/text formatted-message
                                                   :message/role :system
                                                   :message/voice :assistant
                                                   :message/created (System/currentTimeMillis)}]}]))

     (def rag-params (assoc params :conversation-id (get-newest-conversation-id)))

     (rag-pipeline rag-params conn)

     ;;
     ))