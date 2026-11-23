package de.tstieh.stoneintelligence.platform.sync.relay;

import java.nio.charset.StandardCharsets;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0013, Schritt 7: zwei platform-api-Instanzen hinter einem Load Balancer, eine gemeinsame Datenbank. */
class ClusterRelayTest {

    private final InMemoryClusterHub hub = new InMemoryClusterHub();
    private final FakeSnapshotStore database = new FakeSnapshotStore();
    private final SyncRelayService first = new SyncRelayService(database, new SyncRoomRegistry(), (n, s) -> { }, hub.join());
    private final SyncRelayService second = new SyncRelayService(database, new SyncRoomRegistry(), (n, s) -> { }, hub.join());
    private final NoteId note = NoteId.newId();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void should_deliverATypedUpdate_toPeopleConnectedToTheOtherInstance() {
        var anna = new RecordingSyncSession("anna");
        var ben = new RecordingSyncSession("ben");
        first.onJoin(note, anna);
        second.onJoin(note, ben);

        first.onUpdate(note, anna, bytes("hallo"), false, "anna");

        assertThat(ben.receivedDocUpdates).extracting(String::new).containsExactly("hallo");
        assertThat(anna.receivedDocUpdates).isEmpty();
        assertThat(database.listSince(note, 0)).hasSize(1);
    }

    @Test
    void should_deliverWebEditorSaves_toTheOtherInstance() {
        var ben = new RecordingSyncSession("ben");
        second.onJoin(note, ben);

        first.saveIfCurrent(note, 0, bytes("aus dem Browser"), "anna");

        assertThat(ben.receivedDocUpdates).extracting(String::new).containsExactly("aus dem Browser");
    }

    @Test
    void should_showCursors_acrossInstances_andKeepThemForLateJoiners() {
        var anna = new RecordingSyncSession("anna");
        first.onJoin(note, anna);
        var ben = new RecordingSyncSession("ben");
        second.onJoin(note, ben);

        first.onAwarenessUpdate(note, anna, bytes("cursor"));
        var carla = new RecordingSyncSession("carla");
        second.onJoin(note, carla);

        assertThat(ben.receivedAwarenessUpdates).extracting(String::new).containsExactly("cursor");
        assertThat(carla.receivedAwarenessUpdates).extracting(String::new).containsExactly("cursor");
    }

    @Test
    void should_forgetARemoteCursor_whenItsPersonLeaves() {
        var anna = new RecordingSyncSession("anna");
        first.onJoin(note, anna);
        first.onAwarenessUpdate(note, anna, bytes("cursor"));

        first.onLeave(note, anna);
        var carla = new RecordingSyncSession("carla");
        second.onJoin(note, carla);

        assertThat(carla.receivedAwarenessUpdates).isEmpty();
    }

    @Test
    void should_endTheRoomEverywhere_whenTheNoteIsDeleted() {
        var ben = new RecordingSyncSession("ben");
        second.onJoin(note, ben);

        first.onNoteDeleted(note);

        assertThat(ben.notifiedDeletedNoteIds).containsExactly(note);
    }

    @Test
    void should_announceVaultChanges_toDevicesOnTheOtherInstance() {
        var hubForVaults = new InMemoryClusterHub();
        var here = new VaultAnnouncementService(System::nanoTime, hubForVaults.join());
        var there = new VaultAnnouncementService(System::nanoTime, hubForVaults.join());
        var vault = VaultId.newId();
        var phone = new RecordingVaultSubscriber("phone");
        var stranger = RecordingVaultSubscriber.readingOnly("stranger", "Offen.md");
        there.subscribe(vault, phone);
        there.subscribe(vault, stranger);
        var invalidated = new java.util.ArrayList<VaultId>();
        there.addAccessListener(invalidated::add);

        here.announceNoteRenamed(vault, note, "Projekte/Plan – neu.md", NoteKind.NOTE);
        here.announceAccessChanged(vault);

        assertThat(phone.received).containsExactly(
            new RecordingVaultSubscriber.Received(SyncFrame.TYPE_VAULT_NOTE_RENAMED, note, "Projekte/Plan – neu.md"));
        assertThat(stranger.received).isEmpty();
        assertThat(phone.accessChanges).isEqualTo(1);
        assertThat(invalidated).containsExactly(vault);
    }

    @Test
    void should_roundTripEveryMessageKind_throughTheWireFormat() {
        var vault = VaultId.newId();
        var messages = java.util.List.<ClusterMessage>of(
            new ClusterMessage.DocUpdated(note, 42),
            new ClusterMessage.Awareness(note, "a/b", bytes("x")),
            new ClusterMessage.AwarenessGone(note, "a/b"),
            new ClusterMessage.NoteDeleted(note),
            new ClusterMessage.VaultEvent(vault, SyncFrame.TYPE_VAULT_FOLDERS_CHANGED, SyncFrame.NO_NOTE, NoteKind.FILE, "Ordner/mit | Zeichen"),
            new ClusterMessage.AccessChanged(vault));

        for (var message : messages) {
            var decoded = ClusterMessage.decode(ClusterMessage.encode("instanz-1", message));
            assertThat(decoded.origin()).isEqualTo("instanz-1");
            assertThat(decoded.message()).usingRecursiveComparison().isEqualTo(message);
        }
    }
}
