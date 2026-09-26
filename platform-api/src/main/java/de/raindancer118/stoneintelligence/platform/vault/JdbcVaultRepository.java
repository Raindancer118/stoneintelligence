package de.raindancer118.stoneintelligence.platform.vault;

import java.util.Optional;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVaultRepository implements VaultRepository {

    private static final RowMapper<Vault> VAULT_MAPPER = (rs, rowNum) -> new Vault(
        VaultId.of(rs.getString("id")),
        rs.getString("name"),
        rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcClient jdbcClient;

    public JdbcVaultRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Vault create(String name) {
        return jdbcClient.sql("INSERT INTO platform.vaults (name) VALUES (:name) RETURNING id, name, created_at")
            .param("name", name)
            .query(VAULT_MAPPER)
            .single();
    }

    @Override
    public Optional<Vault> findById(VaultId id) {
        return jdbcClient.sql("SELECT id, name, created_at FROM platform.vaults WHERE id = :id")
            .param("id", id.value())
            .query(VAULT_MAPPER)
            .optional();
    }

    @Override
    public Optional<Vault> rename(VaultId id, String name) {
        return jdbcClient.sql("UPDATE platform.vaults SET name = :name WHERE id = :id RETURNING id, name, created_at")
            .param("id", id.value())
            .param("name", name)
            .query(VAULT_MAPPER)
            .optional();
    }
}
