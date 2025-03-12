(ns chat-app.config-ui
  (:require [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
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
