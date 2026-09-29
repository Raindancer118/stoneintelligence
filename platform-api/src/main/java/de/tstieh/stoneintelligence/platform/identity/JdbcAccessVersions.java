package de.tstieh.stoneintelligence.platform.identity;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccessVersions implements AccessVersions {

    private final JdbcClient jdbcClient;

    public JdbcAccessVersions(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public long current(VaultId vaultId) {
        return jdbcClient.sql("SELECT access_version FROM platform.vaults WHERE id = :id")
            .param("id", vaultId.value()).query(Long.class).optional().orElse(0L);
    }
}
