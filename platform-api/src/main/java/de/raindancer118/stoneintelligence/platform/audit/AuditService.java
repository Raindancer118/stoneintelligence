package de.raindancer118.stoneintelligence.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Schreibt/liest den dauerhaften Audit-Trail (`platform.audit_events`). Bewusst kein
 * Domain-Event-Bus/Async-Queue fuer diesen ersten Wurf - der Schreibpfad ist synchron und Teil
 * derselben Transaktion wie die fachliche Aenderung, damit kein Audit-Eintrag verloren gehen
 * kann, waehrend die zugehoerige Aenderung erfolgreich war.
 */
@Service
public class AuditService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final RowMapper<AuditEvent> MAPPER = (rs, rowNum) -> {
        try {
            return new AuditEvent(
                UUID.fromString(rs.getString("id")),
                VaultId.of(rs.getString("vault_id")),
                rs.getString("note_id") == null ? null : NoteId.of(rs.getString("note_id")),
                rs.getString("actor"),
                rs.getString("action"),
                OBJECT_MAPPER.readValue(rs.getString("payload"), new TypeReference<Map<String, Object>>() { }),
                rs.getTimestamp("occurred_at").toInstant()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt audit payload for event " + rs.getString("id"), e);
        }
    };

    private final JdbcClient jdbcClient;

    public AuditService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void record(VaultId vaultId, NoteId noteId, String actor, String action, Map<String, Object> payload) {
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("audit payload is not serializable to JSON", e);
        }

        jdbcClient.sql("""
                INSERT INTO platform.audit_events (vault_id, note_id, actor, action, payload)
                VALUES (:vaultId, :noteId, :actor, :action, CAST(:payload AS jsonb))
                """)
            .param("vaultId", vaultId.value())
            .param("noteId", noteId == null ? null : noteId.value())
            .param("actor", actor)
            .param("action", action)
            .param("payload", json)
            .update();
    }

    public List<AuditEvent> listForNote(VaultId vaultId, NoteId noteId) {
        return jdbcClient.sql("""
                SELECT * FROM platform.audit_events
                WHERE vault_id = :vaultId AND note_id = :noteId
                ORDER BY occurred_at
                """)
            .param("vaultId", vaultId.value())
            .param("noteId", noteId.value())
            .query(MAPPER)
            .list();
    }
}
