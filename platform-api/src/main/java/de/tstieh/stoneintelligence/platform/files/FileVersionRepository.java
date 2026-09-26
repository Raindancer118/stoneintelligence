package de.tstieh.stoneintelligence.platform.files;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public interface FileVersionRepository {

    /**
     * Neue Fassung auf Basis von {@code expectedRevision} (0 = noch keine). Atomar: passt die
     * Basis nicht mehr, {@link FileRevisionConflictException} mit der aktuellen Revision.
     */
    FileVersion append(NoteId noteId, long expectedRevision, StoredBlob blob, String contentType, String createdBy, Instant at);

    Optional<FileVersion> current(NoteId noteId);

    /** Aktuelle Fassung vieler Dateien in einer Abfrage (Listen-Seite); ohne Fassung fehlt der Eintrag. */
    Map<NoteId, FileVersion> current(Collection<NoteId> noteIds);

    /** Belegter Platz eines Vaults ueber alle aufbewahrten Fassungen, in Bytes. */
    long usage(VaultId vaultId);

    /** Loescht ersetzte Fassungen, die vor {@code olderThan} entstanden sind; die aktuelle bleibt immer. */
    int purgeReplaced(Instant olderThan);

    /** Alle Hashes, auf die noch eine Fassung verweist - der Rest darf aus dem Dateispeicher. */
    Set<String> referencedHashes();
}
