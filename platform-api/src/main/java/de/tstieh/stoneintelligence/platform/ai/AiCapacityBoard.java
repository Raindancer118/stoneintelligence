package de.tstieh.stoneintelligence.platform.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Wie viel Kontingent die KI-Dienste noch haben - so, wie der Worker es zuletzt gemeldet hat.
 * Nur der Worker kennt Anbieter und Schluessel (ADR 0008); er meldet regelmaessig, ohne Schluessel.
 * Im Speicher genuegt: nach einem Neustart ist die naechste Meldung Sekunden entfernt, und eine
 * alte Meldung waere ohnehin keine verlaessliche Auskunft mehr ({@link #STALE_AFTER}).
 */
public final class AiCapacityBoard {

    /** Ohne neuere Meldung laeuft der Worker vermutlich nicht - die Angabe ist dann nur ein Anhaltspunkt. */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);
    static final int MAX_PROVIDERS = 20;
    private static final int MAX_PROVIDER_NAME = 60;

    /** Restmenge eines Zeitfensters; {@code limit} 0 = unbekannt. */
    public record Quota(long remaining, long limit, Instant resetsAt) { }

    /** Restguthaben; {@code limit} {@code null} = kein Ausgabelimit. */
    public record Credits(double remaining, Double limit) { }

    /** Ein Anbieter hinter dem Dienst, wie ai-gateway ihn beschreibt (ohne Schluessel). */
    public record ProviderReport(String provider, int keys, int usableKeys, boolean exhausted, Instant availableAgainAt,
                                 Quota requests, Quota tokens, Credits credits, Instant observedAt) { }

    /**
     * @param reportedAt       letzte Meldung des Workers, {@code null} = noch keine
     * @param stale            die Meldung ist aelter als {@link #STALE_AFTER}
     * @param exhausted        kein Anbieter des Dienstes kann gerade antworten; {@code null} = unbekannt
     * @param availableAgainAt wenn erschoepft: fruehester Zeitpunkt, zu dem ein Anbieter wieder kann
     */
    public record ServiceCapacity(String service, Instant reportedAt, boolean stale, Boolean exhausted, Instant availableAgainAt,
                                  List<ProviderReport> providers) { }

    private record Report(Instant at, List<ProviderReport> providers) { }

    private final Supplier<AiServiceDirectory> services;
    private final Supplier<Instant> clock;
    private final Map<String, Report> reports = new ConcurrentHashMap<>();

    public AiCapacityBoard(Supplier<AiServiceDirectory> services, Supplier<Instant> clock) {
        this.services = services;
        this.clock = clock;
    }

    public void report(String serviceId, List<ProviderReport> providers) {
        requireService(serviceId);
        if (providers == null || providers.size() > MAX_PROVIDERS || providers.stream().anyMatch(Objects::isNull)) {
            throw new AiWriteRefusedException("Höchstens " + MAX_PROVIDERS + " Anbieter je Dienst");
        }
        for (var provider : providers) {
            if (provider.provider() == null || provider.provider().isBlank() || provider.provider().length() > MAX_PROVIDER_NAME
                || provider.keys() < 0 || provider.usableKeys() < 0 || provider.usableKeys() > provider.keys()) {
                throw new AiWriteRefusedException("Unplausible Kapazitätsmeldung");
            }
        }
        reports.put(serviceId, new Report(clock.get(), List.copyOf(providers)));
    }

    public ServiceCapacity of(String serviceId) {
        requireService(serviceId);
        var report = reports.get(serviceId);
        if (report == null) {
            return new ServiceCapacity(serviceId, null, false, null, null, List.of());
        }
        var stale = report.at().plus(STALE_AFTER).isBefore(clock.get());
        var exhausted = !report.providers().isEmpty() && report.providers().stream().allMatch(ProviderReport::exhausted);
        var back = exhausted ? report.providers().stream().map(ProviderReport::availableAgainAt).filter(Objects::nonNull)
            .min(Instant::compareTo).orElse(null) : null;
        return new ServiceCapacity(serviceId, report.at(), stale, report.providers().isEmpty() ? null : exhausted, back,
            report.providers());
    }

    private void requireService(String serviceId) {
        if (services.get().find(String.valueOf(serviceId)).isEmpty()) {
            throw new AiWriteRefusedException("KI-Dienst " + serviceId + " ist nicht eingerichtet");
        }
    }
}
