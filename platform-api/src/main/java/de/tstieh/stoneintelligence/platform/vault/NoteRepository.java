package de.tstieh.stoneintelligence.platform.vault;

import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;

/**
 * Port fuer den Note-Bestand. Mutationen adressieren grundsaetzlich die stabile {@link NoteId},
 * nie den Pfad (Plan.md Abschnitt 3, Fehlerklasse 5).
 */
public interface NoteRepository {

    default Note create(VaultId vaultId, String path, NoteLevel level, String createdBy) {
        return create(vaultId, path, level, createdBy, NoteKind.NOTE);
    }

    Note create(VaultId vaultId, String path, NoteLevel level, String createdBy, NoteKind kind);

    /**
     * Vault-gescopt (Mandanten-Isolation, Plan.md Abschnitt 8.2): liefert nur eine Note, die
     * tatsaechlich zu {@code vaultId} gehoert - eine Note aus einem anderen Vault ist hier
     * ununterscheidbar von "existiert nicht".
     */
    Optional<Note> findById(VaultId vaultId, NoteId id);

    /** Liegt irgendeine Notiz oder Datei unterhalb dieses Ordners? */
    boolean hasEntriesUnder(VaultId vaultId, String folder);

    /** Alle Notes unter genau diesem Pfad (der Pfad ist nicht eindeutig erzwungen). Vault-gescopt. */
    java.util.List<Note> findByPath(VaultId vaultId, String path);

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
    /** Nur Notizen - so sehen aeltere Clients, die Dateien nicht kennen, auch keine (ADR 0009 Punkt 5). */
    default ReconciliationPage list(VaultId vaultId, String cursorToken, int pageSize) {
        return list(vaultId, cursorToken, pageSize, java.util.Set.of(NoteKind.NOTE));
    }

    /** Wie {@link #list(VaultId, String, int)}, aber nur Eintraege der angegebenen Arten. */
    ReconciliationPage list(VaultId vaultId, String cursorToken, int pageSize, java.util.Set<NoteKind> kinds);

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
