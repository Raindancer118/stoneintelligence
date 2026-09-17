package de.raindancer118.stoneintelligence.platform.identity;

import javax.sql.DataSource;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Derselbe Vertrag wie {@link FakeAuthorizationRepositoryTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcAuthorizationRepositoryIT extends AuthorizationRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static JdbcAuthorizationRepository repository;
    private static JdbcClient jdbcClient;

    @BeforeAll
    static void migrateAndBuildRepository() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform")
            .locations("classpath:db/migration/platform")
            .load()
            .migrate();

        DataSource dataSource = new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcClient = JdbcClient.create(dataSource);
        repository = new JdbcAuthorizationRepository(jdbcClient);
    }

    @BeforeEach
    void cleanTables() {
        // CASCADE ueber platform.vaults raeumt roles/groups/group_roles/group_members/
        // path_rules/topic_rules automatisch mit auf (alle haengen per FK am Vault).
        jdbcClient.sql("TRUNCATE platform.vaults CASCADE").update();
    }

    /**
     * `platform.roles`/`platform.groups`/`platform.path_rules`/`platform.topic_rules` haben
     * echte Fremdschluessel-Constraints auf `platform.vaults` - anders als
     * {@link FakeAuthorizationRepository} (kein FK-Zwang) muss hier ein echter Vault-Datensatz
     * existieren, bevor Rollen/Gruppen/Regeln fuer ihn angelegt werden.
     */
    @Override
    protected VaultId newVault() {
        var vaultId = VaultId.newId();
        jdbcClient.sql("INSERT INTO platform.vaults (id, name) VALUES (:id, :name)")
            .param("id", vaultId.value())
            .param("name", "test-vault-" + vaultId.value())
            .update();
        return vaultId;
    }

    @Override
    protected AuthorizationRepository repository() {
        return repository;
    }
}
