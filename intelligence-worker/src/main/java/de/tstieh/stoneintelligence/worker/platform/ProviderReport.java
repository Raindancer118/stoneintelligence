package de.tstieh.stoneintelligence.worker.platform;

/**
 * Was ein Anbieter hinter einem KI-Dienst noch kann, wie der Worker es an platform-api meldet -
 * ohne Schluessel. Zeitpunkte als ISO-8601-Text; {@code null} = unbekannt.
 */
public record ProviderReport(String provider, int keys, int usableKeys, boolean exhausted, String availableAgainAt,
                             Quota requests, Quota tokens, Credits credits, String observedAt) {

    public record Quota(long remaining, long limit, String resetsAt) { }

    public record Credits(double remaining, Double limit) { }
}
