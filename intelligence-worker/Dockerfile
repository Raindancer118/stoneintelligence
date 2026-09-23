# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
# ai-gateway kommt aus der privaten Registry packages.tstieh.de: Zugang nur beim Bauen, als
# BuildKit-Secret (Maven-settings.xml mit dem Nur-Lese-Token) - er landet in keinem Image-Layer.
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    MAVEN_OPTS=-Djdk.tls.client.protocols=TLSv1.2 ./mvnw -q -pl intelligence-worker -am -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --home-dir /app worker
WORKDIR /app
COPY --from=build /workspace/intelligence-worker/target/intelligence-worker-*-exec.jar app.jar
USER worker
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
