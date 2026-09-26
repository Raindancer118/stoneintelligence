package de.tstieh.stoneintelligence.platform.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Persistierte Job-Queue fuer die KI-Verarbeitung - Backpressure und Wiederanlauf ueber die DB, nie im Speicher. */
public interface AiJobRepository {

    AiJob create(NewAiJob job, Instant at);

    /** Vault-gescopt: ein Job eines anderen Vaults ist nicht auffindbar. */
    Optional<AiJob> find(VaultId vaultId, UUID id);

    /** Nur fuer den Worker, der den Vault erst aus dem Job erfaehrt. */
    Optional<AiJob> findById(UUID id);

    List<AiJob> list(VaultId vaultId, int limit);

    /** Wartende und laufende Jobs dieses Vaults. */
    int countOpen(VaultId vaultId);

    /** Ob im Vault gerade ein Job dieser Art wartet oder laeuft. */
    boolean hasOpen(VaultId vaultId, AiJob.Kind kind);

    /**
     * Vergibt den aeltesten verfuegbaren Job genau einmal: wartend und faellig, oder laufend mit
     * abgelaufener Lease (abgestuerzter Worker). Jobs, deren letzter Versuch abgelaufen ist, gelten
     * als fehlgeschlagen.
     */
    Optional<AiJob> claim(Instant now, Duration lease);

    /** Das hochgeladene Dokument, solange der Job nicht beendet ist. */
    Optional<byte[]> content(UUID id);

    void attachChangeSet(UUID id, UUID changeSetId);

    /** Fortschritt melden und Lease verlaengern - nur fuer laufende Jobs. */
    boolean progress(UUID id, String message, Integer percent, Instant leaseUntil);

    /** Laufenden Job beenden ({@code SUCCEEDED}/{@code FAILED}); das Dokument wird verworfen. */
    boolean finish(UUID id, AiJob.Status status, String error, Instant at);

    /** Laufenden Job spaeter erneut versuchen; das Dokument bleibt. */
    boolean retryLater(UUID id, String error, Instant availableAt);

    /**
     * Laufenden Job zurueckstellen, bis der KI-Dienst wieder Kontingent hat - ohne dass der Lauf als
     * Versuch zaehlt; das Dokument bleibt.
     */
    boolean waitForCapacity(UUID id, String error, Instant availableAt);

    /** Wartenden oder laufenden Job abbrechen; das Dokument wird verworfen. */
    boolean cancel(VaultId vaultId, UUID id, Instant at);

    int purgeCreatedBefore(Instant cutoff);
}
