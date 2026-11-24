FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -pl platform-api -am -DskipTests package

FROM eclipse-temurin:25-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
WORKDIR /app
COPY --from=build /workspace/platform-api/target/platform-api-*-exec.jar app.jar
# Dateispeicher (ADR 0009): gehostet die Storage Box (CIFS, gehoert uid 1000), lokal ein benanntes
# Volume, das Besitz und Rechte dieses Verzeichnisses uebernimmt. Die API laeuft nicht als root.
RUN mkdir -p /data/files && chown 1000:1000 /data/files
USER 1000:1000
# Kein MaxRAMPercentage: ohne mem_limit saehe die JVM den RAM der ganzen VM.
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError"
COPY --chmod=755 ops/docker/healthcheck.sh /usr/local/bin/healthcheck
HEALTHCHECK --interval=30s --timeout=8s --start-period=90s --retries=3 CMD ["healthcheck", "8080", "/actuator/health"]
ENV STONEINTELLIGENCE_FILE_STORAGE_DIR=/data/files
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
