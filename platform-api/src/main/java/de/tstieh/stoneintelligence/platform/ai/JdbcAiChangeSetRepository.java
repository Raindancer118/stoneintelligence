package de.tstieh.stoneintelligence.platform.ai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAiChangeSetRepository implements AiChangeSetRepository {

    private final JdbcClient jdbcClient;

    public JdbcAiChangeSetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static AiChangeSet mapSet(ResultSet rs, int rowNum) throws SQLException {
        return new AiChangeSet(UUID.fromString(rs.getString("id")), VaultId.of(rs.getString("vault_id")), rs.getString("service"),
            rs.getString("agent"), rs.getString("requested_by"), rs.getString("label"), instant(rs, "created_at"),
            instant(rs, "reverted_at"));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Nur was Rueckgaengig braucht - der Text nach dem Einfuegen gehoert nicht in die Datenbank. */
    private record StoredLink(String placement, String markup, String anchor, boolean createdSection, String sectionPrefix) {
    }

    private static AiChange mapChange(ResultSet rs, int rowNum) throws SQLException {
        return new AiChange(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("change_set_id")),
            NoteId.of(rs.getString("note_id")), rs.getString("path"), AiChange.Kind.valueOf(rs.getString("kind")),
            rs.getString("text_before"), rs.getString("text_after"), instant(rs, "at"), linksOf(rs.getString("details")));
    }

    private static List<de.tstieh.stoneintelligence.domain.link.LinkText.Insertion> linksOf(String details) {
        if (details == null) {
            return List.of();
        }
        try {
            return java.util.Arrays.stream(JSON.readValue(details, StoredLink[].class))
                .map(link -> new de.tstieh.stoneintelligence.domain.link.LinkText.Insertion(null,
                    de.tstieh.stoneintelligence.domain.link.LinkText.Placement.valueOf(link.placement()), link.markup(), link.anchor(),
                    link.createdSection(), link.sectionPrefix() == null ? "" : link.sectionPrefix()))
                .toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt link details", e);
        }
    }

    private static String detailsOf(AiChange change) {
        if (change.links().isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(change.links().stream().map(link -> new StoredLink(link.placement().name(), link.markup(),
                link.anchor(), link.createdSection(), link.sectionPrefix())).toList());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public AiChangeSet create(VaultId vaultId, String service, String agent, String requestedBy, String label, Instant at) {
        return jdbcClient.sql("""
                INSERT INTO platform.ai_change_sets (id, vault_id, service, agent, requested_by, label, created_at)
                VALUES (:id, :vaultId, :service, :agent, :requestedBy, :label, :createdAt)
                RETURNING *
                """)
            .param("id", UUID.randomUUID())
            .param("vaultId", vaultId.value())
            .param("service", service)
            .param("agent", agent)
            .param("requestedBy", requestedBy)
            .param("label", label)
            .param("createdAt", Timestamp.from(at))
            .query(JdbcAiChangeSetRepository::mapSet)
            .single();
    }

    @Override
    public Optional<AiChangeSet> find(VaultId vaultId, UUID id) {
        return jdbcClient.sql("SELECT * FROM platform.ai_change_sets WHERE id = :id AND vault_id = :vaultId")
            .param("id", id)
            .param("vaultId", vaultId.value())
            .query(JdbcAiChangeSetRepository::mapSet)
            .optional();
    }

    @Override
    public List<AiChangeSet> list(VaultId vaultId, int limit) {
        return jdbcClient.sql("SELECT * FROM platform.ai_change_sets WHERE vault_id = :vaultId ORDER BY created_at DESC LIMIT :limit")
            .param("vaultId", vaultId.value())
            .param("limit", limit)
            .query(JdbcAiChangeSetRepository::mapSet)
            .list();
    }

    @Override
    public void addChange(AiChange change) {
        jdbcClient.sql("""
                INSERT INTO platform.ai_changes (id, change_set_id, note_id, path, kind, text_before, text_after, at, details)
                VALUES (:id, :changeSetId, :noteId, :path, :kind, :before, :after, :at, CAST(:details AS jsonb))
                """)
            .param("details", detailsOf(change))
            .param("id", change.id())
            .param("changeSetId", change.changeSetId())
            .param("noteId", change.noteId().value())
            .param("path", change.path())
            .param("kind", change.kind().name())
            .param("before", change.textBefore())
            .param("after", change.textAfter())
            .param("at", Timestamp.from(change.at()))
            .update();
    }

    @Override
    public List<AiChange> changes(UUID changeSetId) {
        return jdbcClient.sql("SELECT * FROM platform.ai_changes WHERE change_set_id = :id ORDER BY sequence")
            .param("id", changeSetId)
            .query(JdbcAiChangeSetRepository::mapChange)
            .list();
    }

    @Override
    public boolean markReverted(UUID id, Instant at) {
        return jdbcClient.sql("UPDATE platform.ai_change_sets SET reverted_at = :at WHERE id = :id AND reverted_at IS NULL")
            .param("at", Timestamp.from(at))
            .param("id", id)
            .update() == 1;
    }

    @Override
    public int purgeCreatedBefore(Instant cutoff) {
        return jdbcClient.sql("DELETE FROM platform.ai_change_sets WHERE created_at < :cutoff")
            .param("cutoff", Timestamp.from(cutoff))
            .update();
    }

    @Override
    public java.util.Set<NoteId> linkedTargets(VaultId vaultId, NoteId source) {
        return java.util.Set.copyOf(jdbcClient.sql("""
                SELECT target_note_id FROM platform.link_pairs WHERE vault_id = :vaultId AND source_note_id = :source
                """)
            .param("vaultId", vaultId.value())
            .param("source", source.value())
            .query((rs, row) -> NoteId.of((UUID) rs.getObject("target_note_id")))
            .list());
    }

    @Override
    public void rememberLink(VaultId vaultId, NoteId source, NoteId target, Instant at) {
        jdbcClient.sql("""
                INSERT INTO platform.link_pairs (vault_id, source_note_id, target_note_id, linked_at)
                VALUES (:vaultId, :source, :target, :at) ON CONFLICT DO NOTHING
                """)
            .param("vaultId", vaultId.value())
            .param("source", source.value())
            .param("target", target.value())
            .param("at", Timestamp.from(at))
            .update();
    }
}
