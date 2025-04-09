# install js deps
FROM node:20-slim@sha256:5cfa999422613d3b34f766cbb814d964cbfcb76aaf3607e805da21cccb352bac AS node-deps
WORKDIR /app
COPY package.json yarn.lock .yarnrc.yml .yarn ./
RUN corepack enable && corepack prepare yarn@4.6.0 --activate && yarn install

# build stage
ENV JAVA_TOOL_OPTIONS="-Xmx3g"
FROM clojure:temurin-21-tools-deps-bullseye-slim@sha256:7d0f6e89c21962bf9925e2b298176ba56ef4e1f7cade9602bdff6a8119e290d2 AS build
WORKDIR /app
COPY --from=node-deps /app/node_modules /app/node_modules
COPY deps.edn shadow-cljs.edn package.json secrets.edn ./

COPY resources/ resources/
COPY src-build/ src-build/
COPY src-prod/ src-prod/
COPY config/ config/
COPY src/ src/
RUN clojure -A:build:prod -M -e ::ok
RUN clj -X:build:prod uberjar :build/jar-name app.jar

# runtime stage
FROM eclipse-temurin:21.0.6_7-jre-jammy@sha256:903dcea12637919198041da42cd270d549656c06bfda9126be36f182bb0de72d
WORKDIR /app
COPY --from=build /app /app
#COPY --from=build /app /app/resources/public/chat_app/js/manifest.edn

CMD ["java", "-cp", "app.jar", "clojure.main", "-m", "prod"]