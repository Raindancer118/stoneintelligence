package de.raindancer118.stoneintelligence.platform.identity;

import javax.sql.DataSource;
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
        repository = new JdbcAuthorizationRepository(JdbcClient.create(dataSource));
    }

    @BeforeEach
    void cleanTables() {
        DataSource dataSource = new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcClient.create(dataSource)
            .sql("TRUNCATE platform.group_roles, platform.group_members, platform.role_permissions, "
                + "platform.roles, platform.groups CASCADE")
            .update();
    }

    @Override
    protected AuthorizationRepository repository() {
        return repository;
    }
}
