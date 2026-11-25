package de.tstieh.stoneintelligence.platform.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRetentionRepository implements AuditRetentionRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuditRetentionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Integer retentionDays(VaultId vaultId) {
        return jdbcClient.sql("SELECT audit_retention_days FROM platform.vaults WHERE id = :id")
            .param("id", vaultId.value())
            .query((rs, rowNum) -> (Integer) rs.getObject("audit_retention_days"))
            .optional().orElse(null);
    }

    @Override
    public void setRetentionDays(VaultId vaultId, Integer days) {
        jdbcClient.sql("UPDATE platform.vaults SET audit_retention_days = CAST(:days AS integer) WHERE id = :id")
            .param("id", vaultId.value())
            .param("days", days)
            .update();
    }

    @Override
    public Map<VaultId, Integer> allRetentions() {
        var result = new HashMap<VaultId, Integer>();
        jdbcClient.sql("SELECT id, audit_retention_days FROM platform.vaults WHERE audit_retention_days IS NOT NULL")
            .query((rs, rowNum) -> result.put(VaultId.of(rs.getString("id")), rs.getInt("audit_retention_days")))
            .list();
        return result;
    }

    @Override
    public int deleteExpired(VaultId vaultId, Instant before) {
        // jsonb_exists statt des Operators "?", den JDBC als Platzhalter lesen wuerde.
        return jdbcClient.sql("""
                DELETE FROM platform.audit_events
                WHERE vault_id = :vaultId AND occurred_at < :before
                  AND actor NOT LIKE 'ki:%' AND action NOT LIKE 'ai.%' AND NOT jsonb_exists(payload, 'changeSet')
                """)
            .param("vaultId", vaultId.value())
            .param("before", Timestamp.from(before))
            .update();
    }
}
