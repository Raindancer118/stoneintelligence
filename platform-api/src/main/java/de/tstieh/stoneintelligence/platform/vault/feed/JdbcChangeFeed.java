package de.tstieh.stoneintelligence.platform.vault.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Feed ueber die {@code change_tx}-Spalten aus V20. Gelesen wird nur unter
 * {@code pg_snapshot_xmin(pg_current_snapshot())}: alle Transaktionen darunter sind beendet, es kann
 * also keine mit kleinerer Nummer spaeter noch auftauchen. Eine lang laufende Transaktion (etwa ein
 * Backup) haelt diese Grenze fest - der Feed liefert dann verspaetet, nie lueckenhaft. Live-Sync
 * ueber WebSocket ist davon nicht betroffen.
 */
@Repository
public class JdbcChangeFeed implements ChangeFeed {

    private static final String CHANGED = """
        WITH changed AS (
            SELECT id AS note_id, change_tx AS tx FROM platform.notes
             WHERE vault_id = :vaultId AND change_tx >= :since::xid8 AND change_tx < :upto::xid8
            UNION ALL
            SELECT s.note_id, s.change_tx FROM platform.note_snapshots s
              JOIN platform.notes n ON n.id = s.note_id AND n.vault_id = :vaultId
             WHERE s.change_tx >= :since::xid8 AND s.change_tx < :upto::xid8
            UNION ALL
            SELECT f.note_id, f.change_tx FROM platform.file_versions f
              JOIN platform.notes n ON n.id = f.note_id AND n.vault_id = :vaultId
             WHERE f.change_tx >= :since::xid8 AND f.change_tx < :upto::xid8
            UNION ALL
            SELECT note_id, change_tx FROM platform.note_tombstones
             WHERE vault_id = :vaultId AND change_tx >= :since::xid8 AND change_tx < :upto::xid8
        ), latest AS (
            SELECT note_id, max(tx) AS tx FROM changed GROUP BY note_id
        )
        SELECT note_id, tx::text AS tx FROM latest
         WHERE (tx, note_id) > (:lastTx::xid8, :lastId)
         ORDER BY tx, note_id
         LIMIT :limit
        """;

    private final JdbcClient jdbcClient;

    public JdbcChangeFeed(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public long now() {
        return jdbcClient.sql("SELECT pg_snapshot_xmin(pg_current_snapshot())::text").query(String.class).optional()
            .map(Long::parseLong).orElseThrow();
    }

    @Override
    public Batch read(VaultId vaultId, FeedCursor cursor, int limit) {
        var continuation = cursor.continuation() != null ? cursor.continuation()
            : new FeedCursor.Continuation(now(), 0, new UUID(0, 0));
        var since = cursor.since();
        var upto = continuation.upto();
        var rows = jdbcClient.sql(CHANGED)
            .param("vaultId", vaultId.value())
            .param("since", Long.toString(since))
            .param("upto", Long.toString(upto))
            .param("lastTx", Long.toString(continuation.lastTx()))
            .param("lastId", continuation.lastId())
            .param("limit", limit + 1)
            .query((rs, rowNum) -> new Row(rs.getObject("note_id", UUID.class), Long.parseLong(rs.getString("tx"))))
            .list();
        var more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        var changed = new ArrayList<NoteId>(page.stream().map(row -> NoteId.of(row.noteId())).toList());
        if (more) {
            var last = page.getLast();
            return new Batch(changed, true, new FeedCursor(since, new FeedCursor.Continuation(upto, last.tx(), last.noteId())),
                List.of(), List.of(), List.of());
        }
        return new Batch(changed, false, FeedCursor.at(upto), foldersChanged(vaultId, since, upto),
            foldersRemoved(vaultId, since, upto), accessEvents(vaultId, since, upto));
    }

    private List<String> foldersChanged(VaultId vaultId, long since, long upto) {
        return jdbcClient.sql("""
                SELECT path FROM platform.folders
                 WHERE vault_id = :vaultId AND change_tx >= :since::xid8 AND change_tx < :upto::xid8
                 ORDER BY path
                """)
            .param("vaultId", vaultId.value()).param("since", Long.toString(since)).param("upto", Long.toString(upto))
            .query(String.class).list();
    }

    /** Verschwundene Ordner, die nicht (etwa durch Zurueckverschieben) wieder existieren. */
    private List<String> foldersRemoved(VaultId vaultId, long since, long upto) {
        return jdbcClient.sql("""
                SELECT DISTINCT t.path FROM platform.folder_tombstones t
                 WHERE t.vault_id = :vaultId AND t.change_tx >= :since::xid8 AND t.change_tx < :upto::xid8
                   AND NOT EXISTS (SELECT 1 FROM platform.folders f WHERE f.vault_id = t.vault_id AND f.path = t.path)
                 ORDER BY t.path
                """)
            .param("vaultId", vaultId.value()).param("since", Long.toString(since)).param("upto", Long.toString(upto))
            .query(String.class).list();
    }

    private List<AccessEvent> accessEvents(VaultId vaultId, long since, long upto) {
        return jdbcClient.sql("""
                SELECT DISTINCT scope_type, scope_subject, folder_path, note_id FROM platform.access_events
                 WHERE vault_id = :vaultId AND change_tx >= :since::xid8 AND change_tx < :upto::xid8
                """)
            .param("vaultId", vaultId.value()).param("since", Long.toString(since)).param("upto", Long.toString(upto))
            .query((rs, rowNum) -> {
                var noteId = rs.getObject("note_id", UUID.class);
                return new AccessEvent(rs.getString("scope_type"), rs.getString("scope_subject"), rs.getString("folder_path"),
                    noteId == null ? null : NoteId.of(noteId));
            })
            .list();
    }

    private record Row(UUID noteId, long tx) {
    }
}
