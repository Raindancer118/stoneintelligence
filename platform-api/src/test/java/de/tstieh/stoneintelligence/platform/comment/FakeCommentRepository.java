package de.tstieh.stoneintelligence.platform.comment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** In-Memory-Fake, spiegelt die Semantik von {@code JdbcCommentRepository} (Zeiten auf Mikrosekunden wie Postgres). */
public final class FakeCommentRepository implements CommentRepository {

    private final Map<UUID, CommentThread> threads = new LinkedHashMap<>();
    private final List<CommentNotification> notifications = new ArrayList<>();

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public synchronized CommentThread createThread(VaultId vaultId, NoteId noteId, Anchor anchor, String author, String body) {
        var id = UUID.randomUUID();
        var created = now();
        var thread = new CommentThread(id, vaultId, noteId, anchor, author, created, null, null,
            List.of(new Comment(UUID.randomUUID(), id, author, body, created, null)));
        threads.put(id, thread);
        return thread;
    }

    @Override
    public synchronized Optional<Comment> addReply(VaultId vaultId, UUID threadId, String author, String body) {
        return findThread(vaultId, threadId).map(thread -> {
            var reply = new Comment(UUID.randomUUID(), threadId, author, body, now(), null);
            var all = new ArrayList<>(thread.comments());
            all.add(reply);
            threads.put(threadId, withComments(thread, all));
            return reply;
        });
    }

    @Override
    public synchronized List<CommentThread> threadsOf(VaultId vaultId, NoteId noteId) {
        return threads.values().stream()
            .filter(thread -> thread.vaultId().equals(vaultId) && thread.noteId().equals(noteId))
            .sorted(Comparator.comparing(CommentThread::createdAt)).toList();
    }

    @Override
    public synchronized Optional<CommentThread> findThread(VaultId vaultId, UUID threadId) {
        return Optional.ofNullable(threads.get(threadId)).filter(thread -> thread.vaultId().equals(vaultId));
    }

    @Override
    public synchronized Optional<CommentThread> findThreadOfComment(VaultId vaultId, UUID commentId) {
        return threads.values().stream()
            .filter(thread -> thread.vaultId().equals(vaultId) && thread.comments().stream().anyMatch(c -> c.id().equals(commentId)))
            .findFirst();
    }

    @Override
    public synchronized Optional<Comment> editComment(VaultId vaultId, UUID commentId, String body) {
        return findThreadOfComment(vaultId, commentId).map(thread -> {
            Comment[] edited = new Comment[1];
            var all = thread.comments().stream().map(comment -> {
                if (!comment.id().equals(commentId)) {
                    return comment;
                }
                edited[0] = new Comment(comment.id(), comment.threadId(), comment.author(), body, comment.createdAt(), now());
                return edited[0];
            }).toList();
            threads.put(thread.id(), withComments(thread, all));
            return edited[0];
        });
    }

    @Override
    public synchronized boolean deleteComment(VaultId vaultId, UUID commentId) {
        var found = findThreadOfComment(vaultId, commentId);
        if (found.isEmpty()) {
            return false;
        }
        var thread = found.get();
        var remaining = thread.comments().stream().filter(comment -> !comment.id().equals(commentId)).toList();
        if (remaining.isEmpty()) {
            threads.remove(thread.id());
            notifications.removeIf(item -> item.threadId().equals(thread.id()));
        } else {
            threads.put(thread.id(), withComments(thread, remaining));
            notifications.removeIf(item -> item.commentId().equals(commentId));
        }
        return true;
    }

    @Override
    public synchronized Optional<CommentThread> setResolved(VaultId vaultId, UUID threadId, String resolvedBy) {
        return findThread(vaultId, threadId).map(thread -> {
            var updated = new CommentThread(thread.id(), thread.vaultId(), thread.noteId(), thread.anchor(), thread.createdBy(),
                thread.createdAt(), resolvedBy, resolvedBy == null ? null : now(), thread.comments());
            threads.put(thread.id(), updated);
            return updated;
        });
    }

    @Override
    public synchronized void addNotification(VaultId vaultId, NoteId noteId, UUID threadId, UUID commentId, String recipient, String actor) {
        notifications.add(new CommentNotification(UUID.randomUUID(), recipient, vaultId, noteId, threadId, commentId, actor, now(), null));
    }

    @Override
    public synchronized List<CommentNotification> notificationsFor(String recipient, int limit) {
        return notifications.reversed().stream().filter(item -> item.recipient().equals(recipient)).limit(limit).toList();
    }

    @Override
    public synchronized int markRead(String recipient, Collection<UUID> ids) {
        var changed = 0;
        for (var i = 0; i < notifications.size(); i++) {
            var item = notifications.get(i);
            if (item.recipient().equals(recipient) && item.readAt() == null && (ids.isEmpty() || ids.contains(item.id()))) {
                notifications.set(i, new CommentNotification(item.id(), item.recipient(), item.vaultId(), item.noteId(),
                    item.threadId(), item.commentId(), item.actor(), item.createdAt(), now()));
                changed++;
            }
        }
        return changed;
    }

    private static CommentThread withComments(CommentThread thread, List<Comment> comments) {
        return new CommentThread(thread.id(), thread.vaultId(), thread.noteId(), thread.anchor(), thread.createdBy(),
            thread.createdAt(), thread.resolvedBy(), thread.resolvedAt(), List.copyOf(comments));
    }
}
