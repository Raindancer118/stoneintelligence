package de.tstieh.stoneintelligence.platform.vault;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.files.FakeFileVersionRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.sync.relay.FakeSnapshotStore;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRoomRegistry;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultSubscriber;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class NoteControllerTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final FakeSnapshotStore snapshots = new FakeSnapshotStore();
    private final NoteController controller = new NoteController(notes,
        new SyncRelayService(snapshots, new SyncRoomRegistry()), (vault, note, actor, action, payload) -> { },
        new VaultAccessGuard(authorization, grants), announcements, snapshots,
        new FolderRegistry(new FakeFolderRepository(), announcements, grants), new FakeFileVersionRepository(notes));
    private final VaultId vaultId = VaultId.newId();
    private final AtomicInteger accessChecks = new AtomicInteger();

    NoteControllerTest() {
        var role = authorization.createRole(vaultId, "alles", EnumSet.allOf(Permission.class));
        var group = authorization.createGroup(vaultId, "alle");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "tom");
        announcements.subscribe(vaultId, new VaultSubscriber() {
            @Override public String id() { return "geraet"; }
            @Override public boolean mayRead(String path) { return true; }
            @Override public void sendVaultEvent(byte messageType, NoteId noteId, String path) { }
            @Override public void accessChanged() { accessChecks.incrementAndGet(); }
        });
    }

    private NoteController.NoteResponse rename(String from, String to) {
        var note = notes.create(vaultId, from, NoteLevel.of(1), "tom");
        return controller.rename(vaultId.value().toString(), note.id().value().toString(),
            new NoteController.RenameNoteRequest(to), new TestingAuthenticationToken("tom", null));
    }

    // Wer die Notiz offen hat, darf sie nach dem Verschieben in einen gesperrten Ordner nicht weiter live bekommen.
    @Test
    void should_recheckOpenConnections_whenANoteMovesToAnotherFolder() {
        rename("Offen/A.md", "Privat/A.md");

        assertThat(accessChecks).hasValue(1);
    }

    @Test
    void should_notRecheckConnections_whenANoteIsOnlyRenamedInPlace() {
        rename("Offen/A.md", "Offen/B.md");

        assertThat(accessChecks).hasValue(0);
    }

    @Test
    void should_capThePageSize_whenListingTheWholeVault() {
        for (var i = 0; i < 1001; i++) {
            notes.create(vaultId, "N" + i + ".md", NoteLevel.of(1), "tom");
        }

        var page = controller.reconcile(vaultId.value().toString(), null, 100_000, "note", null, null,
            new TestingAuthenticationToken("tom", null));

        assertThat(page.notes()).hasSize(1000);
        assertThat(page.complete()).isFalse();
    }

    @Test
    void should_returnAtLeastOneEntry_whenThePageSizeIsNotPositive() {
        notes.create(vaultId, "A.md", NoteLevel.of(1), "tom");
        notes.create(vaultId, "B.md", NoteLevel.of(1), "tom");

        var page = controller.reconcile(vaultId.value().toString(), null, -3, "note", null, null,
            new TestingAuthenticationToken("tom", null));

        assertThat(page.notes()).hasSize(1);
    }
}
