package de.tstieh.stoneintelligence.platform.comment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** {@code anchor == null}: Kommentar zur ganzen Notiz; {@code resolvedAt == null}: offen. */
public record CommentThread(UUID id, VaultId vaultId, NoteId noteId, Anchor anchor, String createdBy, Instant createdAt,
                            String resolvedBy, Instant resolvedAt, List<Comment> comments) {

    public boolean resolved() {
        return resolvedAt != null;
    }
}
