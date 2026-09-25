package de.raindancer118.stoneintelligence.worker.ingest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import de.raindancer118.stoneintelligence.worker.platform.PlatformApi;
import de.raindancer118.stoneintelligence.worker.platform.ProviderReport;
import io.github.raindancer118.aigateway.ProviderCapacity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Meldet platform-api regelmaessig, wie viel Kontingent die Anbieter jedes KI-Dienstes noch haben
 * - das Dashboard zeigt es an. Nur der Worker kennt Anbieter und Schluessel (ADR 0008); gemeldet
 * wird ohne Schluessel. Anbieter mit eigener Kontingent-Abfrage (OpenRouter) werden dabei gefragt,
 * die uebrigen melden ihren Stand ohnehin mit jeder Antwort.
 */
public class CapacityReporter {

    private static final Logger LOG = LoggerFactory.getLogger(CapacityReporter.class);

    private final PlatformApi platform;
    private final LlmFactory llms;
    private final ServiceModels models;

    CapacityReporter(PlatformApi platform, LlmFactory llms, ServiceModels models) {
        this.platform = platform;
        this.llms = llms;
        this.models = models;
    }

    @Scheduled(initialDelayString = "${STONEINTELLIGENCE_CAPACITY_REPORT_MS:60000}",
        fixedDelayString = "${STONEINTELLIGENCE_CAPACITY_REPORT_MS:60000}")
    public void report() {
        List<String> services;
        try {
            services = platform.services();
        } catch (RuntimeException unreachable) {
            LOG.debug("Kapazitaet nicht meldbar: {}", unreachable.getMessage());
            return;
        }
        services.forEach(this::report);
    }

    /** Nach einem Lauf sofort, damit das Dashboard nicht bis zum naechsten Takt Veraltetes zeigt. */
    void report(String serviceId) {
        List<ProviderReport> providers;
        try {
            providers = llms.refreshCapacity(models.forService(serviceId)).values().stream().map(CapacityReporter::toReport).toList();
        } catch (RuntimeException noClient) {
            // Kein Anbieter mit Schluessel oder fehlerhafte Konfiguration: "unbekannt" statt einer alten Angabe.
            LOG.warn("Kapazitaet von {} nicht ermittelbar: {}", serviceId, noClient.getMessage());
            providers = List.of();
        }
        try {
            platform.reportCapacity(serviceId, providers);
        } catch (RuntimeException unreachable) {
            LOG.debug("Kapazitaet von {} nicht meldbar: {}", serviceId, unreachable.getMessage());
        }
    }

    static ProviderReport toReport(ProviderCapacity capacity) {
        return new ProviderReport(capacity.provider(), capacity.keys(), capacity.usableKeys(), capacity.exhausted(),
            iso(capacity.availableAgainAt()), quota(capacity.requests()), quota(capacity.tokens()),
            capacity.credits() == null ? null : new ProviderReport.Credits(capacity.credits().remaining(), capacity.credits().limit()),
            iso(capacity.observedAt()));
    }

    /** Die Anbieter, die gerade nichts mehr koennen - fuer eine verstaendliche Meldung. */
    static String exhaustedNames(Map<String, ProviderCapacity> capacity) {
        return String.join(", ", capacity.values().stream().filter(ProviderCapacity::exhausted).map(ProviderCapacity::provider).toList());
    }

    private static ProviderReport.Quota quota(ProviderCapacity.Quota quota) {
        return quota == null ? null : new ProviderReport.Quota(quota.remainingAt(Instant.now()), quota.limit(), iso(quota.resetsAt()));
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
