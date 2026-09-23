package de.raindancer118.stoneintelligence.worker.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Alles, was der Worker von platform-api braucht (ADR 0008: {@code /internal/ai/**}). Der Worker
 * hat keinen eigenen Datenbankzugriff - Jobs, Dokumente und Notizen laufen nur hierueber.
 * Fachliche Ablehnungen kommen als {@link PlatformRefusedException}, Netzprobleme als
 * {@link java.io.UncheckedIOException}.
 */
public interface PlatformApi {

    Optional<ClaimedJob> claim();

    byte[] document(UUID jobId);

    void progress(UUID jobId, String message, Integer percent);

    void complete(UUID jobId);

    void fail(UUID jobId, String error, boolean retryable);

    List<ListedNote> notes(String vaultId, UUID changeSetId);

    String read(String vaultId, UUID changeSetId, String noteId);

    /** Legt eine Notiz an; liefert ihre Id. */
    String create(String vaultId, UUID changeSetId, String path, String text, int level);

    void update(String vaultId, UUID changeSetId, String noteId, String text);

    /** Legt das gelesene Original als Datei ab; liefert den Pfad, den der Server gewaehlt hat. */
    String storeFile(String vaultId, UUID changeSetId, String path, byte[] content, String contentType, int level);
}
