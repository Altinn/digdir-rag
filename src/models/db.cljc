(ns models.db
  (:require [nano-id.core :refer [nano-id]]
            [chat-app.kit :as kit]
            [clojure.edn :as edn]
            #?(:clj [datahike.api :as d])
            #?(:clj [datahike-jdbc.core])
            #?(:clj [aero.core :as aero])))

;; TODO: 
;; Remove the delayed connection and replace with environment variables for db creation

#?(:clj
   (def config-filename (System/getenv "ENTITY_CONFIG_FILE")))

#?(:clj
   (def config
     (let [_ (println (str "reading config from file: " config-filename))]
       (if config-filename
         (aero/read-config config-filename)
         (println (str "ENTITY_CONFIG_FILE env variable empty, unable to load config. This is expected during builds."))))))
#?(:clj
   (def cfg (get-in config [:db (:db-env config)])))

(def dh-schema
  [;; Folder
   {:db/ident :folder/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :folder/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; prompt.folder
   {:db/ident :prompt.folder/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :prompt.folder/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; Prompt
   {:db/ident :prompt/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   
   ;; Conversation
   {:db/ident :conversation/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :conversation/topic
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :conversation/messages
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}

   ;; Text message
   {:db/ident :message/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :message/text
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :message/completion
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}

   ;; Filter message
   {:db/ident :message/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :message.filter/value 
    :db/valueType :db.type/string ;; edn - clojure.edn/read-string
    :db/cardinality :db.cardinality/one}
   
   {:db/ident :active-key-name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :key/value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :user/id
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :user/email
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}])

;; (defonce create-db (when-not (d/database-exists? cfg) (d/create-database cfg)))
;; (defonce conn (d/connect cfg))
;; (defonce dh-schema-tx (d/transact conn {:tx-data dh-schema}))

#?(:clj
   (defn init-db []
     (if cfg
       (do
         (when-not (d/database-exists? cfg)
           (d/create-database cfg)
           (let [conn (d/connect cfg)]
             (d/transact conn {:tx-data dh-schema})))
         (d/connect cfg))
       (println (str "no db config loaded, skipping init-db")))))

#?(:clj
   (def delayed-connection (delay (init-db))))

#?(:clj (defonce conn @delayed-connection))


;; Queries 

#?(:clj
   (defn fetch-convo-messages [db convo-id-str]
     (sort-by first < (d/q '[:find ?msg-created ?msg-id ?msg-text ?msg-role
                             :in $ ?conv-id
                             :where
                             [?e :conversation/id ?conv-id]
                             [?e :conversation/messages ?msg]
                             [?msg :message/id ?msg-id]
                             [?msg :message/role ?msg-role]
                             [?msg :message/text ?msg-text]
                             [?msg :message/created ?msg-created]]
                           db convo-id-str))))

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

#?(:clj
   (defn fetch-convo-messages-mapped
     [dh-conn convo-id]
      (->> (d/q '[:find (pull ?msg [*])
                  :in $ ?convo-id
                  :where
                  [?c :conversation/id ?convo-id]
                  [?c :conversation/messages ?msg]]
                dh-conn
                convo-id)
           (map first)
           (map #(update % :message.filter/value edn/read-string))
           
           (sort-by :message/created <)
           #_T)))


#?(:clj
   (defn fetch-convo-entity-id [db convo-id]
     (d/q '[:find [?entity-id]
            :in $ ?conv-id
            :where
            [?e :conversation/id ?conv-id]
            [?e :conversation/entity-id ?entity-id]]
          db convo-id)))

#?(:clj
   (defn fetch-user-id [db user-email]
     (:user/id (d/pull conn '[:user/id] [:user/email user-email]))))

#?(:clj
   (defn conversations
     ([db search-text]
      (let [convo-eids (d/q '[:find [?c ...]
                              :in $ search-txt ?includes-fn
                              :where
                              [?m :message/text ?msg-text]
                              [?c :conversation/messages ?m]
                              [?c :conversation/topic ?topic]
                              [?c :conversation/entity-id ?entity-id]
                              (or-join [?msg-text ?topic]
                                       [(?includes-fn ?msg-text search-txt)]
                                       [(?includes-fn ?topic search-txt)])]
                            db search-text kit/lowercase-includes?)]
        (sort-by first > (d/q '[:find ?created ?e ?conv-id ?topic ?entity-id
                                :in $ [?e ...]
                                :where
                                [?e :conversation/id ?conv-id]
                                [?e :conversation/topic ?topic]
                                [?e :conversation/created ?created]
                                [?c :conversation/entity-id ?entity-id]]
                              db convo-eids))))
     ([db]
      (sort-by first > (d/q '[:find ?created ?e ?conv-id ?topic ?entity-id
                              :where
                              [?e :conversation/id ?conv-id]
                              [?e :conversation/topic ?topic]
                              [?e :conversation/created ?created]
                              [?c :conversation/entity-id ?entity-id]
                              (not [?e :conversation/folder])]
                            db)))))

#?(:clj
   (defn conversations-in-folder [db folder-id]
     (sort-by first > (d/q '[:find ?created ?c ?c-id ?topic ?folder-name ?entity-id
                             :in $ ?folder-id
                             :where
                             [?e :folder/id ?folder-id]
                             [?e :folder/name ?folder-name]
                             [?c :conversation/folder ?folder-id]
                             [?c :conversation/id ?c-id]
                             [?c :conversation/topic ?topic]
                             [?c :conversation/created ?created]
                             [?c :conversation/entity-id ?entity-id]]
                           db folder-id))))

#?(:clj
   (defn folders [db]
     (sort-by first > (d/q '[:find ?created ?e ?folder-id ?name
                             :where
                             [?e :folder/id ?folder-id]
                             [?e :folder/name ?name]
                             [?e :folder/created ?created]]
                           db))))

;;

;; Transactions
#?(:clj
   (defn transact-user-msg [conn {:keys [new-convo? convo-id user-query entity-id voice]}]
             ;TODO: 
             ; - handle passing this the db connection
             ; - create error when transacting to an existing conversation without a convo-id or the convo-id being present in the db 
     (let [time-point (System/currentTimeMillis)
           tx-data {:conversation/id convo-id
                    :conversation/messages {:message/id (nano-id)
                                            :message/text user-query
                                            :message/role :user
                                            :message/voice (or voice :user)
                                            :message/completion true
                                            :message/kind :kind/markdown
                                            :message/created time-point}}
           _ (prn "transact-user-msg called for query: " user-query)]
       (d/transact conn [tx-data]))))

#?(:clj
   (defn transact-assistant-msg [conn convo-id msg]
     (d/transact conn [{:conversation/id convo-id
                        :conversation/messages [{:message/id (nano-id)
                                                 :message/text msg
                                                 :message/role :assistant
                                                 :message/voice :assistant
                                                 :message/completion true
                                                 :message/kind :kind/markdown
                                                 :message/created (System/currentTimeMillis)}]}])))

#?(:clj
   (defn transact-new-msg-thread [conn {:keys [convo-id user-query entity-id]}]
     (let [time-point (System/currentTimeMillis)
           tx-data
           {:conversation/id convo-id
            :conversation/entity-id entity-id
            :conversation/topic user-query
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
                                     :message/created (inc time-point)}]}
           _ (prn "transact-new-msg-thread called for query: " user-query)]
       (d/transact conn [tx-data]))))

(defn transact-retrieval-prompt [conn convo-id prompt]
  (d/transact conn [{:conversation/id convo-id
                     :conversation/messages [{:message/id (nano-id)
                                              :message/text prompt
                                              :message/role :user
                                              :message/voice :agent
                                              :message/completion true
                                              :message/kind :kind/markdown
                                              :message/created (System/currentTimeMillis)}]}]))

(defn transact-retrieved-sources [conn convo-id formatted-msg]
  (d/transact conn [{:conversation/id convo-id
                         :conversation/messages [{:message/id (nano-id)
                                                  :message/text formatted-msg
                                                  :message/role :system
                                                  :message/voice :assistant
                                                  :message/completion false ;; this message doesn't get sent to the llm
                                                  :message/kind :kind/html
                                                  :message/created (System/currentTimeMillis)}]}]))

(defn create-folder [conn]
  (d/transact conn [{:folder/id (nano-id)
                     :folder/name "New folder"
                     :folder/created (System/currentTimeMillis)}]))

(defn rename-convo-topic [conn convo-id new-topic]
  (d/transact conn [{:db/id [:conversation/id convo-id]
                     :conversation/topic new-topic}]))

(defn rename-folder [conn folder-id new-folder-name]
  (d/transact conn [{:db/id [:folder/id folder-id]
                     :folder/name new-folder-name}]))

(defn delete-convo [conn convo-eid]
  (d/transact conn [[:db/retract convo-eid :conversation/id]])) ; TODO: develop consistency of id and eid usage

(defn delete-folder [conn folder-eid]
  (d/transact conn [[:db.fn/retractEntity folder-eid]]))

(defn clear-all-conversations [conn]
  (let [convo-eids (map :e (d/datoms @conn :avet :conversation/id))
        folder-eids (map :e (d/datoms @conn :avet :folder/id))
        m-eids  (set (map first (d/q '[:find ?m
                                       :in $ [?convo-id ...]
                                       :where
                                       [?convo-id :conversation/messages ?m]] @conn convo-eids)))
        retraction-ops (concat
                        (mapv (fn [eid] [:db.fn/retractEntity eid :conversation/id]) convo-eids)
                        (mapv (fn [eid] [:db.fn/retractEntity eid :folder/id]) m-eids)
                        (mapv (fn [eid] [:db.fn/retractEntity eid :folder/id]) folder-eids))]
    (d/transact conn retraction-ops)))

#?(:clj
   (defn transact-new-msg-thread2 [conn entity-id]
     (let [convo-id (nano-id)
           time-point (System/currentTimeMillis)
           tx-data
           {:conversation/id convo-id
            :conversation/entity-id entity-id
            :conversation/topic "Ny samtale"
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
                                     :message/voice :filter
                                     :message/created (inc time-point)
                                     :message/completion false
                                     :message.filter/value
                                     (pr-str {:type :typesense
                                              :fields [{:type :multiselect
                                                        :expanded? true
                                                        :selected-options #{}
                                                        :field "type"}
                                                       {:type :multiselect
                                                        :expanded? true
                                                        :selected-options #{}
                                                        :field "orgs_long"}
                                                       {:type :multiselect
                                                        :expanded? true
                                                        :selected-options #{}
                                                        :field "source_published_year"}]}
                                             
                                             )}]}
           _ (prn "transact-new-msg-thread2 called" )]
       (d/transact conn [tx-data])
       {:conversation-id convo-id})))


#?(:clj
   (defn set-message-filter [conn id new-message-filter]
     (prn [new-message-filter])
     (d/transact conn [{:db/id id
                        :message.filter/value (pr-str new-message-filter)}])
     nil))

(comment

  ;; Helpers

  (defn ->eid-action [data action-fn]
    (let [eid (second (first data))]
      (when eid
        (action-fn eid))))

  (defn ->id-action [data action-fn]
    (let [eid (nth (first data) 2)]
      (when eid
        (action-fn eid))))

  ;; Creation

  ;Create folder
  (create-folder conn)

  ; Existing conversations
  ; Ensure that the convo-id exists in the db
  (transact-user-msg {:convo-id "test-2"
                      :msg "hello world"})
  ; New conversation
  (transact-user-msg {:new-convo? true
                      :convo-id (nano-id)
                      :msg "test message"})
  
  ; New conversation
  (transact-new-msg-thread conn {:convo-id (nano-id)
                                 :user-query "hvilke kategorier gjelder for KI system risiko?"
                                 :entity-id "7i8dadbe-0101-f0e1-92b8-40b10a61cdcd" ;; AI Guide
                                 })
  

;; Queries
  (conversations @conn)
  (folders @conn)

;; Update 

  ; rename conversation topic
  (-> (conversations @conn)
      (->id-action #(rename-convo-topic conn % "new convo name")))

  ; rename folder 
  (-> (folders @conn)
      (->id-action #(rename-folder conn % "Updated folder name")))

  ;; Deletions 

  (-> (conversations @conn)
      (->eid-action #(delete-convo conn %)))

  (-> (folders @conn)
      (->eid-action #(delete-convo conn %)))

  (clear-all-conversations conn)

;; TODO
  ;(conversations-in-folder @conn ) 

;; Database migration scripts
  (require '[datahike.migrate :refer [export-db import-db]])

  (def local-cfg (get-in config [:db :local]))
  (def remote-cfg (get-in config [:db :remote]))

  (def create-local-db (when-not (d/database-exists? local-cfg) (d/create-database local-cfg)))

  (def local-conn (d/connect local-cfg))
  (def remote-conn (d/connect remote-cfg))

  (conversations @local-conn)
  (conversations @remote-conn)

  (export-db remote-conn "/tmp/eavt-dump")
  (import-db local-conn "/tmp/eavt-dump")

;; End comment
  )


