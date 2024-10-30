# clj chatbot ui

This is a clone of [chatbot-ui](https://github.com/mckaywrigley/chatbot-ui) built in [electric](https://github.com/hyperfiddle/electric) and [datahike](https://github.com/replikativ/datahike).

I have made some minor modifications to the UI and UX and will do a full redesign once all the base issues are resolved.

## Run the application

`npm install` to install Tailwind and other javasscript dependencies

`npm run build:tailwind:dev` to build the css watch and build

Entities are collected from `config.edn`
Add this as a file to get started.

```clojure
{:all-entities-image "bot.svg"
 :entities [{:name "Chatbot"
             :image "bot.svg"
             :prompt ""}]}
```

Dev build:

* Shell: `clj -A:dev -X dev/-main`, or repl: `(dev/-main)`
* http://localhost:8080
* Electric root function: [src/electric_starter_app/main.cljc](src/electric_starter_app/main.cljc)
* Hot code reloading works: edit -> save -> see app reload in browser

Prod build:

```shell
clj -X:build:prod build-client
clj -M:prod -m prod
```


Deployment env variables required:

ENTITY_CONFIG_FILE
ADH_POSTGRES_PWD
ADH_POSTGRES_USER
ADH_POSTGRES_TABLE # remember, lowercase and underscores only

TYPESENSE_API_HOST
TYPESENSE_API_KEY

COLBERT_API_URL

USE_AZURE_OPENAI
AZURE_OPENAI_ENDPOINT
AZURE_OPENAI_DEPLOYMENT_NAME
AZURE_OPENAI_API_KEY

OPENAI_API_URL
OPENAI_API_MODEL_NAME
OPENAI_API_API_KEY
