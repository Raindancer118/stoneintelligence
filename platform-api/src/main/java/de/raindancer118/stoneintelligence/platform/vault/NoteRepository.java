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

    /**
     * Vault-gescopt (Mandanten-Isolation, Plan.md Abschnitt 8.2): liefert nur eine Note, die
     * tatsaechlich zu {@code vaultId} gehoert - eine Note aus einem anderen Vault ist hier
     * ununterscheidbar von "existiert nicht".
     */
    Optional<Note> findById(VaultId vaultId, NoteId id);

    /**
     * Umbenennen/Verschieben aendert nur den Pfad - die {@link NoteId} bleibt stabil
     * (Fehlerklasse 5). Wirft {@link NoteNotFoundException}, wenn die Note nicht in
     * {@code vaultId} existiert (auch wenn sie in einem ANDEREN Vault existiert).
     */
    Note rename(VaultId vaultId, NoteId noteId, String newPath);

    /**
     * Keyset-paginierte Vault-Reconciliation (Fehlerklasse 2). {@code cursorToken} ist der
     * Wert aus {@link ReconciliationPage#nextCursor()} der vorherigen Seite, oder {@code null}
     * fuer die erste Seite eines neuen Durchlaufs.
     */
    ReconciliationPage list(VaultId vaultId, String cursorToken, int pageSize);

    /**
     * Idempotent ueber {@code operationId}: ein wiederholter Aufruf mit derselben
     * {@code operationId} liefert denselben {@link Tombstone} zurueck, ohne einen zweiten
     * anzulegen (Fehlerklasse 3). Wirft {@link NoteNotFoundException}, wenn unter einer NEUEN
     * {@code operationId} keine Note mit dieser {@link NoteId} in {@code vaultId} existiert
     * (auch wenn sie in einem ANDEREN Vault existiert) - sonst koennte ein beliebiger Client
     * Notes fremder Vaults loeschen, wenn er nur die NoteId kennt/erraet.
     */
    Tombstone delete(VaultId vaultId, NoteId noteId, String operationId, String deletedBy);
}
