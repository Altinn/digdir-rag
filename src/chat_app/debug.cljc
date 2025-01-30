(ns chat-app.debug
  (:require #?(:clj [datahike.api :as d])
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.electric :as e]
            #?(:clj [models.db :refer [conn]])
            [hyperfiddle.electric-ui4 :as ui]))

#?(:clj (defonce !call-open-ai? (atom true)))
(defonce !debug? (atom false))

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
                        (dom/p (dom/props {:class "w-1/3 text-ellipsis overflow-hidden"})
                          (dom/text v))
                        (dom/p (dom/props {:class "w-8"})
                          (dom/text asserted))))))))))))))


(e/defn DBInspector [{:keys [!active-conversation !conversation-entity]}]
  (e/server
    (let [db (e/watch conn)
          group-by-tx (fn [results] (reduce (fn [acc [e a v tx asserted]]
                                              (update acc tx conj {:e e :a a :v v :asserted asserted}))
                                      {}
                                      results))
          db-data (let [results (d/q '[:find ?e ?a ?v ?tx ?asserted
                                       :where
                                       [?e ?a ?v ?tx ?asserted]] (d/history db))]
                    (reverse (sort (group-by-tx results))))]
      (e/client
        (dom/div (dom/props {:class "z-30 absolute top-0 right-0 h-48 h-full w-full bg-red-500 overflow-auto"}) ;w-1/2 
          (dom/p (dom/text "Active conversation: " (e/watch !active-conversation)))
          (dom/p (dom/text "Conversation entity: " (e/watch !conversation-entity)))
          ;; (dom/p (dom/text "View main: " view-main))
          ;; (dom/p (dom/text "Convo dragged: " convo-dragged))
          ;; (dom/p (dom/text "Folder dragged to : " folder-dragged-to))
          (ui/button (e/fn [] (e/server (swap! !call-open-ai? not)))
            (dom/props {:class "px-4 py-2 bg-white"})
            (dom/text "Call OpenAI?: "  (e/server (e/watch !call-open-ai?))))
          (TreeView. db-data))))))


(e/defn DebugController []
  (e/client
    (let [debug? (e/watch !debug?)]
      #_(ui/button
        (e/fn [] (swap! !debug? not))
        (dom/props {:class (str "absolute top-0 right-0 z-10 px-4 py-2 rounded text-black"
                             (if-not debug?
                               " bg-slate-500"
                               " bg-red-500"))})
        (dom/p (dom/text "Debug: " debug?))))))