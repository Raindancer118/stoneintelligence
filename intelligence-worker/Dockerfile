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
# Antworten laufender Jobs (ADR 0010, lange Dokumente): ein Volume hier uebersteht das Neuerstellen des
# Containers, und ein wartender Job macht danach weiter, wo er aufgehoert hat.
RUN mkdir -p /data/ai-resume && chown worker:worker /data/ai-resume
WORKDIR /app
COPY --from=build /workspace/intelligence-worker/target/intelligence-worker-*-exec.jar app.jar
USER worker
ENV STONEAI_RESUME_DIR=/data/ai-resume
# Ein 100-MB-Buch liegt beim Einlesen mehrfach im Speicher; der Standard (25 % des Containers) reicht nicht.
ENV JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
