(ns chat-app.rag-test
  (:require [hyperfiddle.rcf :as rcf :refer [tests]]
            #?(:clj [chat-app.rag :as rag])
            #?(:clj [typesense.client :as ts-client])))

#?(:clj (rcf/enable!))

#?(:clj
   (tests "facet-result->ui-field"
  ; converts facet result to UI field with no selected options
          (let [filter-field {:field "category"
                              :selected-options #{}}
                options [{:count 5 :value "docs"}
                         {:count 3 :value "articles"}]
                result (rag/facet-result->ui-field filter-field options)]
            (= {:field "category"
                :selected-options #{}
                :options [{:count 5
                           :value "docs"
                           :selected? false}
                          {:count 3
                           :value "articles"
                           :selected? false}]}
               result))

  ; converts facet result to UI field with selected options
          (let [filter-field {:field "category"
                              :selected-options #{"docs"}}
                options [{:count 5 :value "docs"}
                         {:count 3 :value "articles"}]
                result (rag/facet-result->ui-field filter-field options)]
            (= {:field "category"
                :selected-options #{"docs"}
                :options [{:count 5
                           :value "docs"
                           :selected? true}
                          {:count 3
                           :value "articles"
                           :selected? false}]}
               result))))

#?(:clj
   (tests "fetch-facets"
  ; handles successful facet fetch
          (let [conversation-entity {:docs-collection "test-collection"}
                filter-map {:fields [{:field "category"
                                      :selected-options #{"docs"}}]}
                mock-results {:results [{:facet_counts [{:field_name "category"
                                                         :stats {:count 5}
                                                         :counts [{:count 5 :value "docs"}
                                                                  {:count 3 :value "articles"}]}]}]}]
            (with-redefs [ts-client/multi-search (constantly mock-results)]
              (let [result (rag/fetch-facets conversation-entity filter-map)]
                (= {:fields [{:field "category"
                              :selected-options #{"docs"}
                              :options [{:count 5
                                         :value "docs"
                                         :selected? true}
                                        {:count 3
                                         :value "articles"
                                         :selected? false}]}]}
                   result))))

  ; handles errors gracefully
          (let [conversation-entity {:docs-collection "test-collection"}
                filter-map {:fields [{:field "category"
                                      :selected-options #{"docs"}}]}]
            (with-redefs [ts-client/multi-search (fn [& _] (throw (Exception. "Test error")))]
              (let [result (rag/fetch-facets conversation-entity filter-map)]
                (= filter-map result))))))


(comment



  (def test-facet-results
    {:results
     [{:facet_counts
       [{:counts
         [{:count 2021, :highlighted "Tildelingsbrev", :value "Tildelingsbrev"}
          {:count 788, :highlighted "Årsrapport", :value "Årsrapport"}
          {:count 584, :highlighted "Evaluering", :value "Evaluering"}],
         :field_name "type",
         :sampled false,
         :stats {:total_values 3}}
        {:counts
         [{:count 394, :highlighted "Kunnskapsdepartementet", :value "Kunnskapsdepartementet"}
          {:count 392, :highlighted "Justis- og beredskapsdepartementet", :value "Justis- og beredskapsdepartementet"}
          {:count 322, :highlighted "Kommunal- og distriktsdepartementet", :value "Kommunal- og distriktsdepartementet"}],
         :field_name "orgs_long",
         :sampled false,
         :stats {:total_values 241}}]}]})

  (def test-facet-results
    {:results
     [{:facet_counts
       [{:counts
         [{:count 337, :highlighted "Tildelingsbrev", :value "Tildelingsbrev"}
          {:count 111, :highlighted "Årsrapport", :value "Årsrapport"}
          {:count 92, :highlighted "Evaluering", :value "Evaluering"}],
         :field_name "type",
         :sampled false,
         :stats {:total_values 3}}
        {:counts
         [{:count 161, :value "Norges Forskningsråd"}
          {:count 394, :value "Kunnskapsdepartementet"}],
         :field_name "orgs_long",
         :sampled false,
         :stats {:total_values 241}}]}]})

  (def test-entity {:id "71e1adbe-2116-478d-92b8-40b10a612d7b"
                    :name "Kunnskapsassistent - DEV"
                    :image "kudos-logo.png"
                    :docs-collection "NEXT_kudos_docs"
                    :chunks-collection "NEXT_kudos_chunks"
                    :phrases-collection "NEXT_kudos_phrases"
                    :phrase-gen-prompt "keyword-search"
                    :reasoning-languages ["en" "no"]
                    :prompt ""})

  (rag/options (:results test-facet-results))

    ;; Base case: nothing selected
  (= (->> (rag/fetch-facets test-entity
                            {:type :typesense
                             :fields [{:type :multiselect
                                       :expanded? true
                                       :selected-options #{}
                                       :field "type"}
                                      {:type :multiselect
                                       :expanded? true
                                       :selected-options #{}
                                       :field "orgs_long"}]})
          :ui/fields
          (mapv (juxt :field :options))
          set)
     #{["type" [{:count 32, :value "Tildelingsbrev", :selected? false} {:count 1, :value "Instruks", :selected? false} {:count 4, :value "Evaluering", :selected? false} {:count 10, :value "Årsrapport", :selected? false}]]

       ["orgs_short" [{:count 13, :value "Digdir", :selected? false} {:count 1, :value "NFD", :selected? false} {:count 32, :value "DFD", :selected? false} {:count 8, :value "DSB", :selected? false} {:count 1, :value "NGU", :selected? false} {:count 9, :value "KDD", :selected? false} {:count 8, :value "JD", :selected? false} {:count 1, :value "Statsforvalterens fellestjenester", :selected? false} {:count 1, :value "NFR", :selected? false} {:count 22, :value "Departementenes sikkerhets- og serviceorganisasjon", :selected? false}]]})

  (->> (rag/fetch-facets test-entity
                         {:type :typesense
                          :fields [{:type :multiselect
                                    :expanded? true
                                    :selected-options #{"Tildelingsbrev"}
                                    :field "type"}
                                   {:type :multiselect
                                    :expanded? true
                                    :selected-options #{}
                                    :field "orgs_short"}]})
       :ui/fields
       (mapv (juxt :field :options))
       set))

(comment


  (def docs-collection-name "KUDOS_docs_2025-01-08")
  (def docs-collection-name "TEST_kudos_docs")

  (def input-filter-maps
    [;; n 0
     {:type :typesense
      :max-options 30
      :fields [{:type :multiselect
                :selected-options #{"Bufdir"}
                :field "orgs_short"}
               {:type :multiselect
                :selected-options #{}
                :field "type"}]}
     ;; n 1
     {:type :typesense
      :max-options 30
      :fields [{:type :multiselect
                :selected-options #{"Bufdir"}
                :field "orgs_short"}
               {:type :multiselect
                :selected-options #{"Årsrapport"}
                :field "type"}]}

     ;; n 2
     {:type :typesense
      :fields [{:type :multiselect
                :expanded? true
                :selected-options #{}
                :field "type"}
               {:type :multiselect
                :expanded? true
                :selected-options #{}
                :field "orgs_long"}]}

     ;; n 3
     {:type :typesense
      :fields [{:type :multiselect
                :expanded? true
                :selected-options #{}
                :field "type"}
               {:type :multiselect
                :expanded? true
                :selected-options #{"Kunnskapsdepartementet" "Norges Forskningsråd"}
                :field "orgs_long"}]}

     ;; n 4
     {:type :typesense
      :fields [{:type :multiselect
                :expanded? true
                :selected-options #{}
                :field "type"}
               {:type :multiselect
                :expanded? true
                :selected-options #{"Norges Forskningsråd"}
                :field "orgs_long"}]}
     ;;
     ])

  ;; (def output-search-config
  ;;   (filter-map->typesense-facet-multi-search
  ;;    input-filter-map
  ;;    "KUDOS_docs_2024-12-10")) 


  (def target-search-config
    [;; n 0
     {:searches
      [{:q "*",
        :query_by "doc_num",
        :facet_by "orgs_short,type",
        :page 1,
        :filter_by "orgs_short:=[`Bufdir`]",
        :max_facet_values 10,
        :per_page 6,
        :collection "KUDOS_docs_2025-01-08"}
       {:query_by "doc_num",
        :collection "KUDOS_docs_2025-01-08",
        :q "*",
        :facet_by "orgs_short",
        :max_facet_values 10,
        :page 1}]}

     ;; n 1
     {:searches
      [{:q "*",
        :query_by "doc_num",
        :facet_by "orgs_short,type",
        :page 1,
        :filter_by "orgs_short: [`Bufdir`] && type:=[`Årsrapport`]",
        :max_facet_values 10,
        :per_page 6,
        :collection "KUDOS_docs_2025-01-08"}
       {:query_by "doc_num",
        :collection "KUDOS_docs_2025-01-08",
        :q "*",
        :facet_by "orgs_short",
        :filter_by "type:=[`Årsrapport`]",
        :max_facet_values 10,
        :page 1}
       {:query_by "doc_num",
        :collection "KUDOS_docs_2025-01-08",
        :q "*",
        :facet_by "type",
        :filter_by "orgs_short:=[`Bufdir`]",
        :max_facet_values 10,
        :page 1}]}

     ;; n 2
     {:searches
      [{:query_by "doc_num",
        :collection "KUDOS_docs_2025-01-08",
        :q "*",
        :facet_by "orgs_long,type",
        :max_facet_values 10,
        :page 1,
        :per_page 6}]}

     ;; n 3
     {:searches
      [{:q "*",
        :query_by "doc_num",
        :facet_by "orgs_long,type",
        :page 1,
        :filter_by
        "orgs_long:=[`Kunnskapsdepartementet`,`Norges Forskningsråd`]",
        :max_facet_values 10,
        :per_page 6,
        :collection "KUDOS_docs_2025-01-08"}
       {:query_by "doc_num",
        :collection "KUDOS_docs_2025-01-08",
        :q "*",
        :facet_by "orgs_long",
        :max_facet_values 10,
        :page 1}]}

     ;; n 4
     {:searches
      [{:q "*",
        :query_by "doc_num",
        :facet_by "orgs_long,type",
        :page 1,
        :filter_by
        "orgs_long:=[`Norges Forskningsråd`]",
        :max_facet_values 10,
        :per_page 6,
        :collection "KUDOS_docs_2025-01-08"}
       {:query_by "doc_num",
        :collection "KUDOS_docs_2025-01-08",
        :q "*",
        :facet_by "orgs_long",
        :max_facet_values 10,
        :page 1}]}

     ;;
     ])

  (def n 4)

  (defn relevant-search-keys [{:keys [searches]}]
    (mapv
     (fn [{:keys [facet_by filter_by]}]
       (into {}
             (keep identity
                   [(when (not-empty facet_by)
                      [:facet_by facet_by])
                    (when (not-empty filter_by)
                      [:filter_by filter_by])])))
     searches))

  (=
   ;; expected
   (relevant-search-keys (nth target-search-config n))
   ;; actual
   (relevant-search-keys (filter-map->typesense-facet-multi-search
                          (nth input-filter-maps n)
                          docs-collection-name)))

  (print-diff

   ;; actual
   #_(relevant-search-keys) (filter-map->typesense-facet-multi-search
                             (nth input-filter-maps n)
                             docs-collection-name)
   ;; expected
   #_(relevant-search-keys) (nth target-search-config n))


  (def conversation-entity {:id "71e1adbe-2116-478d-92b8-40b10a612d7b"
                            :name "Kunnskapsassistent - test"
                            :image "kudos-logo.png"
                            :docs-collection "NEXT_kudos_docs"
                            :chunks-collection "NEXT_kudos_chunks"
                            :phrases-collection "NEXT_kudos_phrases"
                            :phrase-gen-prompt "keyword-search"
                            :reasoning-languages ["en" "no"]
                            :prompt ""})

  (def searches (filter-map->typesense-facet-multi-search
                 (nth input-filter-maps n)
                 (:docs-collection conversation-entity)))
  (def searches {:searches
                 [{:q  "tildelingsbrevet for 2022",
                   :sort_by "_text_match:desc",
                   :limit 20,
                   :exclude_fields "phrase_vec",
                  ;;  :filter_by "$NEXT_kudos_docs(type:=[`Tildelingsbrev`] && orgs_long:=[`Barne-, ungdoms- og familiedirektoratet`])", 

                   ;; works
                  ;;  :filter_by "$NEXT_kudos_docs(type:=[`Tildelingsbrev`] )",  

                   ;; does NOT work
                   :filter_by "$NEXT_kudos_docs(orgs_long:=[`Barne-, ungdoms- og familiedirektoratet`])",
                    ;; :filter_by "$NEXT_kudos_docs(doc_num:=`26212`)",
                   :prioritize_exact_match false,
                   :drop_tokens_threshold 5,
                   :include_fields "chunk_id,search_phrase",
                   :collection "NEXT_kudos_phrases"}]})
  (try
    (let [results (:results (ts-client/multi-search
                             ts-cfg searches
                             {:query_by "search_phrase,phrase_vec"}))
          opts (options results)]
      results)
    (catch Exception e
      (println "Error in fetch-facets:"
               (.getMessage e) "Error: " (str e) "queries:" searches)))

  (ts-client/multi-search ts-cfg
                          {:searches [{:q "Instruks help",
                                       :sort_by "_text_match:desc", :limit 20,
                                       :exclude_fields "phrase_vec",
                                       :filter_by "$KUDOS_docs_2024-12-10(type:=['Instruks'])",
                                       :prioritize_exact_match false, :drop_tokens_threshold 5,
                                       :include_fields "chunk_id,search_phrase",
                                       :collection "KUDOS_phrases_2024-12-10"}]}
                          {:query_by "chunk_id"})

  (fetch-facets conversation-entity
                (filter-map->typesense-facet-multi-search
                 (nth input-filter-maps n)
                 docs-collection-name))
  )


