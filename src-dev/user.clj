(ns user (:require [dev]
                   [datahike.api :as d]
                   [datahike-jdbc.core]
                   [chat-app.main :as main]
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