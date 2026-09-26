package de.tstieh.stoneintelligence.worker.platform;

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

    /** Lauf abgebrochen, weil kein Kontingent frei war; startet ab {@code availableAt} ({@code null} = unbekannt) neu. */
    void waitForCapacity(UUID jobId, String error, java.time.Instant availableAt);

    /** Ids der eingerichteten KI-Dienste. */
    List<String> services();

    /** Meldet, wie viel Kontingent die Anbieter hinter einem Dienst noch haben. */
    void reportCapacity(String serviceId, List<ProviderReport> providers);

    List<ListedNote> notes(String vaultId, UUID changeSetId);

    String read(String vaultId, UUID changeSetId, String noteId);

    /** Legt eine Notiz an; liefert ihre Id. */
    String create(String vaultId, UUID changeSetId, String path, String text, int level);

    void update(String vaultId, UUID changeSetId, String noteId, String text);

    /** Links in einer Notiz setzen (ADR 0012) - der Server fuegt nur Markup ein; liefert, wie viele gesetzt wurden. */
    int link(String vaultId, UUID changeSetId, String noteId, List<ProposedLink> links);

    /** Legt das gelesene Original als Datei ab; liefert den Pfad, den der Server gewaehlt hat. */
    String storeFile(String vaultId, UUID changeSetId, String path, byte[] content, String contentType, int level);
}
