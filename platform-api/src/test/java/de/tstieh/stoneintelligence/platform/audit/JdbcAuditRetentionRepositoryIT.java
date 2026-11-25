package de.tstieh.stoneintelligence.platform.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Derselbe Vertrag wie {@link FakeAuditRetentionRepositoryTest}, gegen echtes Postgres (V25 und das Loesch-SQL). */
@Testcontainers
class JdbcAuditRetentionRepositoryIT extends AuditRetentionRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static JdbcClient jdbcClient;
    private static JdbcAuditRetentionRepository repository;
    private static JdbcVaultRepository vaults;

    @BeforeAll
    static void migrateAndBuild() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform")
            .locations("classpath:db/migration/platform")
            .load()
            .migrate();
        jdbcClient = JdbcClient.create(new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        repository = new JdbcAuditRetentionRepository(jdbcClient);
        vaults = new JdbcVaultRepository(jdbcClient);
    }

    @BeforeEach
    void cleanTables() {
        jdbcClient.sql("TRUNCATE platform.vaults CASCADE").update();
        jdbcClient.sql("TRUNCATE platform.audit_events").update();
    }

    @Override
    protected AuditRetentionRepository repository() {
        return repository;
    }

    @Override
    protected VaultId newVault() {
        return vaults.create("v").id();
    }

    @Override
    protected void seed(VaultId vaultId, String actor, String action, Map<String, Object> payload, Instant at) {
        try {
            jdbcClient.sql("""
                    INSERT INTO platform.audit_events (vault_id, actor, action, payload, occurred_at)
                    VALUES (:vaultId, :actor, :action, CAST(:payload AS jsonb), :at)
                    """)
                .param("vaultId", vaultId.value())
                .param("actor", actor)
                .param("action", action)
                .param("payload", new ObjectMapper().writeValueAsString(payload))
                .param("at", Timestamp.from(at))
                .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected List<String> remainingActions(VaultId vaultId) {
        return jdbcClient.sql("SELECT action FROM platform.audit_events WHERE vault_id = :vaultId")
            .param("vaultId", vaultId.value()).query(String.class).list();
    }
}
