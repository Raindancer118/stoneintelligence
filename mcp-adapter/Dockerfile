FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -pl mcp-adapter -am -DskipTests package

FROM eclipse-temurin:25-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
RUN useradd --system --uid 10001 --no-create-home mcp
WORKDIR /app
COPY --from=build /workspace/mcp-adapter/target/mcp-adapter-*-exec.jar app.jar
USER mcp
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
COPY --chmod=755 ops/docker/healthcheck.sh /usr/local/bin/healthcheck
HEALTHCHECK --interval=30s --timeout=8s --start-period=90s --retries=3 CMD ["healthcheck", "8082", "/actuator/health"]
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
