package de.raindancer118.stoneintelligence.worker;

import de.raindancer118.stoneintelligence.worker.ingest.WorkerLoop;
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
}
