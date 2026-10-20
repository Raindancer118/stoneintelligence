package de.tstieh.stoneintelligence.platform.scim;

import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.AuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.JdbcAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.JdbcPeopleDirectory;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.AccountData;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.GroupData;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Derselbe Vertrag wie {@link FakeDirectoryStoreTest} - hier prueft er auch Sicht und Trigger aus V22. */
@Testcontainers
class JdbcDirectoryStoreIT extends DirectoryStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static JdbcClient jdbcClient;
    private static JdbcDirectoryStore store;
    private static JdbcAuthorizationRepository authorization;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform")
            .locations("classpath:db/migration/platform")
            .load()
            .migrate();
        DataSource dataSource = new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcClient = JdbcClient.create(dataSource);
        store = new JdbcDirectoryStore(jdbcClient);
        authorization = new JdbcAuthorizationRepository(jdbcClient);
    }

    @BeforeEach
    void clean() {
        jdbcClient.sql("TRUNCATE platform.vaults, platform.subject_idp_groups, platform.directory_users, "
            + "platform.directory_groups, platform.people CASCADE").update();
    }

    @Override
    protected DirectoryStore store() {
        return store;
    }

    @Override
    protected AuthorizationRepository authorization() {
        return authorization;
    }

    @Override
    protected VaultId newVault() {
        var vaultId = VaultId.newId();
        jdbcClient.sql("INSERT INTO platform.vaults (id, name) VALUES (:id, :name)")
            .param("id", vaultId.value()).param("name", "vault-" + vaultId.value()).update();
        return vaultId;
    }

    private long accessVersion(VaultId vault) {
        return jdbcClient.sql("SELECT access_version FROM platform.vaults WHERE id = :id").param("id", vault.value())
            .query(Long.class).single();
    }

    private List<String> userEvents(VaultId vault) {
        return jdbcClient.sql("SELECT scope_subject FROM platform.access_events WHERE vault_id = :id AND scope_type = 'USER'")
            .param("id", vault.value()).query(String.class).list();
    }

    // Rechte-Cache (Zugriffs-Version) und Geraete (Feed) muessen von jeder SCIM-Aenderung erfahren.
    @Test
    void should_bumpTheAccessVersion_andRecordAnEvent_forEveryMembershipChange() {
        var vault = newVault();
        authorization.linkIdpGroup(authorization.createGroup(vault, "Vertrieb").id(), "Sales");
        var ben = store.saveAccount(null, new AccountData("ben", "ben", null, null, true));
        var sales = store.saveGroup(null, new GroupData("Sales", "Sales", null));
        var before = accessVersion(vault);

        store.addMembers(sales.id(), Set.of(ben.id()));
        var afterJoin = accessVersion(vault);
        store.saveAccount(ben.id(), new AccountData("ben", "ben", null, null, false));
        var afterBlock = accessVersion(vault);
        store.saveGroup(sales.id(), new GroupData("Sales EMEA", "Sales EMEA", null));

        assertThat(afterJoin).isGreaterThan(before);
        assertThat(afterBlock).isGreaterThan(afterJoin);
        assertThat(accessVersion(vault)).as("renamed group no longer matches the link").isGreaterThan(afterBlock);
        assertThat(userEvents(vault)).contains("ben");
    }

    @Test
    void should_preferTheScimDisplayName_overTheOneFromTheToken() {
        var people = new JdbcPeopleDirectory(jdbcClient);
        people.remember("ben", "ben (Token)", java.time.Instant.now());
        store.saveAccount(null, new AccountData("ben", "ben", null, "Ben Becker", true));

        assertThat(people.displayNames(List.of("ben"))).containsEntry("ben", "Ben Becker");
    }
}
