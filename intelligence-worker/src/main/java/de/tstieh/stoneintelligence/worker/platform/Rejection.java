package de.tstieh.stoneintelligence.worker.platform;

/** Die KI fand den Link zu {@code target} nicht sinnvoll - bei diesen Fassungen beider Notizen (Text-Hashes). */
public record Rejection(String target, String sourceHash, String targetHash) {
}
