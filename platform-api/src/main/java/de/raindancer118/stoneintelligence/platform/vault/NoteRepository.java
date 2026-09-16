package de.raindancer118.stoneintelligence.platform.vault;

import java.util.Optional;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;

/**
 * Port fuer den Note-Bestand. Mutationen adressieren grundsaetzlich die stabile {@link NoteId},
 * nie den Pfad (Plan.md Abschnitt 3, Fehlerklasse 5).
 */
public interface NoteRepository {

    Note create(VaultId vaultId, String path, NoteLevel level, String createdBy);

    Optional<Note> findById(NoteId id);

    /**
     * Keyset-paginierte Vault-Reconciliation (Fehlerklasse 2). {@code cursorToken} ist der
     * Wert aus {@link ReconciliationPage#nextCursor()} der vorherigen Seite, oder {@code null}
     * fuer die erste Seite eines neuen Durchlaufs.
     */
    ReconciliationPage list(VaultId vaultId, String cursorToken, int pageSize);

    /**
     * Idempotent ueber {@code operationId}: ein wiederholter Aufruf mit derselben
     * {@code operationId} liefert denselben {@link Tombstone} zurueck, ohne einen zweiten
     * anzulegen (Fehlerklasse 3).
     */
    Tombstone delete(VaultId vaultId, NoteId noteId, String operationId, String deletedBy);
}
