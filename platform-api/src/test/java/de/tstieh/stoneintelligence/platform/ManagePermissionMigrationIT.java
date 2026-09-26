package de.tstieh.stoneintelligence.platform;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 trennt "Mitglieder/Rechte verwalten" (MANAGE) von "Notizen loeschen" (DELETE). Bis dahin
 * war DELETE zugleich das Verwaltungsrecht - jede bestehende Rolle mit DELETE muss deshalb MANAGE
 * dazubekommen, sonst verloeren Vault-Besitzer mit dem Update ihre Verwaltungsrechte.
 */
@Testcontainers
class ManagePermissionMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private Flyway flyway(String target) {
        var config = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform")
            .locations("classpath:db/migration/platform");
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    @Test
    void should_grantManage_toEveryRoleThatCouldDeleteBefore_andToNoOtherRole() throws Exception {
        flyway("5").migrate();
        var vaultId = UUID.randomUUID();
        var owner = UUID.randomUUID();
        var reader = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO platform.vaults (id, name) VALUES ('%s', 'v')".formatted(vaultId));
            statement.execute("INSERT INTO platform.roles (id, vault_id, name) VALUES ('%s', '%s', 'owner'), ('%s', '%s', 'reader')"
                .formatted(owner, vaultId, reader, vaultId));
            statement.execute(("INSERT INTO platform.role_permissions (role_id, permission) VALUES "
                + "('%s', 'READ'), ('%s', 'DELETE'), ('%s', 'READ')").formatted(owner, owner, reader));

            flyway(null).migrate();

            assertThat(permissions(statement, owner)).containsExactlyInAnyOrder("READ", "DELETE", "MANAGE");
            assertThat(permissions(statement, reader)).containsExactly("READ");
        }
    }

    private static List<String> permissions(java.sql.Statement statement, UUID roleId) throws Exception {
        var result = new ArrayList<String>();
        try (var rs = statement.executeQuery("SELECT permission FROM platform.role_permissions WHERE role_id = '%s'".formatted(roleId))) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }
}
