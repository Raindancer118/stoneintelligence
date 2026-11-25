package de.tstieh.stoneintelligence.platform.device;

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

/** Derselbe Vertrag wie {@link FakeDeviceRepositoryTest}, gegen echtes Postgres (V29). */
@Testcontainers
class JdbcDeviceRepositoryIT extends DeviceRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static JdbcClient jdbcClient;
    private static JdbcDeviceRepository devices;
    private static JdbcPluginPolicyRepository policies;
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
        devices = new JdbcDeviceRepository(jdbcClient);
        policies = new JdbcPluginPolicyRepository(jdbcClient);
        vaults = new JdbcVaultRepository(jdbcClient);
    }

    @BeforeEach
    void cleanTables() {
        jdbcClient.sql("TRUNCATE platform.vaults CASCADE").update();
    }

    @Override
    protected DeviceRepository devices() {
        return devices;
    }

    @Override
    protected PluginPolicyRepository policies() {
        return policies;
    }

    @Override
    protected VaultId newVault() {
        return vaults.create("v").id();
    }
}
