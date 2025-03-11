(ns chat-app.entities
  (:require [chat-app.ui :as ui]
            [chat-app.chat :as chat]
            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.electric-ui4 :as ui4]
            #?(:clj [models.db :refer [update-config] :as db])))

;; Entity state
(e/def entities-cfg 
  (e/server (:chat (e/watch db/!config))))

(e/defn PromptEditor []
  (e/client
   (let [cfg entities-cfg
         entity (when cfg (-> cfg :entities first))
         {:keys [id promptRagGenerate promptRagQueryRelax]} (or entity {})
         !promptRagGenerate (atom promptRagGenerate)
         !promptRagQueryRelax (atom promptRagQueryRelax)
         promptGenerate (e/watch !promptRagGenerate)
         promptQueryRelax (e/watch !promptRagQueryRelax)]
     (if-not entity
       (dom/div (dom/text "Loading..."))
       (dom/div
        (dom/props {:class "m-2 h-full overflow-y-auto"})
        (dom/h3 (dom/text "Query relax prompt:"))
        (dom/div
         (dom/textarea
          (dom/props {:value (or promptQueryRelax "")
                      :style {:width "94%"
                              :height "200px"
                              :font-family "monospace"
                              :margin "10px"
                              :padding "10px"
                              :border "1px solid #ccc"
                              :border-radius "4px"}
                      :placeholder "Loading config file..."})
          (dom/on "change" (e/fn [e]
                             (when-some [v (not-empty (.. e -target -value))]
                               (reset! !promptRagQueryRelax v)))))

         (dom/h3 (dom/text "Generate prompt:"))
         (dom/textarea
          (dom/props {:value (or promptGenerate "")
                      :style {:width "94%"
                              :height "200px"
                              :font-family "monospace"
                              :margin "10px"
                              :padding "10px"
                              :border "1px solid #ccc"
                              :border-radius "4px"}
                      :placeholder "Loading config file..."})
          (dom/on "change" (e/fn [e]
                             (when-some [v (not-empty (.. e -target -value))]
                               (reset! !promptRagGenerate v)))))
         (dom/div (dom/props {:class (str "bottom-3" " absolute right-0 w-full border-transparent bg-gradient-to-b from-transparent via-white to-white pt-6 md:pt-2")})
          (dom/button
           (dom/on "click"
                   (e/fn [_]
                     (println "Saving prompts")
                     (e/server
                      (e/offload
                       #(update-config
                         {:id id
                          :promptRagGenerate promptGenerate
                          :promptRagQueryRelax promptQueryRelax}))
                      nil)))
           (dom/props {:class "px-4 py-2 bg-blue-500 text-white rounded-md hover:bg-blue-600 focus:outline-none"})
           (dom/text "Save changes")))))))))

(e/defn EntitySelector []
  (e/client
   (let [EntityCard (e/fn [id title img-src]
                      (ui4/button (e/fn []
                                    (let [entity (some #(when (= (:id %) id) %) (:entities entities-cfg))]
                                      (when entity
                                        (reset! ui/!view-main :pre-conversation)
                                        (reset! chat/!conversation-entity entity))))
                                  (dom/props {:class "flex flex-col gap-4 items-center
                                                      bg-white rounded-lg p-4 shadow-md
                                                      hover:shadow-lg transition-shadow duration-300
                                                      w-64 h-80"})
                                  (dom/img (dom/props {:class "w-32 h-32 object-cover rounded-full"
                                                       :src img-src}))
                                  (dom/h2 (dom/props {:class "text-xl font-bold text-gray-800"})
                                          (dom/text title))))]
     (dom/div (dom/props {:class "flex flex-col items-center justify-center h-full"})
              (dom/h1 (dom/props {:class "text-3xl font-bold mb-8"})
                      (dom/text "Velg en assistent"))
              (dom/div (dom/props {:class "flex flex-wrap gap-8 justify-center"})
                       (e/for [entity (:entities entities-cfg)]
                         (EntityCard. (:id entity) (:name entity) (:image entity))))))))
