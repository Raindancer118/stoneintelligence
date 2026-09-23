package de.raindancer118.stoneintelligence.platform.files;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFileVersionRepository implements FileVersionRepository {

    private final JdbcClient jdbcClient;

    public JdbcFileVersionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static FileVersion map(ResultSet rs, int rowNum) throws SQLException {
        return new FileVersion(NoteId.of(java.util.UUID.fromString(rs.getString("note_id"))), rs.getLong("revision"),
            rs.getString("sha256"), rs.getLong("size"), rs.getString("content_type"), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant());
    }

    /**
     * Die Basis-Pruefung steckt in der Abfrage selbst; zwei gleichzeitige Aenderungen auf derselben
     * Basis wollen dieselbe neue Revision - der Primaerschluessel laesst nur eine durch.
     */
    @Override
    public FileVersion append(NoteId noteId, long expectedRevision, StoredBlob blob, String contentType, String createdBy,
                              Instant at) {
        try {
            var inserted = jdbcClient.sql("""
                    INSERT INTO platform.file_versions (note_id, revision, sha256, size, content_type, created_by, created_at)
                    SELECT :noteId, :expected + 1, :sha256, :size, :contentType, :createdBy, :at
                    WHERE (SELECT coalesce(max(revision), 0) FROM platform.file_versions WHERE note_id = :noteId) = :expected
                    """)
                .param("noteId", noteId.value())
                .param("expected", expectedRevision)
                .param("sha256", blob.sha256())
                .param("size", blob.size())
                .param("contentType", contentType)
                .param("createdBy", createdBy)
                .param("at", Timestamp.from(at))
                .update();
            if (inserted == 1) {
                return new FileVersion(noteId, expectedRevision + 1, blob.sha256(), blob.size(), contentType, createdBy, at);
            }
        } catch (DuplicateKeyException raced) {
            // Gleichzeitig dieselbe Basis - unten mit der dann aktuellen Revision melden.
        }
        throw new FileRevisionConflictException(current(noteId).map(FileVersion::revision).orElse(0L));
    }

    @Override
    public Optional<FileVersion> current(NoteId noteId) {
        return jdbcClient.sql("SELECT * FROM platform.file_versions WHERE note_id = :noteId ORDER BY revision DESC LIMIT 1")
            .param("noteId", noteId.value())
            .query(JdbcFileVersionRepository::map)
            .optional();
    }

    @Override
    public Map<NoteId, FileVersion> current(Collection<NoteId> noteIds) {
        if (noteIds.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql("""
                SELECT DISTINCT ON (note_id) * FROM platform.file_versions
                WHERE note_id IN (:ids) ORDER BY note_id, revision DESC
                """)
            .param("ids", noteIds.stream().map(NoteId::value).toList())
            .query(JdbcFileVersionRepository::map)
            .list().stream()
            .collect(Collectors.toMap(FileVersion::noteId, Function.identity()));
    }

    @Override
    public long usage(VaultId vaultId) {
        return jdbcClient.sql("""
                SELECT coalesce(sum(v.size), 0) FROM platform.file_versions v
                JOIN platform.notes n ON n.id = v.note_id WHERE n.vault_id = :vaultId
                """)
            .param("vaultId", vaultId.value())
            .query(Long.class)
            .single();
    }

    @Override
    public int purgeReplaced(Instant olderThan) {
        return jdbcClient.sql("""
                DELETE FROM platform.file_versions v
                WHERE v.created_at < :olderThan
                  AND v.revision < (SELECT max(revision) FROM platform.file_versions c WHERE c.note_id = v.note_id)
                """)
            .param("olderThan", Timestamp.from(olderThan))
            .update();
    }

    @Override
    public Set<String> referencedHashes() {
        return new HashSet<>(jdbcClient.sql("SELECT DISTINCT sha256 FROM platform.file_versions").query(String.class).list());
    }
}
