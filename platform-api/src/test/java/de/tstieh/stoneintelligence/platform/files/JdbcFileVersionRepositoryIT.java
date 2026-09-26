package de.tstieh.stoneintelligence.platform.files;

import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.vault.JdbcNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Derselbe Vertrag wie {@link FakeFileVersionRepositoryTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcFileVersionRepositoryIT extends FileVersionRepositoryContractTest {

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
    protected FileVersionRepository repository() {
        jdbcClient.sql("TRUNCATE platform.file_versions").update();
        return new JdbcFileVersionRepository(jdbcClient);
    }

    @Override
    protected NoteId newFile(VaultId vaultId) {
        return new JdbcNoteRepository(jdbcClient).create(vaultId, java.util.UUID.randomUUID() + ".pdf", NoteLevel.of(1), "tom",
            NoteKind.FILE).id();
    }

    @Override
    protected VaultId newVault() {
        return new JdbcVaultRepository(jdbcClient).create("Datei-Test").id();
    }

    @Override
    protected void deleteFile(VaultId vaultId, NoteId noteId) {
        new JdbcNoteRepository(jdbcClient).delete(vaultId, noteId, java.util.UUID.randomUUID().toString(), "tom");
    }
}
