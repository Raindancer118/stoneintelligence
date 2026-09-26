package de.tstieh.stoneintelligence.worker.ingest;

import java.time.LocalDate;
import de.tstieh.stoneintelligence.worker.platform.PlatformApi;
import de.tstieh.stoneintelligence.worker.platform.PlatformHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Der Worker laeuft nur mit Adresse von platform-api und Worker-Token
 * ({@code STONEINTELLIGENCE_PLATFORM_API_URL}, {@code STONEINTELLIGENCE_AI_WORKER_TOKEN}); ohne
 * beides startet er, tut aber nichts.
 */
@Configuration
@EnableScheduling
@ConditionalOnExpression("'${STONEINTELLIGENCE_PLATFORM_API_URL:}' != '' and '${STONEINTELLIGENCE_AI_WORKER_TOKEN:}' != ''")
public class WorkerConfig {

    @Bean
    public PlatformApi platformApi(@Value("${STONEINTELLIGENCE_PLATFORM_API_URL}") String url,
                                   @Value("${STONEINTELLIGENCE_AI_WORKER_TOKEN}") String token) {
        return new PlatformHttpClient(url, token);
    }

    /** Ein gemeinsamer Satz Clients: Verarbeitung und Kapazitaetsmeldung sehen dieselben Schluessel-Pools. */
    @Bean
    public GatewayLlmFactory gatewayLlmFactory() {
        return new GatewayLlmFactory();
    }

    @Bean
    public CapacityReporter capacityReporter(PlatformApi platform, GatewayLlmFactory llms) {
        return new CapacityReporter(platform, llms, ServiceModels.from(System.getenv()));
    }

    @Bean
    public WorkerLoop workerLoop(PlatformApi platform, GatewayLlmFactory llms, CapacityReporter capacity) {
        var processor = new JobProcessor(platform, llms, ServiceModels.from(System.getenv()), LocalDate::now,
            JobProcessor.resumeRootFrom(System.getenv()));
        return new WorkerLoop(platform, processor, capacity);
    }
}
