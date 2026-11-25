package de.tstieh.stoneintelligence.platform.comment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCommentRepository implements CommentRepository {

    private static final String THREAD_COLUMNS =
        "t.id, t.vault_id, t.note_id, t.quote, t.quote_prefix, t.quote_suffix, t.created_by, t.created_at, t.resolved_by, t.resolved_at";

    private final JdbcClient jdbc;

    public JdbcCommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public CommentThread createThread(VaultId vaultId, NoteId noteId, Anchor anchor, String author, String body) {
        var id = jdbc.sql("""
                INSERT INTO platform.comment_threads (vault_id, note_id, quote, quote_prefix, quote_suffix, created_by)
                VALUES (:vault, :note, :quote, :prefix, :suffix, :author) RETURNING id
                """)
            .param("vault", vaultId.value()).param("note", noteId.value())
            .param("quote", anchor == null ? null : anchor.quote())
            .param("prefix", anchor == null ? null : anchor.prefix())
            .param("suffix", anchor == null ? null : anchor.suffix())
            .param("author", author)
            .query(UUID.class).single();
        insertComment(id, author, body);
        return findThread(vaultId, id).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<Comment> addReply(VaultId vaultId, UUID threadId, String author, String body) {
        if (findThread(vaultId, threadId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(insertComment(threadId, author, body));
    }

    private Comment insertComment(UUID threadId, String author, String body) {
        return jdbc.sql("""
                INSERT INTO platform.comments (thread_id, author, body) VALUES (:thread, :author, :body)
                RETURNING id, thread_id, author, body, created_at, edited_at
                """)
            .param("thread", threadId).param("author", author).param("body", body)
            .query((rs, row) -> comment(rs)).single();
    }

    @Override
    public List<CommentThread> threadsOf(VaultId vaultId, NoteId noteId) {
        var threads = jdbc.sql("SELECT " + THREAD_COLUMNS + " FROM platform.comment_threads t "
                + "WHERE t.vault_id = :vault AND t.note_id = :note ORDER BY t.created_at, t.id")
            .param("vault", vaultId.value()).param("note", noteId.value())
            .query((rs, row) -> thread(rs, List.of())).list();
        if (threads.isEmpty()) {
            return List.of();
        }
        var byThread = new LinkedHashMap<UUID, List<Comment>>();
        jdbc.sql("""
                SELECT c.id, c.thread_id, c.author, c.body, c.created_at, c.edited_at FROM platform.comments c
                JOIN platform.comment_threads t ON t.id = c.thread_id
                WHERE t.vault_id = :vault AND t.note_id = :note ORDER BY c.created_at, c.id
                """)
            .param("vault", vaultId.value()).param("note", noteId.value())
            .query((rs, row) -> comment(rs)).list()
            .forEach(comment -> byThread.computeIfAbsent(comment.threadId(), key -> new ArrayList<>()).add(comment));
        return threads.stream().map(t -> withComments(t, byThread.getOrDefault(t.id(), List.of()))).toList();
    }

    @Override
    public Optional<CommentThread> findThread(VaultId vaultId, UUID threadId) {
        return jdbc.sql("SELECT " + THREAD_COLUMNS + " FROM platform.comment_threads t WHERE t.id = :id AND t.vault_id = :vault")
            .param("id", threadId).param("vault", vaultId.value())
            .query((rs, row) -> thread(rs, List.of())).optional()
            .map(thread -> withComments(thread, comments(threadId)));
    }

    @Override
    public Optional<CommentThread> findThreadOfComment(VaultId vaultId, UUID commentId) {
        return jdbc.sql("SELECT c.thread_id FROM platform.comments c JOIN platform.comment_threads t ON t.id = c.thread_id "
                + "WHERE c.id = :id AND t.vault_id = :vault")
            .param("id", commentId).param("vault", vaultId.value())
            .query(UUID.class).optional()
            .flatMap(threadId -> findThread(vaultId, threadId));
    }

    @Override
    public Optional<Comment> editComment(VaultId vaultId, UUID commentId, String body) {
        return jdbc.sql("""
                UPDATE platform.comments c SET body = :body, edited_at = clock_timestamp()
                FROM platform.comment_threads t
                WHERE c.id = :id AND t.id = c.thread_id AND t.vault_id = :vault
                RETURNING c.id, c.thread_id, c.author, c.body, c.created_at, c.edited_at
                """)
            .param("id", commentId).param("vault", vaultId.value()).param("body", body)
            .query((rs, row) -> comment(rs)).optional();
    }

    @Override
    @Transactional
    public boolean deleteComment(VaultId vaultId, UUID commentId) {
        var thread = findThreadOfComment(vaultId, commentId);
        if (thread.isEmpty()) {
            return false;
        }
        if (thread.get().comments().size() == 1) {
            jdbc.sql("DELETE FROM platform.comment_threads WHERE id = :id").param("id", thread.get().id()).update();
        } else {
            jdbc.sql("DELETE FROM platform.comments WHERE id = :id").param("id", commentId).update();
        }
        return true;
    }

    @Override
    public Optional<CommentThread> setResolved(VaultId vaultId, UUID threadId, String resolvedBy) {
        var changed = jdbc.sql("""
                UPDATE platform.comment_threads SET resolved_by = :by,
                    resolved_at = CASE WHEN CAST(:by AS text) IS NULL THEN NULL ELSE clock_timestamp() END
                WHERE id = :id AND vault_id = :vault
                """)
            .param("by", resolvedBy).param("id", threadId).param("vault", vaultId.value()).update();
        return changed == 0 ? Optional.empty() : findThread(vaultId, threadId);
    }

    @Override
    public void addNotification(VaultId vaultId, NoteId noteId, UUID threadId, UUID commentId, String recipient, String actor) {
        jdbc.sql("""
                INSERT INTO platform.comment_notifications (recipient, vault_id, note_id, thread_id, comment_id, actor)
                VALUES (:recipient, :vault, :note, :thread, :comment, :actor)
                """)
            .param("recipient", recipient).param("vault", vaultId.value()).param("note", noteId.value())
            .param("thread", threadId).param("comment", commentId).param("actor", actor).update();
    }

    @Override
    public List<CommentNotification> notificationsFor(String recipient, int limit) {
        return jdbc.sql("""
                SELECT id, recipient, vault_id, note_id, thread_id, comment_id, actor, created_at, read_at
                FROM platform.comment_notifications WHERE recipient = :recipient ORDER BY created_at DESC, id LIMIT :limit
                """)
            .param("recipient", recipient).param("limit", limit)
            .query((rs, row) -> new CommentNotification(rs.getObject("id", UUID.class), rs.getString("recipient"),
                VaultId.of(rs.getObject("vault_id", UUID.class)), NoteId.of(rs.getObject("note_id", UUID.class)),
                rs.getObject("thread_id", UUID.class), rs.getObject("comment_id", UUID.class), rs.getString("actor"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()))
            .list();
    }

    @Override
    public int markRead(String recipient, Collection<UUID> ids) {
        var statement = jdbc.sql("UPDATE platform.comment_notifications SET read_at = clock_timestamp() "
                + "WHERE recipient = :recipient AND read_at IS NULL" + (ids.isEmpty() ? "" : " AND id IN (:ids)"))
            .param("recipient", recipient);
        return (ids.isEmpty() ? statement : statement.param("ids", ids)).update();
    }

    private List<Comment> comments(UUID threadId) {
        return jdbc.sql("SELECT id, thread_id, author, body, created_at, edited_at FROM platform.comments "
                + "WHERE thread_id = :id ORDER BY created_at, id")
            .param("id", threadId).query((rs, row) -> comment(rs)).list();
    }

    private static Comment comment(ResultSet rs) throws SQLException {
        var edited = rs.getTimestamp("edited_at");
        return new Comment(rs.getObject("id", UUID.class), rs.getObject("thread_id", UUID.class), rs.getString("author"),
            rs.getString("body"), rs.getTimestamp("created_at").toInstant(), edited == null ? null : edited.toInstant());
    }

    private static CommentThread thread(ResultSet rs, List<Comment> comments) throws SQLException {
        var quote = rs.getString("quote");
        var resolved = rs.getTimestamp("resolved_at");
        return new CommentThread(rs.getObject("id", UUID.class), VaultId.of(rs.getObject("vault_id", UUID.class)),
            NoteId.of(rs.getObject("note_id", UUID.class)),
            quote == null ? null : new Anchor(quote, rs.getString("quote_prefix"), rs.getString("quote_suffix")),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getString("resolved_by"),
            resolved == null ? null : resolved.toInstant(), comments);
    }

    private static CommentThread withComments(CommentThread thread, List<Comment> comments) {
        return new CommentThread(thread.id(), thread.vaultId(), thread.noteId(), thread.anchor(), thread.createdBy(),
            thread.createdAt(), thread.resolvedBy(), thread.resolvedAt(), List.copyOf(comments));
    }
}
