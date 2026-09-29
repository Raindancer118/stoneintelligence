package de.tstieh.stoneintelligence.platform.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.sync.relay.FakeSnapshotStore;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRoomRegistry;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncSession;
import de.tstieh.stoneintelligence.platform.sync.relay.UpdateRecord;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.sync.yjs.YjsBridge;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoteVersionControllerTest {

    private static YjsBridge yjs;

    @BeforeAll
    static void loadYjs() {
        yjs = YjsBridge.load();
    }

    @AfterAll
    static void closeYjs() {
        yjs.close();
    }

    /** Ein offenes Obsidian, das die Notiz gerade anzeigt. */
    static final class OpenEditor implements SyncSession {
        final List<byte[]> received = new ArrayList<>();

        @Override public String id() { return "editor"; }
        @Override public void sendDocUpdate(NoteId noteId, byte[] payload) { received.add(payload); }
        @Override public void sendAwarenessUpdate(NoteId noteId, byte[] payload) { }
        @Override public void notifyNoteDeleted(NoteId noteId) { }
        @Override public void sendCatchupComplete(NoteId noteId) { }
        @Override public void close(int code, String reason) { }
    }

    private static org.springframework.beans.factory.ObjectProvider<YjsBridge> provider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public YjsBridge getObject() {
                return yjs;
            }
        };
    }

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final FakeSnapshotStore snapshots = new FakeSnapshotStore();
    private final SyncRelayService relay = new SyncRelayService(snapshots, new SyncRoomRegistry());
    private final List<String> audit = new ArrayList<>();
    private final NoteVersionController controller = new NoteVersionController(notes, snapshots, relay, provider(),
        new VaultAccessGuard(authorization, grants), (vault, note, actor, action, payload) -> audit.add(actor + " " + action + " " + payload.get("restoredRevision")),
        new VaultAnnouncementService());
    private final VaultId vaultId = VaultId.newId();
    private Note plan;

    @BeforeEach
    void setUp() {
        member("tom", Set.of(Permission.READ, Permission.WRITE, Permission.MANAGE));
        member("anna", Set.of(Permission.READ, Permission.WRITE));
        member("ben", Set.of(Permission.READ));
        plan = notes.create(vaultId, "Team/plan.md", NoteLevel.of(1), "tom");
        write(plan, "tom", "# Plan\n\nErste Fassung\n");
        write(plan, "anna", "# Plan\n\nZweite Fassung von Anna\n");
    }

    private void member(String subject, Set<Permission> permissions) {
        var role = authorization.createRole(vaultId, subject, permissions);
        var group = authorization.createGroup(vaultId, subject);
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), subject);
    }

    private void write(Note note, String actor, String text) {
        var history = snapshots.listSince(note.id(), 0);
        var update = yjs.change(history.stream().map(UpdateRecord::payload).toList(), text).orElseThrow();
        relay.saveIfCurrent(note.id(), history.size(), update, actor).orElseThrow();
    }

    private String currentText(Note note) {
        return yjs.textOf(snapshots.listSince(note.id(), 0).stream().map(UpdateRecord::payload).toList());
    }

    private static TestingAuthenticationToken as(String who) {
        return new TestingAuthenticationToken(who, null);
    }

    private String vault() {
        return vaultId.value().toString();
    }

    private String id(Note note) {
        return note.id().value().toString();
    }

    @Test
    void should_listTheVersionsOfANote_newestFirst_withTheirAuthors() {
        var versions = controller.list(vault(), id(plan), 100, as("ben"));

        assertThat(versions.currentRevision()).isEqualTo(2);
        assertThat(versions.versions()).extracting(NoteVersion::revision, NoteVersion::actor)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(2L, "anna"), org.assertj.core.groups.Tuple.tuple(1L, "tom"));
    }

    @Test
    void should_showTheTextOfAnOlderVersion_nextToTheCurrentText() {
        var shown = controller.show(vault(), id(plan), 1, as("ben"));

        assertThat(shown.revision()).isEqualTo(1);
        assertThat(shown.text()).isEqualTo("# Plan\n\nErste Fassung\n");
        assertThat(shown.current()).isEqualTo("# Plan\n\nZweite Fassung von Anna\n");
        assertThat(shown.currentRevision()).isEqualTo(2);
    }

    @Test
    void should_restoreAnOlderVersion_asANewChange_thatReachesOpenEditors() {
        var editor = new OpenEditor();
        relay.onJoin(plan.id(), editor);
        editor.received.clear();

        var restored = controller.restore(vault(), id(plan), 1, as("anna"));

        assertThat(restored.changed()).isTrue();
        assertThat(restored.revision()).isEqualTo(3);
        assertThat(currentText(plan)).isEqualTo("# Plan\n\nErste Fassung\n");
        assertThat(editor.received).hasSize(1);
        assertThat(audit).containsExactly("anna note.version-restored 1");
        assertThat(notes.activity(vaultId, plan.id()).orElseThrow().lastEditedBy()).isEqualTo("anna");
        // Nichts geht verloren: Annas Fassung bleibt als Version erhalten.
        assertThat(controller.show(vault(), id(plan), 2, as("anna")).text()).isEqualTo("# Plan\n\nZweite Fassung von Anna\n");
        assertThat(controller.list(vault(), id(plan), 100, as("anna")).versions().getFirst().actor()).isEqualTo("anna");
    }

    @Test
    void should_changeNothing_whenTheVersionIsAlreadyCurrent() {
        var restored = controller.restore(vault(), id(plan), 2, as("anna"));

        assertThat(restored.changed()).isFalse();
        assertThat(restored.revision()).isEqualTo(2);
        assertThat(audit).isEmpty();
    }

    @Test
    void should_letOnlyWritersRestore_andOnlyReadersLook() {
        var secret = notes.create(vaultId, "Privat/geheim.md", NoteLevel.of(1), "tom");
        write(secret, "tom", "geheim\n");
        grants.put(vaultId, GrantTarget.folder("Privat"), GrantScope.user("ben"), Set.of(), "tom");

        assertThatThrownBy(() -> controller.restore(vault(), id(plan), 1, as("ben"))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.list(vault(), id(secret), 100, as("ben"))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.show(vault(), id(secret), 1, as("ben"))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.list(vault(), id(plan), 100, as("fremd"))).isInstanceOf(ForbiddenException.class);
        assertThat(currentText(plan)).isEqualTo("# Plan\n\nZweite Fassung von Anna\n");
    }

    @Test
    void should_refuseRevisionsThatDoNotExist() {
        for (var revision : List.of(0L, 3L, -1L)) {
            assertThatThrownBy(() -> controller.show(vault(), id(plan), revision, as("tom")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            assertThatThrownBy(() -> controller.restore(vault(), id(plan), revision, as("tom")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Test
    void should_refuseFilesAndEncryptedNotes() {
        var file = notes.create(vaultId, "Team/bild.png", NoteLevel.of(1), "tom", NoteKind.FILE);
        var sealed = notes.create(vaultId, "Team/tresor.md", NoteLevel.of(101), "tom");

        for (var note : List.of(file, sealed)) {
            assertThatThrownBy(() -> controller.list(vault(), id(note), 100, as("tom")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        }
    }
}
