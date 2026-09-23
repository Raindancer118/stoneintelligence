FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -pl platform-api -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/platform-api/target/platform-api-*-exec.jar app.jar
# Dateispeicher (ADR 0009): gehostet die Storage Box (CIFS, gehoert uid 1000), lokal ein benanntes
# Volume, das Besitz und Rechte dieses Verzeichnisses uebernimmt. Die API laeuft nicht als root.
RUN mkdir -p /data/files && chown 1000:1000 /data/files
USER 1000:1000
ENV STONEINTELLIGENCE_FILE_STORAGE_DIR=/data/files
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
