package de.tstieh.stoneintelligence.platform.identity;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import de.tstieh.stoneintelligence.domain.id.VaultId;
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
        new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static JdbcAuthorizationRepository repository;
    private static JdbcClient jdbcClient;
    private static final AtomicInteger queryCount = new AtomicInteger();

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
        jdbcClient = JdbcClient.create(new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) {
                            queryCount.incrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            }
        });
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

    @Test
    void should_loadAllGroupRolesWithBoundedQueries_andKeepVaultsIsolated() {
        var vaultId = newVault();
        var reader = repository.createRole(vaultId, "reader", Set.of(Permission.READ));
        var writer = repository.createRole(vaultId, "writer", Set.of(Permission.WRITE));
        var owners = repository.createGroup(vaultId, "owners");
        repository.assignRole(owners.id(), reader.id());
        repository.addMember(owners.id(), "tom");
        for (int i = 0; i < 30; i++) {
            var group = repository.createGroup(vaultId, "team-" + i);
            repository.assignRole(group.id(), reader.id());
            repository.assignRole(group.id(), writer.id());
        }
        repository.createGroup(vaultId, "empty");
        var otherVault = newVault();
        var otherRole = repository.createRole(otherVault, "private", Set.of(Permission.DELETE));
        var otherGroup = repository.createGroup(otherVault, "private");
        repository.assignRole(otherGroup.id(), otherRole.id());
        var notes = new de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository();
        var grants = new FakeAccessGrantRepository(notes);
        var controller = new AuthorizationController(repository, new VaultAccessGuard(repository, grants), grants, notes,
            (vault, note, actor, action, payload) -> { }, new de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService());
        queryCount.set(0);

        var groups = controller.listGroups(vaultId.value().toString(), new TestingAuthenticationToken("tom", null));

        assertThat(groups).hasSize(32);
        assertThat(groups).filteredOn(group -> group.name().startsWith("team-"))
            .allSatisfy(group -> assertThat(group.roleIds()).containsExactlyInAnyOrder(reader.id().toString(), writer.id().toString()));
        assertThat(groups).filteredOn(group -> group.name().equals("empty"))
            .singleElement().satisfies(group -> assertThat(group.roleIds()).isEmpty());
        assertThat(groups).allSatisfy(group -> assertThat(group.roleIds()).doesNotContain(otherRole.id().toString()));
        assertThat(queryCount.get()).as("SQL round trips including permission check").isLessThanOrEqualTo(4);
    }

    @Override
    protected AuthorizationRepository repository() {
        return repository;
    }
}
