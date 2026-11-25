package de.tstieh.stoneintelligence.platform.device;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPluginPolicyRepository implements PluginPolicyRepository {

    private final JdbcClient jdbcClient;

    public JdbcPluginPolicyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public PluginPolicy find(VaultId vaultId) {
        return jdbcClient.sql("SELECT mode, allowed::text AS allowed FROM platform.plugin_policies WHERE vault_id = :vaultId")
            .param("vaultId", vaultId.value())
            .query((rs, rowNum) -> new PluginPolicy(vaultId, PolicyMode.valueOf(rs.getString("mode")),
                PluginRefsJson.read(rs.getString("allowed"))))
            .optional().orElseGet(() -> PluginPolicy.off(vaultId));
    }

    @Override
    public void save(PluginPolicy policy, String actor) {
        jdbcClient.sql("""
                INSERT INTO platform.plugin_policies (vault_id, mode, allowed, updated_by)
                VALUES (:vaultId, :mode, CAST(:allowed AS jsonb), :actor)
                ON CONFLICT (vault_id) DO UPDATE
                SET mode = EXCLUDED.mode, allowed = EXCLUDED.allowed, updated_by = EXCLUDED.updated_by,
                    updated_at = clock_timestamp()
                """)
            .param("vaultId", policy.vaultId().value())
            .param("mode", policy.mode().name())
            .param("allowed", PluginRefsJson.write(policy.allowed()))
            .param("actor", actor)
            .update();
    }
}
