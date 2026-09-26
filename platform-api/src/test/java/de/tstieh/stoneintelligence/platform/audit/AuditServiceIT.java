package de.tstieh.stoneintelligence.platform.audit;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
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

@Testcontainers
class AuditServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static AuditService auditService;

    @BeforeAll
    static void migrateAndBuildService() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform")
            .locations("classpath:db/migration/platform")
            .load()
            .migrate();

        DataSource dataSource = new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        auditService = new AuditService(JdbcClient.create(dataSource));
    }

    @BeforeEach
    void cleanTable() {
        DataSource dataSource = new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcClient.create(dataSource).sql("TRUNCATE platform.audit_events").update();
    }

    @Test
    void should_recordAndListEvent_withPayload() {
        var vaultId = VaultId.newId();
        var noteId = NoteId.newId();

        auditService.record(vaultId, noteId, "tom", "note.created", Map.of("path", "foo.md"));

        List<AuditEvent> events = auditService.listForNote(vaultId, noteId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).actor()).isEqualTo("tom");
        assertThat(events.get(0).action()).isEqualTo("note.created");
        assertThat(events.get(0).payload()).containsEntry("path", "foo.md");
    }

    @Test
    void should_orderEventsChronologically_when_multipleRecorded() {
        var vaultId = VaultId.newId();
        var noteId = NoteId.newId();

        auditService.record(vaultId, noteId, "tom", "note.created", Map.of());
        auditService.record(vaultId, noteId, "tom", "note.renamed", Map.of("newPath", "bar.md"));

        List<AuditEvent> events = auditService.listForNote(vaultId, noteId);
        assertThat(events).extracting(AuditEvent::action).containsExactly("note.created", "note.renamed");
    }

    @Test
    void should_notLeakEventsFromOtherNotes_when_listing() {
        var vaultId = VaultId.newId();
        var noteId = NoteId.newId();
        var otherNoteId = NoteId.newId();

        auditService.record(vaultId, otherNoteId, "tom", "note.created", Map.of());

        assertThat(auditService.listForNote(vaultId, noteId)).isEmpty();
    }

    @Test
    void should_listTheMostRecentEventsOfAVault_newestFirst() {
        var vaultId = VaultId.newId();
        for (int i = 0; i < 5; i++) {
            auditService.record(vaultId, null, "tom", "EVENT_" + i, Map.of());
        }
        auditService.record(VaultId.newId(), null, "eve", "OTHER", Map.of());

        var recent = auditService.listRecent(vaultId, 3);

        assertThat(recent).extracting(AuditEvent::action).hasSize(3).doesNotContain("OTHER", "EVENT_0", "EVENT_1");
    }
}
