package de.tstieh.stoneintelligence.platform.comment;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemeinsamer Vertrag für Fake- und Postgres-Implementierung. */
public abstract class CommentRepositoryContractTest {

    protected record Target(VaultId vault, NoteId note) {
    }

    protected abstract CommentRepository repository();

    /** Liefert eine existierende Notiz (Postgres braucht die Fremdschlüssel-Zeilen). */
    protected abstract Target newNote();

    private CommentRepository comments;
    private Target target;

    @BeforeEach
    void setUp() {
        comments = repository();
        target = newNote();
    }

    private CommentThread thread(String author, String body) {
        return comments.createThread(target.vault(), target.note(), null, author, body);
    }

    @Test
    void should_createAThreadWithItsFirstComment_andKeepTheAnchor() {
        var created = comments.createThread(target.vault(), target.note(), new Anchor("ein Satz", "davor ", " danach"), "anna", "Stimmt das?");

        assertThat(created.anchor()).isEqualTo(new Anchor("ein Satz", "davor ", " danach"));
        assertThat(created.createdBy()).isEqualTo("anna");
        assertThat(created.resolved()).isFalse();
        assertThat(created.comments()).singleElement().satisfies(first -> {
            assertThat(first.author()).isEqualTo("anna");
            assertThat(first.body()).isEqualTo("Stimmt das?");
            assertThat(first.editedAt()).isNull();
        });
        assertThat(comments.findThread(target.vault(), created.id())).get().usingRecursiveComparison().isEqualTo(created);
    }

    @Test
    void should_storeAThreadWithoutAnchor_asACommentOnTheWholeNote() {
        assertThat(thread("anna", "Allgemein").anchor()).isNull();
    }

    @Test
    void should_listThreadsOfOneNoteOnly_oldestFirst_withRepliesInOrder() {
        var first = thread("anna", "eins");
        var second = thread("ben", "zwei");
        comments.addReply(target.vault(), first.id(), "ben", "Antwort A");
        comments.addReply(target.vault(), first.id(), "anna", "Antwort B");
        var other = newNote();
        comments.createThread(other.vault(), other.note(), null, "anna", "andere Notiz");

        var threads = comments.threadsOf(target.vault(), target.note());

        assertThat(threads).extracting(CommentThread::id).containsExactly(first.id(), second.id());
        assertThat(threads.getFirst().comments()).extracting(Comment::body).containsExactly("eins", "Antwort A", "Antwort B");
    }

    @Test
    void should_notFindAThread_fromAnotherVault() {
        var created = thread("anna", "geheim");
        var foreign = VaultId.newId();

        assertThat(comments.findThread(foreign, created.id())).isEmpty();
        assertThat(comments.addReply(foreign, created.id(), "eve", "x")).isEmpty();
        assertThat(comments.setResolved(foreign, created.id(), "eve")).isEmpty();
        assertThat(comments.findThreadOfComment(foreign, created.comments().getFirst().id())).isEmpty();
        assertThat(comments.editComment(foreign, created.comments().getFirst().id(), "x")).isEmpty();
        assertThat(comments.deleteComment(foreign, created.comments().getFirst().id())).isFalse();
        assertThat(comments.findThread(target.vault(), created.id())).isPresent();
    }

    @Test
    void should_editABody_andMarkItEdited() {
        var created = thread("anna", "alt");

        var edited = comments.editComment(target.vault(), created.comments().getFirst().id(), "neu");

        assertThat(edited).get().satisfies(comment -> {
            assertThat(comment.body()).isEqualTo("neu");
            assertThat(comment.editedAt()).isNotNull();
        });
    }

    @Test
    void should_resolveAndReopenAThread() {
        var created = thread("anna", "x");

        var resolved = comments.setResolved(target.vault(), created.id(), "ben").orElseThrow();
        assertThat(resolved.resolved()).isTrue();
        assertThat(resolved.resolvedBy()).isEqualTo("ben");

        var reopened = comments.setResolved(target.vault(), created.id(), null).orElseThrow();
        assertThat(reopened.resolved()).isFalse();
        assertThat(reopened.resolvedBy()).isNull();
    }

    @Test
    void should_findTheThreadOfAComment() {
        var created = thread("anna", "x");
        var reply = comments.addReply(target.vault(), created.id(), "ben", "y").orElseThrow();

        assertThat(comments.findThreadOfComment(target.vault(), reply.id())).get().extracting(CommentThread::id).isEqualTo(created.id());
    }

    @Test
    void should_deleteAReply_butKeepTheThread() {
        var created = thread("anna", "x");
        var reply = comments.addReply(target.vault(), created.id(), "ben", "y").orElseThrow();

        assertThat(comments.deleteComment(target.vault(), reply.id())).isTrue();

        assertThat(comments.findThread(target.vault(), created.id()).orElseThrow().comments()).extracting(Comment::body).containsExactly("x");
        assertThat(comments.deleteComment(target.vault(), reply.id())).isFalse();
    }

    @Test
    void should_removeTheThread_whenItsLastCommentIsDeleted() {
        var created = thread("anna", "x");

        assertThat(comments.deleteComment(target.vault(), created.comments().getFirst().id())).isTrue();

        assertThat(comments.findThread(target.vault(), created.id())).isEmpty();
        assertThat(comments.threadsOf(target.vault(), target.note())).isEmpty();
    }

    @Test
    void should_listNotificationsNewestFirst_perRecipient_andMarkThemRead() {
        var created = thread("anna", "@ben");
        var reply = comments.addReply(target.vault(), created.id(), "anna", "@ben nochmal").orElseThrow();
        comments.addNotification(target.vault(), target.note(), created.id(), created.comments().getFirst().id(), "ben", "anna");
        comments.addNotification(target.vault(), target.note(), created.id(), reply.id(), "ben", "anna");
        comments.addNotification(target.vault(), target.note(), created.id(), reply.id(), "cara", "anna");

        var forBen = comments.notificationsFor("ben", 10);

        assertThat(forBen).extracting(CommentNotification::commentId).containsExactly(reply.id(), created.comments().getFirst().id());
        assertThat(forBen).allSatisfy(item -> {
            assertThat(item.readAt()).isNull();
            assertThat(item.actor()).isEqualTo("anna");
            assertThat(item.noteId()).isEqualTo(target.note());
        });
        assertThat(comments.notificationsFor("ben", 1)).hasSize(1);

        assertThat(comments.markRead("ben", Set.of(forBen.getFirst().id()))).isEqualTo(1);
        assertThat(comments.markRead("ben", Set.of(forBen.getFirst().id()))).isZero();
        assertThat(comments.notificationsFor("ben", 10)).extracting(item -> item.readAt() != null).containsExactly(true, false);
        assertThat(comments.markRead("ben", List.<UUID>of())).isEqualTo(1);
        assertThat(comments.notificationsFor("cara", 10)).singleElement().extracting(CommentNotification::readAt).isNull();
    }

    @Test
    void should_notMarkSomeoneElsesNotificationsRead() {
        var created = thread("anna", "@ben");
        comments.addNotification(target.vault(), target.note(), created.id(), created.comments().getFirst().id(), "ben", "anna");
        var id = comments.notificationsFor("ben", 10).getFirst().id();

        assertThat(comments.markRead("eve", Set.of(id))).isZero();
        assertThat(comments.notificationsFor("ben", 10).getFirst().readAt()).isNull();
    }

    @Test
    void should_dropTheNotifications_ofADeletedComment() {
        var created = thread("anna", "@ben");
        var reply = comments.addReply(target.vault(), created.id(), "anna", "@ben").orElseThrow();
        comments.addNotification(target.vault(), target.note(), created.id(), reply.id(), "ben", "anna");

        comments.deleteComment(target.vault(), reply.id());

        assertThat(comments.notificationsFor("ben", 10)).isEmpty();
    }
}
