package de.tstieh.stoneintelligence.platform.ai;

import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.vault.JdbcNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Derselbe Vertrag wie {@link FakeNoteEmbeddingRepositoryTest}, gegen Postgres mit pgvector. */
@Testcontainers
class JdbcNoteEmbeddingRepositoryIT extends NoteEmbeddingRepositoryContractTest {

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
    protected NoteEmbeddingRepository repository() {
        return new JdbcNoteEmbeddingRepository(jdbcClient);
    }

    @Override
    protected VaultId newVault() {
        return new JdbcVaultRepository(jdbcClient).create("Embeddings").id();
    }

    @Override
    protected NoteId newNote(VaultId vaultId, String path) {
        return new JdbcNoteRepository(jdbcClient).create(vaultId, path, NoteLevel.of(1), "tom").id();
    }

    @Override
    protected void deleteNote(VaultId vaultId, NoteId noteId) {
        new JdbcNoteRepository(jdbcClient).delete(vaultId, noteId, UUID.randomUUID().toString(), "tom");
    }
}
