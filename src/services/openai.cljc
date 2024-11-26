(ns services.openai 
  (:require #?(:clj [models.db :refer [conn
                                       transact-assistant-msg]])
            #?(:clj [wkok.openai-clojure.api :as api])))

#?(:clj (defn process-chunk [!stream-msgs]
          (fn [convo-id data]
            (let [delta (get-in data [:choices 0 :delta])
                  content (:content delta)]
              (if content
                (swap! !stream-msgs update-in [convo-id :content] (fn [old-content] (str old-content content)))
                (do
                  (swap! !stream-msgs assoc-in [convo-id :streaming] false)
                  (let [resp (:content (get @!stream-msgs convo-id))]
                    (transact-assistant-msg conn convo-id resp))
                  (swap! !stream-msgs assoc-in [convo-id :content] nil)))))))

#?(:clj (defn stream-chat-completion [!stream-msgs convo-id msg-list]
          (swap! !stream-msgs assoc-in [convo-id :streaming] true)
          (let [process-chunk-fn (process-chunk !stream-msgs)]
            (try (api/create-chat-completion
                   {:model "gpt-4o"
                    :messages msg-list
                    :stream true
                    :on-next #(process-chunk-fn convo-id %)})
              (catch Exception e
                (println "This is the exception: " e))))))

#?(:clj (defn get-chat-completion [!wait? convo-id msg-list]
          (let [_ (reset! !wait? true)
                _ (println "reset wait to true")
                _ (println "the msg list: " msg-list)
                raw-resp (api/create-chat-completion {:model "gpt-4o"
                                                      :messages msg-list})
                resp (get-in raw-resp [:choices 0 :message :content])]
            (transact-assistant-msg conn convo-id resp)
            (reset! !wait? false)
            (println "reset wait to false"))))

#?(:clj
   (defn create-chat-completion [messages]
     (if (= "true" (System/getenv "USE_AZURE_OPENAI"))
       (api/create-chat-completion
        {:model (System/getenv "AZURE_OPENAI_DEPLOYMENT_NAME")
         :messages messages
         :temperature 0.1
         :max_tokens nil}
        {:api-key (System/getenv "AZURE_OPENAI_API_KEY")
         :api-endpoint (System/getenv "AZURE_OPENAI_ENDPOINT")
         :impl :azure})
       (api/create-chat-completion
        {:model (System/getenv "OPENAI_API_MODEL_NAME")
         :messages messages
         :temperature 0.1
         :stream false
         :max_tokens nil}))))