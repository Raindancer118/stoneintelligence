package de.tstieh.stoneintelligence.platform.vault;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Derselbe Vertrag wie {@link FakeVaultRepositoryTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcVaultRepositoryIT extends VaultRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static JdbcVaultRepository repository;
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
        repository = new JdbcVaultRepository(jdbcClient);
    }

    @BeforeEach
    void cleanTables() {
        jdbcClient.sql("TRUNCATE platform.vaults CASCADE").update();
    }

    @Override
    protected VaultRepository repository() {
        return repository;
    }
}
