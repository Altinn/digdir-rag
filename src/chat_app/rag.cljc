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
            #?(:clj [services.openai :as llm])
            #?(:clj [datahike.api :as d])
            #?(:clj [models.db :as db :refer [delayed-connection]])
            #?(:clj [markdown.core :as md2])
            [lambdaisland.deep-diff2 :as ddiff]))
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

(defn print-diff [old new]
  (-> (ddiff/diff old new)
      ddiff/minimize
      ddiff/pretty-print ;; Printing directly with
                       ;; ddiff/pretty-print does not look
                       ;; right
      with-out-str
      print))

(def stage-name "DOCS_QA_RAG")


#?(:clj (defonce !response-states (atom [])))

;; Queue to store RAG jobs
#?(:clj (defonce !rag-jobs (atom [])))

#?(:clj
   (defn enqueue-rag-job
     "Add a new RAG job to the queue"
     [job-data]
     (swap! !rag-jobs conj job-data)))

#?(:clj
   (defn dequeue-rag-job
     "Remove and return the next RAG job from the queue"
     []
     (when-let [job (first @!rag-jobs)]
       (swap! !rag-jobs subvec 1)
       job)))

#?(:clj
   (defn has-pending-jobs?
     "Check if there are any pending RAG jobs"
     []
     (boolean (seq @!rag-jobs))))


#?(:clj (defn env-var [key]
          (System/getenv key)))

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
     [prompt-rag-query-relax messages]
     (let [query-result (atom nil)
           formatted-messages
           (map (fn [msg]
                  (str (if (= (:message/role msg) :user) "User: " "Assistant: ")
                       "\"" (:message/text msg) "\""))
                messages)
           message-string (clojure.string/join "\n\n" formatted-messages)
           _ (println "Formatted messages:")
           _ (println message-string)
           
           prompt (-> (or prompt-rag-query-relax "")
                      (str/replace "{messages}" message-string))]

       #_(when (= (env-var "LOG_LEVEL") "debug")
           (println (str "prompt.rag.queryRelax: \n" prompt)))

       (reset! query-result
               (if (llm/use-azure-openai)
                 (openai/create-chat-completion
                  {:model (env-var "AZURE_OPENAI_DEPLOYMENT_NAME")
                   :messages [{:role "user" :content prompt}]
                   :tools search-results-tools
                   :tool_choice {:type "function"
                                 :function {:name "searchPhrases"}}
                   :temperature 0.1
                   :max_tokens nil}
                  {:api-key (env-var "AZURE_OPENAI_API_KEY")
                   :api-endpoint (env-var "AZURE_OPENAI_ENDPOINT")
                   :impl :azure
                  ;;  :trace (fn [request response]
                  ;;           #_(println "Request:" request)
                  ;;           (println "Response:" response))
                   })
                 (openai/create-chat-completion
                  {:model (env-var "OPENAI_API_MODEL_NAME")
                   :messages [{:role "user" :content prompt}]
                   :tools search-results-tools
                   :tool_choice {:type "function"
                                 :function {:name "searchPhrases"}}
                   :temperature 0.1
                   :max_tokens nil}
                  {;;  :trace (fn [request response]
                  ;;           #_(println "Request:" request)
                  ;;           (println "Response:" response))
                   })))
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

   (defn query-relaxation [prompt-rag-query-relax messages]
     (let [max-retries 5
           retry-delay 500 ;; ms 
           ]
       (loop [attempt 1]
         (let [result (try
                        (do-query-relaxation prompt-rag-query-relax messages)
                        (catch Exception e
                          (println "Error in do-query-relaxation attempt" attempt ":" (.getMessage e))
                          nil))]
           (if (or result (>= attempt max-retries))
             result
             (do
               (Thread/sleep retry-delay)
               (recur (inc attempt)))))))))

#?(:clj
   (defn filter-map->typesense-filter
     "Converts a filter map to a Typesense filter_by string"
     [{:keys [fields]} docs-collection-name]
     (let [selected-fields (->> fields
                                (map (fn [{:keys [field selected-options]}]
                                       (when (seq selected-options)
                                         (str field ":=[" 
                                              (clojure.string/join "," 
                                              (map #(str "`" % "`") selected-options))
                                              "]"))))
                                (remove nil?))]
       (when (seq selected-fields)
         (str "$" docs-collection-name "("
              (clojure.string/join " && " selected-fields)
              ")")))))

(comment 
  

  (filter-map->typesense-filter
   {:fields [{:type :multiselect
              :selected-options #{"DFD"}
              :field "orgs_short"}
             {:type :multiselect
              :selected-options #{"Digdir"}
              :field "owner_short"}]}
   "my_collection")
  
  
)



#?(:clj
   (defn filter-map->typesense-facet-multi-search
     "Converts a filter map to a Typesense multi-search with disjunctive faceting"
     [{:keys [fields max-options]} docs-collection-name]
     (let [all-field-names (map :field fields)
           fields-with-selections (filter #(not-empty (:selected-options %)) fields)

           ;; Helper function to create filter string for selected options
           create-filter-str (fn [excluded-field]
                              (->> fields-with-selections
                                    (remove #(= (:field %) excluded-field))
                                    (map (fn [{:keys [field selected-options]}]
                                           (str field ":=[" 
                                                (clojure.string/join "," 
                                              (map #(str "`" % "`") selected-options))
                                                "]")))
                                    (clojure.string/join " && ")))

           ;; Main search with all filters and facets
           all-facets (into {}
                             (remove (fn [[_ v]] (#{"" nil} v)))
                             (merge
                              {:q "*"
                               :query_by "doc_num"
                               :facet_by (clojure.string/join "," all-field-names)
                               :page 1
                               :max_facet_values (or max-options 300)
                               :per_page 6
                               :collection docs-collection-name}
                              ))
           
           ;; Main search with all filters and facets
           main-search (into {} 
                             (remove (fn [[_ v]] (#{"" nil} v)))
                            (merge
                             {:q "*"
                              :query_by "doc_num"
                              :facet_by (clojure.string/join "," all-field-names)
                              :page 1
                               :max_facet_values (or max-options 300)
                              :per_page 6
                               :collection docs-collection-name}
                              (when (seq fields-with-selections)
                                {:filter_by (create-filter-str nil)})))

           ;; Individual facet searches - only for fields with selections
           facet-searches (map (fn [{:keys [field]}]
                                (into {}
                                      (remove (fn [[_ v]] (#{"" nil} v)))
                                      (merge
                                 {:q "*"
                                  :query_by "doc_num"
                                  :facet_by field
                                  :page 1
                                         :max_facet_values (or max-options 300)
                                         :collection docs-collection-name}
                                        (when-let [filter-str (create-filter-str field)]
                                          {:filter_by filter-str}))))
                              fields-with-selections)]

       {:searches (vec (concat [all-facets main-search] facet-searches))})))


(defn rcomp [& fns]
  (apply comp (reverse fns)))

#?(:clj
   (defn field->counts [facet_counts]
     (into {}
           (map (fn [{:keys [counts field_name]}]
                  [field_name counts]))
           facet_counts)))



#?(:clj 
   (defn options [res]
     (def options-input res)
     (let [all-facet-counts (mapcat :facet_counts res)
           ;; Group by field_name first
           grouped (group-by :field_name all-facet-counts)
           ;; For each field_name, combine counts taking max count for each value
           field->options
           (reduce-kv
            (fn [acc field-name field-entries]
              (let [all-counts (mapcat :counts field-entries)
                                  ;; Group counts by value and take max count for each
                    combined-counts (->> all-counts
                                         (group-by :value)
                                         (map (fn [[value entries]]
                                                (if (seq entries)
                                                  {:value value
                                                   :count (if (seq (rest entries))
                                                            (apply max (map :count (rest entries)))
                                                            0)}
                                                  {:value value
                                                   :count 0})))
                                         (sort-by :value)
                                         vec)]
                (assoc acc field-name combined-counts)))
            {}
            grouped)]
       field->options)))


#?(:clj
   (defn facet-result->ui-field [{:as filter-field :keys [selected-options]} options]
    (comment (facet-result->ui-field filter-field options))
    (assoc filter-field :options (mapv (fn [{:keys [count value]}]
                                         {:count count
                                          :value value
                                          :selected? (boolean (selected-options value))})
                                       options))))

#?(:clj
   (defn fetch-facets [conversation-entity filter-map]
     (let [multi-search (filter-map->typesense-facet-multi-search
                         filter-map
                         (:docs-collection conversation-entity))
          ;;  _ (prn-str "fetch-facets multi-search query: " multi-search)
           ]
       (try
         (let [results (:results (ts-client/multi-search
                                  ts-cfg multi-search {:query_by "doc_num"}))
               opts (options results)]
           (assoc filter-map :ui/fields
                  (mapv (fn [filter-field]
                          (facet-result->ui-field
                           filter-field (opts (:field filter-field))))
                        (:fields filter-map))))
         (catch Exception e
           (println "Error in fetch-facets:" (.getMessage e) "Error: " (str e) "queries:" multi-search)
           filter-map)))))
   

#?(:clj
   (defn prepare-conversation [dh-conn convo-id conversation-entity]
     (mapv
      #(cond-> %
         (:message.filter/value %) (update :message.filter/value (partial fetch-facets conversation-entity)))
      (db/fetch-convo-messages-mapped dh-conn convo-id))))

#?(:clj
   (defn lookup-search-phrases-similar
     [phrases-collection-name docs-collection-name relaxed-queries prompt filter-by]
     (println "lookup-search-phrases-similar()" "filter-by:" filter-by)
     (if (or (nil? relaxed-queries) (nil? phrases-collection-name) (nil? prompt))
       (do
         (println "typesenseSearchMultiple() - search terms not provided")
         [])
       (let [typesense-filter (filter-map->typesense-filter filter-by docs-collection-name)
             multi-search-args {:searches (map (fn [query]
                                                 (merge
                                                  {:collection phrases-collection-name
                                                   :q query
                                                   :include_fields "chunk_id,search_phrase"
                                                   :exclude_fields "phrase_vec"
                                                   :limit 20
                                                   :sort_by "_text_match:desc"
                                                   :prioritize_exact_match false
                                                   :drop_tokens_threshold 5}
                                                  (when (not-empty typesense-filter) 
                                                    {:filter_by typesense-filter})))
                                               relaxed-queries)}]
         (prn "lookup-search-phrases-similar queries:")
         (prn multi-search-args)
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
           #_(when (= 1 1)
             (prn "lookupSearchPhraseSimilar results:")
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
           _ (prn "retrieve-chunks-by-id queries:")
           _ (prn multi-search-args)
           chunk-results (ts-client/multi-search ts-cfg multi-search-args {:query_by "chunk_id"})
           processed-results (->> chunk-results
                                  :results
                                  (mapcat :hits)
                                  (map :document))]
       processed-results)))



#?(:clj
   (defn retrieve-facets
     [docs-collection-name fields]
     (-> (ts-client/multi-search
          ts-cfg
          {:searches
           [{:collection docs-collection-name
             :q "*"
             :include_fields "doc_num"
             :facet_by fields
             :page 1
             :per_page 1
             :max_facet_values 30}]}
          {:query_by "doc_num"})
         (get-in [:results 0 :facet_counts 0 :counts]))))

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
       (map (fn [hit] (:document hit)) (:hits response)))))

#?(:clj
   (defn rag-generate [!dh-conn convo-id extract-search-queries formatted-message full-prompt params loaded-chunk-ids loaded-docs]
     (let [start-time (System/currentTimeMillis)
           _ (prn (str "\n\n*** Starting RAG structured output chain, llm: " (env-var "OPENAI_API_MODEL_NAME") " full-prompt: \n\n"))
           ;;  _ (prn full-prompt)
           chat-response
           (if (llm/use-azure-openai)
             (openai/create-chat-completion
              {:model (env-var "AZURE_OPENAI_DEPLOYMENT_NAME")
               :messages [{:role "system" :content "You are a helpful assistant."}
                          {:role "user" :content full-prompt}]
               :temperature 0.1
               :max_tokens nil}
              {:api-key (env-var "AZURE_OPENAI_API_KEY")
               :api-endpoint (env-var "AZURE_OPENAI_ENDPOINT")
               :impl :azure})
             (openai/create-chat-completion
              {:model (env-var "OPENAI_API_MODEL_NAME")
               :messages [{:role "system" :content "You are a helpful assistant."}
                          {:role "user" :content full-prompt}]
               :temperature 0.1
               :stream false
               :max_tokens nil}))
           end-time (System/currentTimeMillis)
           duration (- end-time start-time)
           _ (println (str "RAG query duration: " duration " ms"))
           assistant-reply (:content (:message (first (:choices chat-response))))
           english-answer (or assistant-reply "")
           translated-answer english-answer
           rag-success true
           ;; durations (assoc durations :rag_query (round (lap-timer start)))
           translation-enabled false
           ;; durations (assoc durations :translation (round (lap-timer start)))
           ;; durations (assoc durations :total (round (lap-timer total-start)))

           response {:conversation-id convo-id
                     :entity-id (:entity-id params)
                     :original_user_query (:original_user_query params)
                     :english_user_query (:translated_user_query params)
                     :user_query_language_name (:user_query_language_name params)
                     :english_answer english-answer
                     :translated_answer translated-answer
                     :rag_success rag-success
                     :search_queries (or (:searchQueries extract-search-queries) [])
                     :source_urls loaded-chunk-ids
                     :source_documents loaded-docs
                     :relevant_urls []
                     ;; :not_loaded_urls not-loaded-urls
                     ;;  :durations durations
                     :prompts {:queryRelax (or (:promptRagQueryRelax params) "")
                               :generate (or (:promptRagGenerate params) "")
                               :fullPrompt full-prompt}}
           ;; Get one-line summary of original query

           ;;
           ]
       ;;

       response)))

#?(:clj
   (defn simplify-convo-topic [!dh-conn params]
     (let [summary-response
           (if (llm/use-azure-openai)
             (openai/create-chat-completion
              {:model (env-var "AZURE_OPENAI_DEPLOYMENT_NAME")
               :messages [{:role "system"
                           :content "Provide a 3 to 5 word summary of the user's query, use the same language as the user."}
                          {:role "user"
                           :content (str "<USER_QUERY>" (:original_user_query params) "</USER_QUERY>")}]
               :temperature 0.1
               :max_tokens 30}
              {:api-key (env-var "AZURE_OPENAI_API_KEY")
               :api-endpoint (env-var "AZURE_OPENAI_ENDPOINT")
               :impl :azure
               ;; :trace (fn [request response]
               ;;          #_(println "Request:" request)
               ;;          (println "Response:" response))
               })
             (openai/create-chat-completion
              {:model (env-var "OPENAI_API_MODEL_NAME")
               :messages [{:role "system"
                           :content "Provide a 3 to 5 word summary of the user's query, use the same language as the user."}
                          {:role "user"
                           :content (str "<USER_QUERY>" (:original_user_query params) "</USER_QUERY>")}]
               :temperature 0.1
               :max_tokens 30}))
           _ (println (str "summary: ") summary-response)
           summary (-> summary-response :choices first :message :content)
           _ (println "Query summary:" summary)
           _ (db/rename-convo-topic !dh-conn (:conversation-id params) summary)])))


#?(:clj
   (defn rerank-chunks
     [retrieved-chunks params]
     (let [all-chunk-ids (atom [])
           all-docs (atom [])
           loaded-docs (atom [])
           loaded-chunk-ids (atom [])
           loaded-search-hits (atom [])
           doc-index (atom 0)
           docs-length (atom 0)]
       ;; Make list of all markdown content
       (while (< @doc-index (count retrieved-chunks))
         (let [search-hit (nth retrieved-chunks @doc-index)
               ;;  _ (prn-str (str "search hit #" @doc-index ": ") search-hit)
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
                                        retrieved-chunks)}
               rerank-data (json/write-str rerank-data)
               ;;  _ (println (str "Rerank data: " rerank-data))
               rerank-response (http/post rerank-url {:body rerank-data :content-type :json})
               ;;  _ (println (str "Rerank response: " rerank-response))
               rerank-response-body (json/read-str (:body rerank-response) :key-fn keyword)
               _ (when (empty? rerank-response-body)
                   (println "***  Warning: Rerank response was empty, falling back to original ranking ***"))
               search-hits-reranked (if (empty? rerank-response-body)
                                      retrieved-chunks  ; fallback to original ranking
                                      (map #(nth retrieved-chunks (:result_index %)) rerank-response-body))]
           #_(swap! durations assoc :colbert_rerank (round (lap-timer start)))

           ;; Need to preserve order in chunks list
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
                   (when (>= @docs-length (:maxContextLength params))
                     (println (str "Context window limit reached, loaded " (count @loaded-docs) " chunks")))
                   (when (>= (count @loaded-docs) (:maxSourceDocCount params))
                     (println (str "maxSourceDocCount limit reached, loaded " (count @loaded-docs) " chunks")))))))

           (let [context-yaml (clojure.string/join "\n\n" (map :page_content @loaded-docs))
                 _ (println-str "\n\n******** context-yaml *********  :\n\n" context-yaml)
                 partial-prompt (:promptRagGenerate params)
                 full-prompt (-> partial-prompt
                                 (str/replace "{context}" context-yaml)
                                 (str/replace "{question}" (:translated_user_query params)))]
             {:loaded-chunk-ids @loaded-chunk-ids
              :loaded-docs @loaded-docs
              :full-prompt full-prompt}))))))

#?(:clj
   (defn rag-pipeline [params !dh-conn]
     (let [;;  _ (assoc params :durations  {:start (System/currentTimeMillis)
           ;;                               :total 0
           ;;                               :analyze 0
           ;;                               :generate_searches 0
           ;;                               :execute_searches 0
           ;;                               :phrase_similarity_search 0
           ;;                               :colbert_rerank 0
           ;;                               :rag_query 0
           ;;                               :translation 0}) 
           ;;  _ (println "rag-pipeline input params: " params)
           convo-id (:conversation-id params)
           
           ;; conversation always exists
           _ (println "starting to transact user message...")
           _ (db/transact-user-msg !dh-conn convo-id (:original_user_query params))
           _ (println "done transacting user message.")

           messages (vec (db/fetch-convo-messages-mapped @!dh-conn convo-id))

           filter-messages (filterv #(some? (:message.filter/value %)) messages)

           filter-by (-> (last filter-messages) :message.filter/value)

           retrieval-prompt-msg (first (filter #(and (= :user (:message/role %))
                                                     (= :agent (:message/voice %))
                                                     (= true (:message/completion %))
                                                     (= :kind/markdown (:message/kind %)))
                                               messages))
           retrieval-prompt-msg-id (when retrieval-prompt-msg (:message/id retrieval-prompt-msg))

           retrieved-sources-msg (first (filter #(and (= :system (:message/role %))
                                                      (= :assistant (:message/voice %))
                                                      (= false (:message/completion %))
                                                      (= :kind/html (:message/kind %)))
                                                messages))
           retrieved-sources-msg-id (when retrieved-sources-msg (:message/id retrieved-sources-msg))

           ;; we send all completion-messages to the llm
           completion-messages (filter #(not= false (:message/completion %))
                                       messages)
           _ (prn "fetched" (count completion-messages) "completion-messages")

           ;; TODO: for each convo turn,
           ;;   - update the message/text for retrieval-prompt and retrieved-sources messages 
           ;; NB: should find a way to preserve each version of these two messages, instead of overwriting


           _ (println "starting query relaxation...")
           _ (reset! !response-states ["Ser etter dokumenter"])

           extract-search-queries (query-relaxation (:promptRagQueryRelax params) completion-messages)
           _ (println "done query relaxation.")

           _ (println "starting to lookup search phrases...")
           search-phrase-hits (lookup-search-phrases-similar
                               (:phrasesCollectionName params)
                               (:docsCollectionName params)
                               extract-search-queries
                               (:phrase-gen-prompt params)
                               filter-by)
           _ (println "done lookup search phrases, found count:" (count search-phrase-hits))
           _ (when (empty? search-phrase-hits)
               (reset! !response-states ["Ingen søkefraser funnet"])
               (throw (ex-info "No search phrase found" {})))
           ;;  _ (println "first search-phrase-hit:" (first search-phrase-hits))

           _ (println "retrieving docs from " (:docsCollectionName params) "and chunks from" (:chunksCollectionName params))
           retrieved-chunks (retrieve-chunks-by-id
                             (:docsCollectionName params)
                             (:chunksCollectionName params)
                             search-phrase-hits)
           _ (println "chunks retrieved count:" (count retrieved-chunks))
           _ (when (empty? retrieved-chunks)
               (reset! !response-states ["Ingen kilder funnet"])
               (throw (ex-info "No sources found" {})))
           ;;  _ (println "retrieved chunks:" retrieved-chunks)

           _ (println "starting to format retrieved chunks...")
           loaded-chunks (->> retrieved-chunks
                              (map-indexed
                               (fn [idx doc]
                                 (let [content-markdown (get-in doc [:content_markdown])]
                                   (str
                                    "\n<details>\n<summary> "
                                    (get-in doc [(keyword (:docsCollectionName params)) :title])
                                    " <a href=\""
                                    "https://kudos.dfo.no/documents/"
                                    (get-in doc [:doc_num])
                                    "/files/"
                                    "\" target=\"_blank\" title=\"Open source document\">&nbsp;&#8599;</a>\n"
                                    "</summary>\n"
                                    (when-not (nil? content-markdown)
                                      (md2/md-to-html-string content-markdown))
                                    "\n\n</details>\n"))))
                              (clojure.string/join "\n"))
           formatted-message (str "<details><summary>Relevante kilder</summary>\n"
                                  loaded-chunks
                                  "\n</details>"
                                  "\n<details>\n<summary>Nøkkelord</summary>"
                                  (clojure.string/join ", " (map #(str "\"" % "\"")
                                                                 extract-search-queries))
                                  "\n</details>")
           _ (println "done formatting retrieved chunks.")

           _ (println "starting to rerank chunks...")
           _ (reset! !response-states ["Sorterer rekkefølgen"])
           {:keys [loaded-chunk-ids loaded-docs full-prompt]} (rerank-chunks retrieved-chunks params)
           _ (println "done reranking chunks.")

           _ (when (empty? loaded-docs)
               (println "***** Error: No documents were loaded during reranking. *****"))

           _ (println "starting to transact retrieval prompt...")
           _ (db/transact-retrieval-prompt !dh-conn convo-id retrieval-prompt-msg-id full-prompt)
           _ (println "done transacting retrieval prompt.")

           _ (println "starting to generate response...")
           _ (reset! !response-states ["Skriver svar"])
           generation-result (rag-generate !dh-conn convo-id extract-search-queries
                                           formatted-message full-prompt params loaded-chunk-ids loaded-docs)
           _ (println "done generating response.")

           _ (println "starting to transact assistant msg...")
           _ (db/transact-assistant-msg !dh-conn convo-id (:english_answer generation-result))
           _ (println "done transacting assistant msg.")

           _ (println "starting to transact retrieved sources...")
           _ (db/transact-retrieved-sources !dh-conn convo-id retrieved-sources-msg-id formatted-message)
           _ (println "done transacting retrieved sources.")

           _ (println "simplifying conversation topic...")
           _ (simplify-convo-topic !dh-conn params)
           _ (println "done simplifying conversation topic.")]
       generation-result))
   ;;
   )

