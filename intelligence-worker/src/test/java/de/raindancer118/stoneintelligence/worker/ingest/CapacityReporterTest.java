package de.raindancer118.stoneintelligence.worker.ingest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import de.raindancer118.stoneai.extract.LlmClient;
import io.github.raindancer118.aigateway.ProviderCapacity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityReporterTest {

    private final FakePlatform platform = new FakePlatform();

    @Test
    void should_reportEveryServicesProviders_withoutKeys_andWithIsoTimes() {
        var back = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        platform.services.add("lokal");
        LlmFactory llms = new LlmFactory() {
            @Override
            public LlmClient forService(ServiceModels.ServiceModel model) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, ProviderCapacity> refreshCapacity(ServiceModels.ServiceModel model) {
                if (model.serviceId().equals("lokal")) {
                    throw new IllegalStateException("Kein Provider der Modell-Ketten ist verfügbar");
                }
                return Map.of("groq", new ProviderCapacity("groq", 2, 0, true, back,
                    new ProviderCapacity.Quota(0, 14400, back), new ProviderCapacity.Quota(12, 6000, null),
                    new ProviderCapacity.Credits(1.5, null), back.minusSeconds(30)));
            }
        };

        new CapacityReporter(platform, llms, ServiceModels.from(Map.of())).report();

        var reports = platform.capacityReports.get("gemini");
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.provider()).isEqualTo("groq");
            assertThat(report.keys()).isEqualTo(2);
            assertThat(report.usableKeys()).isZero();
            assertThat(report.exhausted()).isTrue();
            assertThat(report.availableAgainAt()).isEqualTo(back.toString());
            assertThat(report.requests().remaining()).isZero();
            assertThat(report.requests().resetsAt()).isEqualTo(back.toString());
            assertThat(report.tokens().resetsAt()).isNull();
            assertThat(report.credits().remaining()).isEqualTo(1.5);
        });
        // Ohne baubaren Client: eine leere Meldung - das Dashboard zeigt "unbekannt" statt veraltet.
        assertThat(platform.capacityReports.get("lokal")).isEmpty();
    }

    @Test
    void should_keepGoing_whenPlatformIsUnreachable() {
        var unreachable = new FakePlatform() {
            @Override
            public List<String> services() {
                throw new java.io.UncheckedIOException(new java.io.IOException("connection refused"));
            }
        };

        new CapacityReporter(unreachable, model -> null, ServiceModels.from(Map.of())).report();
    }
}
