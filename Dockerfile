FROM clojure:temurin-21-tools-deps-bullseye-slim AS build
WORKDIR /app

COPY deps.edn deps.edn
RUN clojure -A:build:prod -M -e ::ok

COPY shadow-cljs.edn shadow-cljs.edn
COPY config.edn config.edn
COPY src src
COPY src-build src-build
COPY src-prod src-prod
COPY resources resources

ENV JAVA_TOOL_OPTIONS="-Xmx3g"

RUN clj -X:build:prod build-client
RUN clj -X:build:prod uberjar :build/jar-name app.jar

# # Start a new stage for the runtime image
# FROM eclipse-temurin:21-jre-jammy

# WORKDIR /app
# # Copy all build artifacts from the build stage
# COPY --from=build /app /app
# COPY --from=build /app /app/resources/public/chat_app/js/manifest.edn

CMD ["java", "-cp", "app.jar", "clojure.main", "-m", "prod"]