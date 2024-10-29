FROM node:14.7-stretch AS node-deps
WORKDIR /app
COPY package.json package.json
RUN npm install

FROM clojure:temurin-21-tools-deps-bullseye-slim AS build
WORKDIR /app
COPY --from=node-deps /app/node_modules /app/node_modules
COPY deps.edn deps.edn
COPY shadow-cljs.edn shadow-cljs.edn
COPY package.json package.json
COPY config/ config/
COPY secrets.edn secrets.edn
COPY src src
COPY src-build src-build
COPY src-prod src-prod
COPY resources resources
RUN clojure -A:build:prod -M -e ::ok

ENV JAVA_TOOL_OPTIONS="-Xmx3g"

# RUN clj -X:build:prod build-client
RUN clj -X:build:prod uberjar :build/jar-name app.jar

# # Start a new stage for the runtime image
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
# Copy all build artifacts from the build stage
COPY --from=build /app /app
#COPY --from=build /app /app/resources/public/chat_app/js/manifest.edn

CMD ["java", "-cp", "app.jar", "clojure.main", "-m", "prod"]