package de.tstieh.stoneintelligence.platform.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryControllerTest {

    /** Audit im Speicher - Schreib- und Leseweg wie {@code AuditService}. */
    static final class FakeAudit implements AuditRecorder, AuditReader {
        final List<AuditEvent> events = new ArrayList<>();
        private Instant clock = Instant.parse("2026-09-26T08:00:00Z");

        @Override
        public void record(VaultId vaultId, NoteId noteId, String actor, String action, Map<String, Object> payload) {
            clock = clock.plusSeconds(60);
            events.add(new AuditEvent(UUID.randomUUID(), vaultId, noteId, actor, action, payload, clock));
        }

        @Override
        public List<AuditEvent> listForNote(VaultId vaultId, NoteId noteId) {
            return events.stream().filter(e -> e.vaultId().equals(vaultId) && noteId.equals(e.noteId())).toList();
        }

        @Override
        public List<AuditEvent> listRecent(VaultId vaultId, int limit) {
            return events.reversed().stream().filter(e -> e.vaultId().equals(vaultId)).limit(limit).toList();
        }
    }

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final FakeAudit audit = new FakeAudit();
    private final HistoryController controller = new HistoryController(new VaultAccessGuard(authorization, grants), notes, audit);
    private final VaultId vaultId = VaultId.newId();
    private Note plan;
    private Note secret;

    @BeforeEach
    void setUp() {
        member("tom", Set.of(Permission.READ, Permission.WRITE, Permission.MANAGE));
        member("ben", Set.of(Permission.READ));
        plan = notes.create(vaultId, "Team/plan.md", NoteLevel.of(1), "tom");
        secret = notes.create(vaultId, "Privat/geheim.md", NoteLevel.of(1), "tom");
        grants.put(vaultId, GrantTarget.folder("Privat"), GrantScope.user("ben"), Set.of(), "tom");
        audit.record(vaultId, plan.id(), "tom", "note.created", Map.of("path", plan.path()));
        audit.record(vaultId, secret.id(), "tom", "note.created", Map.of("path", secret.path()));
        audit.record(vaultId, plan.id(), "ben", "note.content-updated", Map.of("revision", 3));
        audit.record(vaultId, secret.id(), "tom", "ACCESS_GRANTED", Map.of("target", "folder", "path", "Privat", "subject", "ben"));
        audit.record(vaultId, null, "tom", "MEMBER_ADDED", Map.of("subject", "ben"));
        notes.markOpened(vaultId, plan.id(), "ben", Instant.parse("2026-09-26T09:00:00Z"));
    }

    private void member(String subject, Set<Permission> permissions) {
        var role = authorization.createRole(vaultId, subject, permissions);
        var group = authorization.createGroup(vaultId, subject);
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), subject);
    }

    private String vault() {
        return vaultId.value().toString();
    }

    @Test
    void should_showWhoCreatedEditedAndOpenedANote_withItsEvents() {
        var history = controller.noteHistory(vault(), plan.id().value().toString(), new TestingAuthenticationToken("ben", null));

        assertThat(history.activity().createdBy()).isEqualTo("tom");
        assertThat(history.activity().lastOpenedBy()).isEqualTo("ben");
        assertThat(history.events()).extracting(HistoryController.EventResponse::action)
            .containsExactly("note.created", "note.content-updated");
    }

    @Test
    void should_refuseTheHistoryOfANoteSomeoneMayNotRead() {
        assertThatThrownBy(() -> controller.noteHistory(vault(), secret.id().value().toString(), new TestingAuthenticationToken("ben", null)))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_showEachPersonOnlyTheLogEntriesTheyMaySee() {
        var forBen = controller.vaultLog(vault(), "", 100, new TestingAuthenticationToken("ben", null));
        var forTom = controller.vaultLog(vault(), "", 100, new TestingAuthenticationToken("tom", null));

        assertThat(forBen).extracting(HistoryController.EventResponse::action).containsExactly("note.content-updated", "note.created");
        assertThat(forTom).extracting(HistoryController.EventResponse::action)
            .containsExactly("MEMBER_ADDED", "ACCESS_GRANTED", "note.content-updated", "note.created", "note.created");
        assertThat(forBen).allSatisfy(event -> assertThat(event.path()).isEqualTo("Team/plan.md"));
    }

    @Test
    void should_narrowTheLogToAFolder() {
        var privat = controller.vaultLog(vault(), "Privat", 100, new TestingAuthenticationToken("tom", null));

        assertThat(privat).extracting(HistoryController.EventResponse::action).containsExactly("ACCESS_GRANTED", "note.created");
        assertThatThrownBy(() -> controller.vaultLog(vault(), "", 100, new TestingAuthenticationToken("mallory", null)))
            .isInstanceOf(ForbiddenException.class);
    }

    // Der Audit-Trail je Datei ueberlebt ihre Loeschung - aber nur fuer, wer ihren letzten Ort lesen durfte.
    @Test
    void should_keepTheHistoryOfADeletedNote_forWhoCouldReadIt() {
        notes.delete(vaultId, plan.id(), "op-1", "tom");
        audit.record(vaultId, plan.id(), "tom", "note.deleted", Map.of("operationId", "op-1"));
        notes.delete(vaultId, secret.id(), "op-2", "tom");

        var history = controller.noteHistory(vault(), plan.id().value().toString(), new TestingAuthenticationToken("ben", null));

        assertThat(history.activity()).isNull();
        assertThat(history.events()).extracting(HistoryController.EventResponse::action)
            .containsExactly("note.created", "note.content-updated", "note.deleted");
        assertThat(controller.noteHistory(vault(), secret.id().value().toString(), new TestingAuthenticationToken("ben", null)).events())
            .isEmpty();
    }
}
