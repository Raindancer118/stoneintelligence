package de.tstieh.stoneintelligence.platform.ask;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Derselbe Vertrag wie {@link FakeQuestionRepositoryTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcQuestionRepositoryIT extends QuestionRepositoryContractTest {

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

    // Alle Tests teilen sich einen Container; Abfragen ueber Empfaenger/Alter saehen sonst fremde Zeilen.
    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        jdbcClient.sql("TRUNCATE platform.vault_questions CASCADE").update();
    }

    @Override
    protected QuestionRepository repository() {
        return new JdbcQuestionRepository(jdbcClient);
    }

    @Override
    protected VaultId existingVault() {
        return new JdbcVaultRepository(jdbcClient).create("Fragen").id();
    }
}
