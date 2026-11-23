package de.tstieh.stoneintelligence.worker;

import de.tstieh.stoneintelligence.worker.ingest.WorkerLoop;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Start-Up-Tests: der Worker startet ohne Datenbank - mit und ohne Anbindung an platform-api. */
class IntelligenceWorkerApplicationStartupTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    class OhneAnbindung {

        @Test
        void should_start_butNotPoll(ApplicationContext context) {
            assertThat(context.getBeansOfType(WorkerLoop.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "STONEINTELLIGENCE_PLATFORM_API_URL=http://localhost:1", "STONEINTELLIGENCE_AI_WORKER_TOKEN=test-token",
        "STONEINTELLIGENCE_WORKER_POLL_MS=3600000"})
    class MitAnbindung {

        @Test
        void should_start_andPollThePlatform(ApplicationContext context) {
            assertThat(context.getBeansOfType(WorkerLoop.class)).hasSize(1);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class Betriebsendpunkte {

        @org.springframework.boot.test.web.server.LocalServerPort
        int port;

        private java.net.http.HttpResponse<String> get(String path) throws Exception {
            return java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        }

        @Test
        void should_exposeProbesAndMetrics_butNeitherEnvNorConfig() throws Exception {
            assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
            assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
            assertThat(get("/actuator/prometheus").body()).contains("jvm_memory_used_bytes");
            assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
            assertThat(get("/actuator/configprops").statusCode()).isEqualTo(404);
        }
    }
}
