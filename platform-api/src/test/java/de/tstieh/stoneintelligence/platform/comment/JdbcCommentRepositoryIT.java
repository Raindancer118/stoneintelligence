package de.tstieh.stoneintelligence.platform.comment;

import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.vault.JdbcNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Derselbe Vertrag wie {@link FakeCommentRepositoryTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcCommentRepositoryIT extends CommentRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private static JdbcClient jdbcClient;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform").locations("classpath:db/migration/platform").load().migrate();
        jdbcClient = JdbcClient.create(new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    // Alle Tests teilen sich einen Container; Abfragen ueber Empfaenger/Alter saehen sonst fremde Zeilen.
    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        jdbcClient.sql("TRUNCATE platform.comment_notifications, platform.comments, platform.comment_threads CASCADE").update();
    }

    @Override
    protected CommentRepository repository() {
        return new JdbcCommentRepository(jdbcClient);
    }

    @Override
    protected Target newNote() {
        var vault = new JdbcVaultRepository(jdbcClient).create("Kommentar-Test").id();
        var note = new JdbcNoteRepository(jdbcClient).create(vault, "Notiz.md", NoteLevel.of(1), "tom");
        return new Target(vault, note.id());
    }

    @Test
    void should_dropAllComments_whenTheNoteIsDeleted() {
        var target = newNote();
        var comments = repository();
        var thread = comments.createThread(target.vault(), target.note(), null, "anna", "x");
        comments.addNotification(target.vault(), target.note(), thread.id(), thread.comments().getFirst().id(), "ben", "anna");

        jdbcClient.sql("DELETE FROM platform.notes WHERE id = :id").param("id", target.note().value()).update();

        assertThat(comments.findThread(target.vault(), thread.id())).isEmpty();
        assertThat(comments.notificationsFor("ben", 10)).isEmpty();
    }
}
