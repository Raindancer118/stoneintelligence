package de.tstieh.stoneintelligence.platform.sync.relay;

import de.tstieh.stoneintelligence.domain.id.NoteId;
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

/** Derselbe Vertrag wie {@link FakeSnapshotStoreTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcSnapshotStoreIT extends SnapshotStoreContractTest {

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
    protected SnapshotStore store() {
        return new JdbcSnapshotStore(jdbcClient);
    }

    @Override
    protected NoteId newNote() {
        var vault = new JdbcVaultRepository(jdbcClient).create("Versionen").id();
        return new JdbcNoteRepository(jdbcClient).create(vault, "n-" + NoteId.newId().value() + ".md", NoteLevel.of(1), "tom", NoteKind.NOTE).id();
    }
}
