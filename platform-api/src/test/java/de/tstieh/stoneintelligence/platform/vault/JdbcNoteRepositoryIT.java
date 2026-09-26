package de.tstieh.stoneintelligence.platform.vault;

import javax.sql.DataSource;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Derselbe Vertrag wie {@link FakeNoteRepositoryTest}, aber gegen echtes Postgres (Testcontainers).
 * Braucht einen laufenden Docker-Daemon - siehe Project.md fuer den Status in dieser Umgebung.
 */
@Testcontainers
class JdbcNoteRepositoryIT extends NoteRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static JdbcNoteRepository repository;
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
        repository = new JdbcNoteRepository(jdbcClient);
    }

    @BeforeEach
    void cleanTables() {
        // CASCADE raeumt ueber die Fremdschluessel-Beziehung auch notes/note_tombstones/
        // note_snapshots mit auf (s. V1__initial_schema.sql, ADR 0004).
        jdbcClient.sql("TRUNCATE platform.vaults CASCADE").update();
    }

    /**
     * `platform.notes.vault_id` hat eine echte Fremdschluessel-Constraint auf `platform.vaults` -
     * anders als {@link FakeNoteRepository} (kein FK-Zwang) muss hier ein echter Vault-Datensatz
     * existieren, bevor eine Note fuer ihn angelegt wird.
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
    protected NoteRepository repository() {
        return repository;
    }
}
