# install js deps
FROM node:20-slim@sha256:d6e4ec9eaf2390129b5d23904d07ae03ef744818386bcab3fc45fe63405b5eb2 AS node-deps
WORKDIR /app
COPY package.json yarn.lock .yarnrc.yml .yarn ./
RUN corepack enable && corepack prepare yarn@4.6.0 --activate && yarn install

# build stage
ENV JAVA_TOOL_OPTIONS="-Xmx3g"
FROM clojure:temurin-21-tools-deps-bullseye-slim@sha256:cb89b4f470b8580f675365378dfd6b4c314873754c271d0213655ae030c6c539 AS build
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
FROM eclipse-temurin:21.0.6_7-jre-jammy@sha256:02fc89fa8766a9ba221e69225f8d1c10bb91885ddbd3c112448e23488ba40ab6
WORKDIR /app
COPY --from=build /app /app
#COPY --from=build /app /app/resources/public/chat_app/js/manifest.edn

CMD ["java", "-cp", "app.jar", "clojure.main", "-m", "prod"]