package de.raindancer118.stoneintelligence.platform.vault;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNoteRepository implements NoteRepository {

    private static final RowMapper<Note> NOTE_MAPPER = (rs, rowNum) -> new Note(
        NoteId.of(rs.getString("id")),
        VaultId.of(rs.getString("vault_id")),
        rs.getString("path"),
        NoteLevel.of(rs.getInt("note_level")),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant()
    );

    private record NoteWithSequence(Note note, long sequence) {
    }

    private static final RowMapper<NoteWithSequence> NOTE_WITH_SEQUENCE_MAPPER = (rs, rowNum) -> new NoteWithSequence(
        NOTE_MAPPER.mapRow(rs, rowNum), rs.getLong("sequence")
    );

    private static final RowMapper<Tombstone> TOMBSTONE_MAPPER = (rs, rowNum) -> new Tombstone(
        UUID.fromString(rs.getString("id")),
        VaultId.of(rs.getString("vault_id")),
        NoteId.of(rs.getString("note_id")),
        rs.getString("operation_id"),
        rs.getLong("server_sequence"),
        rs.getString("deleted_by"),
        rs.getTimestamp("deleted_at").toInstant()
    );

    private final JdbcClient jdbcClient;

    public JdbcNoteRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Note create(VaultId vaultId, String path, NoteLevel level, String createdBy) {
        var id = NoteId.newId();
        jdbcClient.sql("""
                INSERT INTO platform.notes (id, vault_id, path, note_level, created_by)
                VALUES (:id, :vaultId, :path, :level, :createdBy)
                """)
            .param("id", id.value())
            .param("vaultId", vaultId.value())
            .param("path", path)
            .param("level", level.value())
            .param("createdBy", createdBy)
            .update();
        return findById(vaultId, id).orElseThrow(() -> new IllegalStateException("just-inserted note not found: " + id));
    }

    @Override
    public Optional<Note> findById(VaultId vaultId, NoteId id) {
        return jdbcClient.sql("SELECT * FROM platform.notes WHERE id = :id AND vault_id = :vaultId")
            .param("id", id.value())
            .param("vaultId", vaultId.value())
            .query(NOTE_MAPPER)
            .optional();
    }

    @Override
    public List<Note> findByPath(VaultId vaultId, String path) {
        return jdbcClient.sql("SELECT * FROM platform.notes WHERE vault_id = :vaultId AND path = :path ORDER BY sequence")
            .param("vaultId", vaultId.value())
            .param("path", path)
            .query(NOTE_MAPPER)
            .list();
    }

    @Override
    @Transactional
    public Note rename(VaultId vaultId, NoteId noteId, String newPath) {
        findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId));
        jdbcClient.sql("UPDATE platform.notes SET path = :path WHERE id = :id AND vault_id = :vaultId")
            .param("path", newPath)
            .param("id", noteId.value())
            .param("vaultId", vaultId.value())
            .update();
        return findById(vaultId, noteId).orElseThrow(() -> new IllegalStateException("just-renamed note not found: " + noteId));
    }

    @Override
    public ReconciliationPage list(VaultId vaultId, String cursorToken, int pageSize) {
        var cursor = ReconciliationCursor.decode(cursorToken);
        var epochId = cursor.map(ReconciliationCursor::epochId).orElseGet(UUID::randomUUID);
        var lastSeenSequence = cursor.flatMap(ReconciliationCursor::lastSeenSequence).orElse(0L);

        // ORDER BY sequence (bigserial), NICHT id: eine UUID hat keine Beziehung zur
        // Einfuegereihenfolge - eine waehrend der Pagination neu eingefuegte Zeile mit
        // "kleinerer" UUID wuerde bei "id > cursor" dauerhaft uebergangen, obwohl die letzte
        // Seite faelschlich complete=true meldet.
        List<NoteWithSequence> rows = jdbcClient.sql("""
                SELECT * FROM platform.notes
                WHERE vault_id = :vaultId AND sequence > :lastSeenSequence
                ORDER BY sequence
                LIMIT :limit
                """)
            .param("vaultId", vaultId.value())
            .param("lastSeenSequence", lastSeenSequence)
            .param("limit", pageSize + 1)
            .query(NOTE_WITH_SEQUENCE_MAPPER)
            .list();

        var complete = rows.size() <= pageSize;
        var page = complete ? rows : rows.subList(0, pageSize);
        var nextCursor = complete
            ? Optional.<String>empty()
            : Optional.of(ReconciliationCursor.of(epochId, page.get(page.size() - 1).sequence()).encode());

        return new ReconciliationPage(epochId, complete, nextCursor, page.stream().map(NoteWithSequence::note).toList());
    }

    @Override
    @Transactional
    public Tombstone delete(VaultId vaultId, NoteId noteId, String operationId, String deletedBy) {
        var existing = findTombstoneByOperation(vaultId, noteId, operationId);
        if (existing.isPresent()) {
            return existing.get();
        }

        findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId));

        jdbcClient.sql("DELETE FROM platform.notes WHERE id = :id AND vault_id = :vaultId")
            .param("id", noteId.value())
            .param("vaultId", vaultId.value())
            .update();

        var tombstoneId = UUID.randomUUID();
        try {
            jdbcClient.sql("""
                    INSERT INTO platform.note_tombstones (id, vault_id, note_id, operation_id, deleted_by)
                    VALUES (:id, :vaultId, :noteId, :operationId, :deletedBy)
                    """)
                .param("id", tombstoneId)
                .param("vaultId", vaultId.value())
                .param("noteId", noteId.value())
                .param("operationId", operationId)
                .param("deletedBy", deletedBy)
                .update();
        } catch (DuplicateKeyException raceLostToConcurrentDelete) {
            return findTombstoneByOperation(vaultId, noteId, operationId)
                .orElseThrow(() -> raceLostToConcurrentDelete);
        }

        return jdbcClient.sql("SELECT * FROM platform.note_tombstones WHERE id = :id")
            .param("id", tombstoneId)
            .query(TOMBSTONE_MAPPER)
            .single();
    }

    /**
     * Idempotenz-Lookup bewusst nach (vaultId, noteId, operationId) - nicht nur (vaultId,
     * operationId), sonst koennte ein wiederverwendeter operationId-Wert fuer eine ANDERE Note
     * denselben Tombstone zurueckliefern (Fehlerklasse: Operation-ID-Replay).
     */
    private Optional<Tombstone> findTombstoneByOperation(VaultId vaultId, NoteId noteId, String operationId) {
        return jdbcClient.sql("""
                SELECT * FROM platform.note_tombstones
                WHERE vault_id = :vaultId AND note_id = :noteId AND operation_id = :operationId
                """)
            .param("vaultId", vaultId.value())
            .param("noteId", noteId.value())
            .param("operationId", operationId)
            .query(TOMBSTONE_MAPPER)
            .optional();
    }
}
