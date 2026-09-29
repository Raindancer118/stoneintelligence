package de.tstieh.stoneintelligence.platform.sync.relay;

import java.time.Instant;

/** Ein gespeichertes Update ohne Inhalt - fuer den Versionsverlauf. {@code actor} fehlt bei Updates von vor V18. */
public record UpdateInfo(long serverSequence, String actor, Instant createdAt, boolean ciphertext) {
}
