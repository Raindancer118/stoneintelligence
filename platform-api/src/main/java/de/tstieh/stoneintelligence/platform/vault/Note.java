package de.tstieh.stoneintelligence.platform.vault;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;

public record Note(NoteId id, VaultId vaultId, String path, NoteLevel level, String createdBy, Instant createdAt, NoteKind kind) {

    public Note(NoteId id, VaultId vaultId, String path, NoteLevel level, String createdBy, Instant createdAt) {
        this(id, vaultId, path, level, createdBy, createdAt, NoteKind.NOTE);
    }

    public boolean isFile() {
        return kind == NoteKind.FILE;
    }
}
