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
# Lokales Embedding-Modell (ADR 0012): gepinnte Fassung, per Pruefsumme gesichert - Notizen verlassen
# fuer die Verlinkung den Server nie. Basis intfloat/multilingual-e5-small (MIT).
ADD --checksum=sha256:f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193 \
    https://huggingface.co/Xenova/multilingual-e5-small/resolve/761b726dd34fb83930e26aab4e9ac3899aa1fa78/onnx/model_quantized.onnx \
    /opt/models/multilingual-e5-small/model_quantized.onnx
ADD --checksum=sha256:0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39 \
    https://huggingface.co/Xenova/multilingual-e5-small/resolve/761b726dd34fb83930e26aab4e9ac3899aa1fa78/tokenizer.json \
    /opt/models/multilingual-e5-small/tokenizer.json
RUN chmod -R a+rX /opt/models
ENV STONEAI_EMBEDDING_MODEL_DIR=/opt/models/multilingual-e5-small
# Der Tokenizer entpackt seine native Bibliothek; das Home des Workers ist nicht beschreibbar.
ENV DJL_CACHE_DIR=/tmp/djl
# Antworten laufender Jobs (ADR 0010, lange Dokumente): ein Volume hier uebersteht das Neuerstellen des
# Containers, und ein wartender Job macht danach weiter, wo er aufgehoert hat.
RUN mkdir -p /data/ai-resume && chown worker:worker /data/ai-resume
WORKDIR /app
COPY --from=build /workspace/intelligence-worker/target/intelligence-worker-*-exec.jar app.jar
USER worker
ENV STONEAI_RESUME_DIR=/data/ai-resume
# Ein 100-MB-Buch liegt beim Einlesen mehrfach im Speicher; der Standard (25 % des Containers) reicht nicht.
# Heap 60 % des Containers (dorn: 1792 MB -> ~1,05 GB wie zuvor); der Rest bleibt fuer das lokale
# Embedding-Modell (ONNX Runtime, Tokenizer) ausserhalb des Heaps.
ENV JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
