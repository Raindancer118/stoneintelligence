package de.tstieh.stoneintelligence.platform.ai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLinkingSettingsRepository implements LinkingSettingsRepository {

    private final JdbcClient jdbcClient;

    public JdbcLinkingSettingsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<LinkingSettings> find(VaultId vaultId) {
        return jdbcClient.sql("SELECT * FROM platform.vault_linking WHERE vault_id = :vaultId")
            .param("vaultId", vaultId.value())
            .query(JdbcLinkingSettingsRepository::map)
            .optional();
    }

    @Override
    public void save(LinkingSettings settings) {
        jdbcClient.sql("""
                INSERT INTO platform.vault_linking (vault_id, enabled, mode, link_human_notes, max_links_per_note, service,
                                                    requested_by, last_run_at, updated_at)
                VALUES (:vaultId, :enabled, :mode, :humans, :max, :service, :requestedBy, :lastRunAt, :updatedAt)
                ON CONFLICT (vault_id) DO UPDATE SET enabled = EXCLUDED.enabled, mode = EXCLUDED.mode,
                    link_human_notes = EXCLUDED.link_human_notes, max_links_per_note = EXCLUDED.max_links_per_note,
                    service = EXCLUDED.service, requested_by = EXCLUDED.requested_by, updated_at = EXCLUDED.updated_at
                """)
            .param("vaultId", settings.vaultId().value())
            .param("enabled", settings.enabled())
            .param("mode", settings.mode().name())
            .param("humans", settings.linkHumanNotes())
            .param("max", settings.maxLinksPerNote())
            .param("service", settings.service())
            .param("requestedBy", settings.requestedBy())
            .param("lastRunAt", settings.lastRunAt() == null ? null : Timestamp.from(settings.lastRunAt()))
            .param("updatedAt", Timestamp.from(settings.updatedAt()))
            .update();
    }

    @Override
    public List<LinkingSettings> enabled() {
        return jdbcClient.sql("SELECT * FROM platform.vault_linking WHERE enabled ORDER BY vault_id")
            .query(JdbcLinkingSettingsRepository::map)
            .list();
    }

    @Override
    public void markRun(VaultId vaultId, Instant at) {
        jdbcClient.sql("UPDATE platform.vault_linking SET last_run_at = :at WHERE vault_id = :vaultId")
            .param("at", Timestamp.from(at))
            .param("vaultId", vaultId.value())
            .update();
    }

    private static LinkingSettings map(ResultSet rs, int rowNum) throws SQLException {
        var lastRun = rs.getTimestamp("last_run_at");
        return new LinkingSettings(VaultId.of(rs.getString("vault_id")), rs.getBoolean("enabled"),
            LinkingSettings.Mode.valueOf(rs.getString("mode")), rs.getBoolean("link_human_notes"),
            (Integer) rs.getObject("max_links_per_note"), rs.getString("service"), rs.getString("requested_by"),
            lastRun == null ? null : lastRun.toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    @Override
    public java.util.Set<String> aiConsents(VaultId vaultId) {
        return java.util.Set.copyOf(jdbcClient.sql("SELECT subject FROM platform.linking_ai_consents WHERE vault_id = :vaultId")
            .param("vaultId", vaultId.value())
            .query(String.class)
            .list());
    }

    @Override
    public void setAiConsent(VaultId vaultId, String subject, boolean consent, Instant at) {
        if (consent) {
            jdbcClient.sql("""
                    INSERT INTO platform.linking_ai_consents (vault_id, subject, consented_at) VALUES (:vaultId, :subject, :at)
                    ON CONFLICT (vault_id, subject) DO NOTHING
                    """)
                .param("vaultId", vaultId.value()).param("subject", subject).param("at", Timestamp.from(at)).update();
        } else {
            jdbcClient.sql("DELETE FROM platform.linking_ai_consents WHERE vault_id = :vaultId AND subject = :subject")
                .param("vaultId", vaultId.value()).param("subject", subject).update();
        }
    }
}
