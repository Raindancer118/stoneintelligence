package de.tstieh.stoneintelligence.platform.ai;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Derselbe Vertrag wie {@link FakeAiChangeSetRepositoryTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcAiChangeSetRepositoryIT extends AiChangeSetRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private static JdbcClient jdbcClient;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform").locations("classpath:db/migration/platform").load().migrate();
        jdbcClient = JdbcClient.create(new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Override
    protected AiChangeSetRepository repository() {
        jdbcClient.sql("TRUNCATE platform.ai_change_sets CASCADE").update();
        return new JdbcAiChangeSetRepository(jdbcClient);
    }

    @Override
    protected VaultId existingVault() {
        return new JdbcVaultRepository(jdbcClient).create("KI-Test").id();
    }

    @Override
    protected de.tstieh.stoneintelligence.domain.id.NoteId existingNote(VaultId vaultId, String path) {
        return new de.tstieh.stoneintelligence.platform.vault.JdbcNoteRepository(jdbcClient)
            .create(vaultId, path, de.tstieh.stoneintelligence.domain.notelevel.NoteLevel.of(1), "tom").id();
    }
}
