package de.tstieh.stoneintelligence.platform.comment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Kommentare je Notiz und die Hinweise auf Erwähnungen. Alles ist auf den Vault begrenzt: eine Id aus
 * einem anderen Vault ist von "gibt es nicht" nicht zu unterscheiden. Rechte prüft der Aufrufer.
 */
public interface CommentRepository {

    /** Neuer Thread samt erstem Kommentar. */
    CommentThread createThread(VaultId vaultId, NoteId noteId, Anchor anchor, String author, String body);

    /** Antwort am Ende des Threads; leer, wenn es den Thread in diesem Vault nicht gibt. */
    Optional<Comment> addReply(VaultId vaultId, UUID threadId, String author, String body);

    /** Alle Threads der Notiz, älteste zuerst, mit Kommentaren, älteste zuerst. */
    List<CommentThread> threadsOf(VaultId vaultId, NoteId noteId);

    Optional<CommentThread> findThread(VaultId vaultId, UUID threadId);

    Optional<CommentThread> findThreadOfComment(VaultId vaultId, UUID commentId);

    /** Setzt Text und Bearbeitungszeit; leer, wenn es den Kommentar nicht gibt. */
    Optional<Comment> editComment(VaultId vaultId, UUID commentId, String body);

    /** Entfernt den Kommentar samt seiner Hinweise; war es der letzte im Thread, verschwindet auch der Thread. */
    boolean deleteComment(VaultId vaultId, UUID commentId);

    /** {@code resolvedBy == null} öffnet den Thread wieder. Leer, wenn es ihn nicht gibt. */
    Optional<CommentThread> setResolved(VaultId vaultId, UUID threadId, String resolvedBy);

    void addNotification(VaultId vaultId, NoteId noteId, UUID threadId, UUID commentId, String recipient, String actor);

    /** Neueste zuerst, über alle Vaults der Person. */
    List<CommentNotification> notificationsFor(String recipient, int limit);

    /** Leere {@code ids} = alle der Person. Liefert die Zahl der neu als gelesen markierten. */
    int markRead(String recipient, Collection<UUID> ids);
}
