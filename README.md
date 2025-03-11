# Kunnskapsassistent (prototype)

An AI tool for knowledge workers to analyze large document repositories.

RAG techniques ensure grounded responses to user queries. 


Tech stack:
- Electric Clojure v2
- Typesense v28
- Datahike + Postgres
- OpenAI or Azure OpenAI compatible inference API
- ColBERT reranker

## Local development

### Install dependencies
`yarn` to install Tailwind and other javascript dependencies


`yarn build:tailwind:dev` to build the css watch and build


Dev build:

* Shell: `clj -A:dev -X dev/-main`, or repl: `(dev/-main)`
* http://localhost:8080
* Hot code reloading works: edit -> save -> see app reload in browser

Production build:

```shell
clj -X:build:prod build-client
clj -M:prod -m prod
```

## Docker

see `Dockerfile` 


### Runtime env variables:

For local development, you can specify values for Postgres, Typesense, Azure OpenAI and ColBERT reranker secrets in the `mise.development.local.toml` file (gitignored).

In production, specify the secrets in the environment variables of the server.