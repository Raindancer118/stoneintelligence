package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSnapshotStore implements SnapshotStore {

    private static final int MAX_SEQUENCE_RETRIES = 5;

    private static final RowMapper<UpdateRecord> RECORD_MAPPER = (rs, rowNum) -> new UpdateRecord(
        rs.getLong("server_sequence"), rs.getBytes("state"), rs.getBoolean("is_ciphertext"));

    private final JdbcClient jdbcClient;

    public JdbcSnapshotStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * {@code server_sequence} ist bewusst kein DB-Sequence/Serial, sondern je Note fortlaufend
     * (0..n) - "wie viele Updates hat diese Note bisher gesehen" ist eine fuer Late-Joiner-
     * Catchup nuetzliche Groesse. Da es keine Row-Lock-Garantie ueber ein einfaches
     * INSERT..SELECT MAX gibt, wird ein Unique-Constraint-Konflikt unter Nebenlaeufigkeit mit
     * wenigen Versuchen aufgeloest statt die Anfrage hart scheitern zu lassen.
     */
    @Override
    public UpdateRecord append(NoteId noteId, byte[] payload, boolean ciphertext) {
        for (int attempt = 1; attempt <= MAX_SEQUENCE_RETRIES; attempt++) {
            try {
                var sequence = jdbcClient.sql("""
                        INSERT INTO platform.note_snapshots (note_id, server_sequence, state, is_ciphertext)
                        SELECT :noteId, COALESCE(MAX(server_sequence), 0) + 1, :state, :ciphertext
                        FROM platform.note_snapshots WHERE note_id = :noteId
                        RETURNING server_sequence
                        """)
                    .param("noteId", noteId.value())
                    .param("state", payload)
                    .param("ciphertext", ciphertext)
                    .query(Long.class)
                    .single();
                return new UpdateRecord(sequence, payload, ciphertext);
            } catch (DuplicateKeyException concurrentAppend) {
                if (attempt == MAX_SEQUENCE_RETRIES) {
                    throw concurrentAppend;
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** Ein CAS ueber den vorhandenen Unique-Key; konkurrierende WS-Appends gewinnen oder verlieren atomar. */
    @Override
    public java.util.Optional<UpdateRecord> appendIfCurrent(NoteId noteId, long expectedRevision, byte[] payload) {
        return jdbcClient.sql("""
                INSERT INTO platform.note_snapshots (note_id, server_sequence, state, is_ciphertext)
                SELECT :noteId, :expected + 1, :state, false
                WHERE :expected = (SELECT COALESCE(MAX(server_sequence), 0)
                    FROM platform.note_snapshots WHERE note_id = :noteId)
                ON CONFLICT (note_id, server_sequence) DO NOTHING
                RETURNING server_sequence
                """)
            .param("noteId", noteId.value()).param("expected", expectedRevision).param("state", payload)
            .query(Long.class).optional().map(sequence -> new UpdateRecord(sequence, payload, false));
    }

    @Override
    public List<UpdateRecord> listSince(NoteId noteId, long afterServerSequence) {
        return jdbcClient.sql("""
                SELECT server_sequence, state, is_ciphertext FROM platform.note_snapshots
                WHERE note_id = :noteId AND server_sequence > :after
                ORDER BY server_sequence
                """)
            .param("noteId", noteId.value())
            .param("after", afterServerSequence)
            .query(RECORD_MAPPER)
            .list();
    }

    @Override
    public java.util.Map<NoteId, Long> latestRevisions(java.util.Collection<NoteId> noteIds) {
        var revisions = new java.util.HashMap<NoteId, Long>();
        noteIds.forEach(id -> revisions.put(id, 0L));
        if (noteIds.isEmpty()) {
            return revisions;
        }
        jdbcClient.sql("""
                SELECT note_id, MAX(server_sequence) AS revision FROM platform.note_snapshots
                WHERE note_id IN (:noteIds)
                GROUP BY note_id
                """)
            .param("noteIds", noteIds.stream().map(NoteId::value).toList())
            .query((rs, rowNum) -> java.util.Map.entry(NoteId.of(rs.getString("note_id")), rs.getLong("revision")))
            .list()
            .forEach(entry -> revisions.put(entry.getKey(), entry.getValue()));
        return revisions;
    }
}
