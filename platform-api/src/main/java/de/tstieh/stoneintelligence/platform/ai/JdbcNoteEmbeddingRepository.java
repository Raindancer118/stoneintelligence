package de.tstieh.stoneintelligence.platform.ai;

import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** pgvector (V16): Kosinus-Abstand {@code <=>}, HNSW-Index; Aehnlichkeit = 1 - Abstand. */
@Repository
public class JdbcNoteEmbeddingRepository implements NoteEmbeddingRepository {

    /** Je Abschnitt so viele Nachbarn, bevor je Notiz der beste zaehlt. */
    private static final int NEIGHBOURS_PER_CHUNK = 20;

    private final JdbcClient jdbcClient;

    public JdbcNoteEmbeddingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<State> states(VaultId vaultId) {
        return jdbcClient.sql("""
                SELECT DISTINCT note_id, model, content_hash FROM platform.note_embeddings WHERE vault_id = :vaultId ORDER BY note_id
                """)
            .param("vaultId", vaultId.value())
            .query((rs, row) -> new State(NoteId.of((UUID) rs.getObject("note_id")), rs.getString("model"), rs.getString("content_hash")))
            .list();
    }

    @Override
    @Transactional
    public void replace(VaultId vaultId, NoteId noteId, String model, String contentHash, List<Chunk> chunks) {
        jdbcClient.sql("DELETE FROM platform.note_embeddings WHERE note_id = :noteId AND vault_id = :vaultId")
            .param("noteId", noteId.value())
            .param("vaultId", vaultId.value())
            .update();
        for (var chunk : chunks) {
            jdbcClient.sql("""
                    INSERT INTO platform.note_embeddings (note_id, chunk, vault_id, model, content_hash, heading, embedding)
                    VALUES (:noteId, :chunk, :vaultId, :model, :hash, :heading, CAST(:vector AS vector))
                    """)
                .param("noteId", noteId.value())
                .param("chunk", chunk.index())
                .param("vaultId", vaultId.value())
                .param("model", model)
                .param("hash", contentHash)
                .param("heading", chunk.heading())
                .param("vector", literal(chunk.vector()))
                .update();
        }
    }

    @Override
    public List<Similar> similarTo(VaultId vaultId, NoteId noteId, int limit) {
        return jdbcClient.sql("""
                SELECT DISTINCT ON (o.note_id) o.note_id, o.chunk, o.heading, o.similarity
                FROM platform.note_embeddings c
                CROSS JOIN LATERAL (
                    SELECT n.note_id, n.chunk, n.heading, 1 - (n.embedding <=> c.embedding) AS similarity
                    FROM platform.note_embeddings n
                    WHERE n.vault_id = c.vault_id AND n.note_id <> c.note_id
                    ORDER BY n.embedding <=> c.embedding
                    LIMIT :neighbours
                ) o
                WHERE c.note_id = :noteId AND c.vault_id = :vaultId
                ORDER BY o.note_id, o.similarity DESC
                """)
            .param("noteId", noteId.value())
            .param("vaultId", vaultId.value())
            .param("neighbours", NEIGHBOURS_PER_CHUNK)
            .query((rs, row) -> new Similar(NoteId.of((UUID) rs.getObject("note_id")), rs.getInt("chunk"), rs.getString("heading"),
                rs.getDouble("similarity")))
            .list().stream()
            .sorted(java.util.Comparator.comparingDouble(Similar::similarity).reversed())
            .limit(limit)
            .toList();
    }

    /** pgvector-Textform, z. B. {@code [0.1,0.2]}. */
    private static String literal(float[] vector) {
        var text = new StringBuilder("[");
        for (var i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }
}
