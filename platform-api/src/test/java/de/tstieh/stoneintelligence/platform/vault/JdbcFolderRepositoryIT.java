package de.tstieh.stoneintelligence.platform.vault;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Derselbe Vertrag wie {@link FakeFolderRepositoryTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcFolderRepositoryIT extends FolderRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private static JdbcClient jdbcClient;

    @BeforeAll
    static void migrate() {
        jdbcClient = JdbcClient.create(new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static void migrateTo(String version) {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform").locations("classpath:db/migration/platform").target(version).load().migrate();
    }

    @Override
    protected FolderRepository repository() {
        migrateTo("latest");
        return new JdbcFolderRepository(jdbcClient);
    }

    @Override
    protected VaultId newVault() {
        return new JdbcVaultRepository(jdbcClient).create("Ordner-Test").id();
    }

    // Bestehende Vaults kennen bisher nur Notizen: ihre Ordner muessen beim Update entstehen,
    // sonst wuerden neue Plugins sie fuer "anderswo geloescht" halten.
    @Test
    void should_backfillTheFoldersOfExistingNotes_whenMigrating() {
        var fresh = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES);
        fresh.start();
        try {
            var client = JdbcClient.create(new SimpleDriverDataSource(
                new org.postgresql.Driver(), fresh.getJdbcUrl(), fresh.getUsername(), fresh.getPassword()));
            var flyway = Flyway.configure().dataSource(fresh.getJdbcUrl(), fresh.getUsername(), fresh.getPassword())
                .schemas("platform").locations("classpath:db/migration/platform");
            flyway.target("8").load().migrate();
            var vault = new JdbcVaultRepository(client).create("Bestand").id();
            // Mit dem Schema von damals (V8), nicht mit dem heutigen Repository.
            for (var path : java.util.List.of("Studium/Mathe/Analysis.md", "Wurzel.md")) {
                client.sql("INSERT INTO platform.notes (vault_id, path, note_level, created_by) VALUES (:vault, :path, 1, 'tom')")
                    .param("vault", vault.value()).param("path", path).update();
            }

            flyway.target("latest").load().migrate();

            assertThat(new JdbcFolderRepository(client).list(vault)).containsExactly("Studium", "Studium/Mathe");
        } finally {
            fresh.stop();
        }
    }
}
