package de.tstieh.stoneintelligence.platform.comment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.audit.AuditRecorder;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.FakePeopleDirectory;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.invitation.FakeUserDirectory;
import de.tstieh.stoneintelligence.platform.invitation.MailDeliveryException;
import de.tstieh.stoneintelligence.platform.invitation.Mailer;
import de.tstieh.stoneintelligence.platform.invitation.OutgoingMail;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.FakeVaultRepository;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentServiceTest {

    private record Recorded(NoteId noteId, String actor, String action, Map<String, Object> payload) {
    }

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final FakeCommentRepository comments = new FakeCommentRepository();
    private final FakePeopleDirectory people = new FakePeopleDirectory();
    private final FakeVaultRepository vaults = new FakeVaultRepository();
    private final FakeUserDirectory directory = new FakeUserDirectory();
    private final List<Recorded> audit = new ArrayList<>();
    private final List<OutgoingMail> sent = new ArrayList<>();
    private boolean mailConfigured = true;
    private boolean mailFails = false;
    private final Mailer mailer = new Mailer() {
        @Override
        public void send(OutgoingMail mail) {
            if (mailFails) {
                throw new MailDeliveryException("SMTP nicht erreichbar", new RuntimeException());
            }
            sent.add(mail);
        }

        @Override
        public boolean isConfigured() {
            return mailConfigured;
        }
    };
    private final AuditRecorder recorder = (vault, note, actor, action, payload) -> audit.add(new Recorded(note, actor, action, payload));

    private VaultId vaultId;
    private Note note;
    private CommentService service;

    @BeforeEach
    void setUp() {
        vaultId = vaults.create("Team").id();
        note = notes.create(vaultId, "Projekte/Plan.md", NoteLevel.of(1), "anna");
        service = new CommentService(comments, notes, new VaultAccessGuard(authorization, grants), people, vaults, recorder,
            directory, mailer, "https://kb.example");
        member("anna", Permission.READ, Permission.WRITE, Permission.MANAGE);
        member("ben", Permission.READ);
        member("cara", Permission.READ, Permission.WRITE);
        people.remember("anna", "Anna Adler", Instant.now());
        people.remember("ben", "Ben Berg", Instant.now());
        directory.with("ben", "Ben Berg", "ben@example.org").with("cara", "Cara Cohn", "cara@example.org");
    }

    private void member(String subject, Permission... permissions) {
        var role = authorization.createRole(vaultId, "rolle-" + subject, Set.of(permissions));
        var group = authorization.createGroup(vaultId, "gruppe-" + subject);
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), subject);
    }

    private void hideNoteFrom(String subject) {
        grants.put(vaultId, GrantTarget.entry(note.id(), note.path()), GrantScope.user(subject), Set.of(), "anna");
    }

    private static String mention(String name, String subject) {
        return "@[" + name + "](" + subject + ")";
    }

    @Test
    void should_letAReaderStartAThread_becauseCommentingNeedsOnlyRead() {
        var thread = service.startThread(vaultId, note.id(), "ben", "Passt das so?", new Anchor("Plan", "der ", " steht"));

        assertThat(thread.comments()).singleElement().satisfies(first -> {
            assertThat(first.body()).isEqualTo("Passt das so?");
            assertThat(first.authorName()).isEqualTo("Ben Berg");
            assertThat(first.mine()).isTrue();
        });
        assertThat(thread.anchor()).isEqualTo(new Anchor("Plan", "der ", " steht"));
        assertThat(service.list(vaultId, note.id(), "anna")).hasSize(1);
        assertThat(service.list(vaultId, note.id(), "anna").getFirst().comments().getFirst().mine()).isFalse();
    }

    @Test
    void should_refuseEverything_toSomeoneWhoMayNotReadTheNote() {
        var thread = service.startThread(vaultId, note.id(), "anna", "intern", null);
        var commentId = UUID.fromString(thread.comments().getFirst().id());
        hideNoteFrom("ben");

        assertThatThrownBy(() -> service.list(vaultId, note.id(), "ben")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.startThread(vaultId, note.id(), "ben", "hi", null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.reply(vaultId, UUID.fromString(thread.id()), "ben", "hi")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.edit(vaultId, commentId, "ben", "hi")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(vaultId, commentId, "ben")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.resolve(vaultId, UUID.fromString(thread.id()), "ben")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.list(vaultId, note.id(), "fremd")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_notFindAThread_ofAnotherVault() {
        var thread = service.startThread(vaultId, note.id(), "anna", "x", null);
        var other = vaults.create("Anderer").id();
        var role = authorization.createRole(other, "alles", Set.of(Permission.READ, Permission.WRITE));
        var group = authorization.createGroup(other, "alle");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "anna");

        assertThatThrownBy(() -> service.reply(other, UUID.fromString(thread.id()), "anna", "y")).isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    void should_rejectEmptyOrOversizedTextAndBrokenAnchors() {
        assertThatThrownBy(() -> service.startThread(vaultId, note.id(), "ben", "  ", null)).isInstanceOf(CommentRejectedException.class);
        assertThatThrownBy(() -> service.startThread(vaultId, note.id(), "ben", "x".repeat(4001), null)).isInstanceOf(CommentRejectedException.class);
        assertThatThrownBy(() -> service.startThread(vaultId, note.id(), "ben", "x", new Anchor(" ", "", ""))).isInstanceOf(CommentRejectedException.class);
        assertThatThrownBy(() -> service.startThread(vaultId, note.id(), "ben", "x", new Anchor("y".repeat(1001), "", ""))).isInstanceOf(CommentRejectedException.class);
        assertThat(service.list(vaultId, note.id(), "ben")).isEmpty();
    }

    @Test
    void should_notCommentOnFiles() {
        var file = notes.create(vaultId, "bild.png", NoteLevel.of(1), "anna", NoteKind.FILE);

        assertThatThrownBy(() -> service.startThread(vaultId, file.id(), "anna", "x", null)).isInstanceOf(CommentRejectedException.class);
    }

    @Test
    void should_notifyAndMailAMentionedReader() {
        service.startThread(vaultId, note.id(), "anna", "Hallo " + mention("Ben Berg", "ben") + ", schau mal", null);

        var inbox = service.notifications("ben");
        assertThat(inbox.unread()).isEqualTo(1);
        assertThat(inbox.items()).singleElement().satisfies(item -> {
            assertThat(item.notePath()).isEqualTo("Projekte/Plan.md");
            assertThat(item.actorName()).isEqualTo("Anna Adler");
            assertThat(item.excerpt()).isEqualTo("Hallo @Ben Berg, schau mal");
            assertThat(item.vaultName()).isEqualTo("Team");
        });
        assertThat(sent).singleElement().satisfies(mail -> {
            assertThat(mail.to()).isEqualTo("ben@example.org");
            assertThat(mail.subject()).contains("Anna Adler").contains("Plan");
            assertThat(mail.text()).contains("Hallo @Ben Berg, schau mal").contains("https://kb.example");
        });
    }

    @Test
    void should_notSendMail_whenMailIsNotConfigured_butStillNotify() {
        mailConfigured = false;

        service.startThread(vaultId, note.id(), "anna", mention("Ben Berg", "ben"), null);

        assertThat(sent).isEmpty();
        assertThat(service.notifications("ben").unread()).isEqualTo(1);
    }

    @Test
    void should_keepTheComment_whenTheMailCannotBeDelivered() {
        mailFails = true;

        service.startThread(vaultId, note.id(), "anna", mention("Ben Berg", "ben"), null);

        assertThat(service.list(vaultId, note.id(), "anna")).hasSize(1);
        assertThat(service.notifications("ben").unread()).isEqualTo(1);
    }

    @Test
    void should_refuseAMention_ofSomeoneWhoMayNotReadTheNote_andSaveNothing() {
        hideNoteFrom("ben");

        assertThatThrownBy(() -> service.startThread(vaultId, note.id(), "anna", "Hi " + mention("Ben Berg", "ben"), null))
            .isInstanceOf(CommentRejectedException.class)
            .hasMessageContaining("Ben Berg").hasMessageContaining("darf diese Notiz nicht lesen");

        assertThat(service.list(vaultId, note.id(), "anna")).isEmpty();
        assertThat(sent).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void should_refuseAMention_ofSomeoneOutsideTheVault() {
        assertThatThrownBy(() -> service.startThread(vaultId, note.id(), "anna", mention("Eva Extern", "eva"), null))
            .isInstanceOf(CommentRejectedException.class).hasMessageContaining("Eva Extern").hasMessageContaining("kein Mitglied");
    }

    @Test
    void should_notNotifyYourself_andNotifyEachPersonOnce() {
        service.startThread(vaultId, note.id(), "anna",
            mention("Anna Adler", "anna") + " " + mention("Ben Berg", "ben") + " " + mention("Ben Berg", "ben"), null);

        assertThat(service.notifications("anna").items()).isEmpty();
        assertThat(service.notifications("ben").items()).hasSize(1);
        assertThat(sent).hasSize(1);
    }

    @Test
    void should_notifyOnReplies() {
        var thread = service.startThread(vaultId, note.id(), "anna", "Frage", null);

        var updated = service.reply(vaultId, UUID.fromString(thread.id()), "cara", "Antwort " + mention("Ben Berg", "ben"));

        assertThat(updated.comments()).extracting(CommentService.CommentView::body).containsExactly("Frage", "Antwort " + mention("Ben Berg", "ben"));
        assertThat(service.notifications("ben").items()).hasSize(1);
    }

    @Test
    void should_letOnlyTheAuthorEdit_andNotifyOnlyNewMentions() {
        var thread = service.startThread(vaultId, note.id(), "cara", "Hi " + mention("Ben Berg", "ben"), null);
        var commentId = UUID.fromString(thread.comments().getFirst().id());

        assertThatThrownBy(() -> service.edit(vaultId, commentId, "anna", "gekapert")).isInstanceOf(ForbiddenException.class);

        var edited = service.edit(vaultId, commentId, "cara", "Hi " + mention("Ben Berg", "ben") + " und " + mention("Anna Adler", "anna"));

        assertThat(edited.comments().getFirst().editedAt()).isNotNull();
        assertThat(service.notifications("ben").items()).hasSize(1);
        assertThat(service.notifications("anna").items()).hasSize(1);
    }

    @Test
    void should_letTheAuthorOrAManagerDelete_butNotOtherReaders() {
        var thread = service.startThread(vaultId, note.id(), "cara", "von Cara", null);
        var first = UUID.fromString(thread.comments().getFirst().id());
        var reply = service.reply(vaultId, UUID.fromString(thread.id()), "ben", "von Ben").comments().get(1);

        assertThatThrownBy(() -> service.delete(vaultId, first, "ben")).isInstanceOf(ForbiddenException.class);
        service.delete(vaultId, UUID.fromString(reply.id()), "ben");
        service.delete(vaultId, first, "anna");

        assertThat(service.list(vaultId, note.id(), "anna")).isEmpty();
    }

    @Test
    void should_resolveAndReopen_byAnyReader() {
        var thread = service.startThread(vaultId, note.id(), "cara", "offen?", null);

        var resolved = service.resolve(vaultId, UUID.fromString(thread.id()), "ben");
        assertThat(resolved.resolved()).isTrue();
        assertThat(resolved.resolvedByName()).isEqualTo("Ben Berg");

        assertThat(service.reopen(vaultId, UUID.fromString(thread.id()), "anna").resolved()).isFalse();
    }

    @Test
    void should_writeAuditEvents_withoutTheCommentText() {
        var thread = service.startThread(vaultId, note.id(), "cara", "streng geheimer Text", null);
        var id = UUID.fromString(thread.id());
        var comment = service.reply(vaultId, id, "ben", "Antwort").comments().get(1);
        service.resolve(vaultId, id, "ben");
        service.reopen(vaultId, id, "ben");
        service.delete(vaultId, UUID.fromString(comment.id()), "ben");

        assertThat(audit).extracting(Recorded::action)
            .containsExactly("comment.created", "comment.created", "comment.resolved", "comment.reopened", "comment.deleted");
        assertThat(audit).allSatisfy(event -> {
            assertThat(event.noteId()).isEqualTo(note.id());
            assertThat(event.payload().values().toString()).doesNotContain("geheim").doesNotContain("Antwort");
            assertThat(event.payload()).containsKey("threadId");
        });
        assertThat(audit.getFirst().actor()).isEqualTo("cara");
    }

    @Test
    void should_hideNotifications_fromSomeoneWhoLostReadAccess_andMarkTheRestRead() {
        service.startThread(vaultId, note.id(), "anna", mention("Ben Berg", "ben"), null);
        var other = notes.create(vaultId, "Offen.md", NoteLevel.of(1), "anna");
        service.startThread(vaultId, other.id(), "anna", mention("Ben Berg", "ben") + " zweite", null);
        hideNoteFrom("ben");

        var inbox = service.notifications("ben");

        assertThat(inbox.items()).singleElement().satisfies(item -> assertThat(item.notePath()).isEqualTo("Offen.md"));
        assertThat(inbox.unread()).isEqualTo(1);

        service.markRead("ben", List.of());

        assertThat(service.notifications("ben").unread()).isZero();
        assertThat(service.notifications("ben").items()).singleElement().satisfies(item -> assertThat(item.read()).isTrue());
    }
}
