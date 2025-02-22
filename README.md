# Kunnskapsassistent (prototype)

An AI tool for knowledge workers to analyze large document repositories.

RAG techniques ensure grounded responses to user queries. 


Tech stack:
- Electric Clojure v2
- Typesense v28
- Datahike + Postgres
- OpenAI or Azure OpenAI compatible inference API


## Run the application

`yarn` to install Tailwind and other javascript dependencies

`yarn build:tailwind:dev` to build the css watch and build


Dev build:

* Shell: `clj -A:dev -X dev/-main`, or repl: `(dev/-main)`
* http://localhost:8080
* Hot code reloading works: edit -> save -> see app reload in browser

Prod build:

```shell
clj -X:build:prod build-client
clj -M:prod -m prod
```


### Runtime env variables:

ADMIN_USER_EMAILS (space separated)
ALLOWED_DOMAINS (space separated, example: "@mydomain.com @yourdomain.com")
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
