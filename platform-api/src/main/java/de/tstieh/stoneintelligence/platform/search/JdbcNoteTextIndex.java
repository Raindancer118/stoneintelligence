package de.tstieh.stoneintelligence.platform.search;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNoteTextIndex implements NoteTextIndex {

    private final JdbcClient jdbc;

    public JdbcNoteTextIndex(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public List<Queued> claim(int limit) {
        return jdbc.sql("""
                DELETE FROM platform.note_text_queue q
                USING platform.notes n
                WHERE q.note_id = n.id AND q.note_id IN (
                    SELECT note_id FROM platform.note_text_queue ORDER BY queued_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING q.note_id, n.vault_id
                """)
            .param("limit", limit)
            .query((rs, row) -> new Queued(NoteId.of(rs.getString("note_id")), VaultId.of(rs.getString("vault_id"))))
            .list();
    }

    @Override
    public void requeue(NoteId noteId) {
        jdbc.sql("""
                INSERT INTO platform.note_text_queue (note_id)
                SELECT id FROM platform.notes WHERE id = :id
                ON CONFLICT DO NOTHING
                """).param("id", noteId.value()).update();
    }

    @Override
    public void upsert(NoteId noteId, VaultId vaultId, long revision, String text) {
        jdbc.sql("""
                INSERT INTO platform.note_texts (note_id, vault_id, revision, body) VALUES (:id, :vault, :revision, :body)
                ON CONFLICT (note_id) DO UPDATE SET revision = EXCLUDED.revision, body = EXCLUDED.body, vault_id = EXCLUDED.vault_id
                WHERE platform.note_texts.revision <= EXCLUDED.revision
                """)
            .param("id", noteId.value()).param("vault", vaultId.value()).param("revision", revision).param("body", text)
            .update();
    }

    @Override
    public void remove(NoteId noteId) {
        jdbc.sql("DELETE FROM platform.note_texts WHERE note_id = :id").param("id", noteId.value()).update();
    }

    @Override
    public List<NoteId> matching(VaultId vaultId, String tsquery, int limit) {
        return jdbc.sql("""
                SELECT note_id FROM platform.note_texts
                WHERE vault_id = :vault AND search @@ to_tsquery('simple', :query)
                ORDER BY ts_rank(search, to_tsquery('simple', :query)) DESC, note_id
                LIMIT :limit
                """)
            .param("vault", vaultId.value()).param("query", tsquery).param("limit", limit)
            .query((rs, row) -> NoteId.of(rs.getString("note_id")))
            .list();
    }

    @Override
    public Map<NoteId, String> excerpts(VaultId vaultId, Collection<NoteId> noteIds, String tsquery) {
        var result = new HashMap<NoteId, String>();
        if (noteIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                SELECT note_id, ts_headline('simple', body, to_tsquery('simple', :query),
                    'StartSel=' || chr(2) || ', StopSel=' || chr(3) || ', MaxWords=24, MinWords=10, MaxFragments=1, FragmentDelimiter=" … "') AS excerpt
                FROM platform.note_texts
                WHERE vault_id = :vault AND note_id IN (:ids) AND search @@ to_tsquery('simple', :query)
                """)
            .param("vault", vaultId.value()).param("query", tsquery)
            .param("ids", noteIds.stream().map(NoteId::value).toList())
            .query((rs, row) -> Map.entry(NoteId.of(rs.getString("note_id")), rs.getString("excerpt")))
            .list()
            .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
