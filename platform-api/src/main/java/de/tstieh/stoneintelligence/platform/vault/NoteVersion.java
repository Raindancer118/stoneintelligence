package de.tstieh.stoneintelligence.platform.vault;

import java.time.Instant;

/**
 * Eine Version: aufeinanderfolgende Updates einer Person in einer Sitzung. {@code revision} ist
 * der Stand nach dem letzten Update, {@code actor} fehlt bei Updates von vor dem Versionsverlauf.
 */
public record NoteVersion(long revision, long firstRevision, String actor, Instant startedAt, Instant endedAt, int updates) {
}
