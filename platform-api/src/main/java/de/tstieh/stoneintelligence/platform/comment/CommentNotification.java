package de.tstieh.stoneintelligence.platform.comment;

import java.time.Instant;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Nur Verweise, kein Text: Inhalt und Titel werden beim Lesen aufgelöst und gegen das Leserecht geprüft. */
public record CommentNotification(UUID id, String recipient, VaultId vaultId, NoteId noteId, UUID threadId, UUID commentId,
                                  String actor, Instant createdAt, Instant readAt) {
}
