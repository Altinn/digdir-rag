(ns models.db
  (:require #?(:clj [datahike.api :as d])
            #?(:clj [datahike-jdbc.core])))

#?(:clj (def cfg {:store {:backend :jdbc
                          :dbtype "postgresql"
                          :host "aws-0-eu-central-1.pooler.supabase.com"
                          :port 6543
                          :dbname "postgres"
                          :table (System/getenv "ADH_POSTGRES_TABLE")
                          :user (System/getenv "ADH_POSTGRES_USER")
                          :password (System/getenv "ADH_POSTGRES_PWD")
                          :jdbcUrl "jdbc:postgresql://aws-0-eu-central-1.pooler.supabase.com:6543/postgres?pgbouncer=true&sslmode=require&prepareThreshold=0"}
                  :schema-flexibility :read}))

#_#?(:clj (def cfg {:store {:backend :mem :id "schemaless"}
                    :schema-flexibility :read}))

#?(:clj
   (def schema
     [{:db/ident :folder/id
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/unique :db.unique/identity}
      {:db/ident :folder/name
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one}
      {:db/ident :prompt.folder/id
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/unique :db.unique/identity}
      {:db/ident :prompt.folder/name
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one}
      {:db/ident :prompt/id
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/unique :db.unique/identity}
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
       :db/cardinality :db.cardinality/one}]))

#?(:clj 
   (defn init-db []
     (when-not (d/database-exists? cfg)
       (d/create-database cfg)
       (let [conn (d/connect cfg)]
         (d/transact conn {:tx-data schema})))
     (d/connect cfg)))

#?(:clj 
   (def delayed-connection (delay (init-db))))