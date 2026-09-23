package de.raindancer118.stoneintelligence.platform.files;

import java.io.InputStream;
import java.time.Instant;
import java.util.Set;

/** Inhaltsadressierter Speicher fuer Datei-Bytes (ADR 0009). */
public interface BlobStore {

    /** Liest {@code content} vollstaendig; mehr als {@code maxBytes} → {@link BlobTooLargeException}, nichts bleibt liegen. */
    StoredBlob put(InputStream content, long maxBytes);

    /** @throws BlobNotFoundException wenn es den Inhalt nicht gibt */
    InputStream open(String sha256);

    boolean exists(String sha256);

    /** Loescht Inhalte, die nicht in {@code referenced} stehen und vor {@code olderThan} geschrieben wurden. */
    int deleteUnreferenced(Set<String> referenced, Instant olderThan);

    boolean available();
}
