package de.raindancer118.stoneintelligence.platform.sync.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;

/**
 * Deckt den in der Codex-Vergleichsreview gefundenen P0-Bug ab (s. {@code
 * docs/sync-comparison-review-2026-09-18.md}): JOIN prueft nur {@link Permission#READ}, danach
 * akzeptierte der {@code default}-Case jede Nachricht als Dokument-Update - ein reiner Leser
 * konnte damit dauerhaft schreiben.
 */
class SyncWebSocketHandlerTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeSnapshotStore snapshotStore = new FakeSnapshotStore();
    private final SyncRoomRegistry registry = new SyncRoomRegistry();
    private final SyncRelayService relay = new SyncRelayService(snapshotStore, registry);
    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final SyncWebSocketHandler handler =
        new SyncWebSocketHandler(relay, notes, new VaultAccessGuard(authorization), announcements);

    private VaultId newReaderOnlyNote(String actor, String path) {
        var vaultId = VaultId.newId();
        var note = notes.create(vaultId, path, NoteLevel.of(1), "creator");
        var role = authorization.createRole(vaultId, "reader", Set.of(Permission.READ));
        var group = authorization.createGroup(vaultId, "readers");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), actor);
        this.lastNoteId = note.id();
        return vaultId;
    }

    private NoteId lastNoteId;

    private FakeWebSocketSession connect(VaultId vaultId, String actor) {
        var session = new FakeWebSocketSession();
        session.getAttributes().put(TicketHandshakeInterceptor.ATTR_VAULT_ID, vaultId);
        session.getAttributes().put(TicketHandshakeInterceptor.ATTR_ACTOR, actor);
        handler.afterConnectionEstablished(session);
        return session;
    }

    private void send(FakeWebSocketSession session, byte messageType, NoteId noteId, byte[] payload) {
        var frame = new SyncFrame(messageType, noteId, payload);
        handler.handleBinaryMessage(session, new BinaryMessage(frame.encode()));
    }

    @Test
    void should_notPersistOrBroadcastDocUpdate_when_senderOnlyHasReadPermission() {
        var vaultId = newReaderOnlyNote("reader-actor", "readonly.md");
        var noteId = lastNoteId;
        var reader = connect(vaultId, "reader-actor");
        send(reader, SyncFrame.TYPE_JOIN, noteId, new byte[0]);

        send(reader, SyncFrame.TYPE_DOC_UPDATE, noteId, "malicious write".getBytes());

        assertThat(snapshotStore.listSince(noteId, 0)).isEmpty();
    }

    @Test
    void should_notBroadcastDocUpdate_when_senderOnlyHasReadPermission() {
        var vaultId = newReaderOnlyNote("reader-actor", "readonly.md");
        var noteId = lastNoteId;
        var writerRole = authorization.createRole(vaultId, "writer", Set.of(Permission.READ, Permission.WRITE));
        var writers = authorization.createGroup(vaultId, "writers");
        authorization.assignRole(writers.id(), writerRole.id());
        authorization.addMember(writers.id(), "writer-actor");

        var reader = connect(vaultId, "reader-actor");
        var writer = connect(vaultId, "writer-actor");
        send(reader, SyncFrame.TYPE_JOIN, noteId, new byte[0]);
        send(writer, SyncFrame.TYPE_JOIN, noteId, new byte[0]);
        writer.sentMessages.clear();

        send(reader, SyncFrame.TYPE_DOC_UPDATE, noteId, "malicious write".getBytes());

        assertThat(writer.sentMessages).isEmpty();
    }

    @Test
    void should_persistAndBroadcastDocUpdate_when_senderHasWritePermission() {
        var vaultId = VaultId.newId();
        var note = notes.create(vaultId, "shared.md", NoteLevel.of(1), "creator");
        var role = authorization.createRole(vaultId, "writer", Set.of(Permission.READ, Permission.WRITE));
        var group = authorization.createGroup(vaultId, "writers");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "writer-actor");

        var writer = connect(vaultId, "writer-actor");
        send(writer, SyncFrame.TYPE_JOIN, note.id(), new byte[0]);
        writer.sentMessages.clear();

        send(writer, SyncFrame.TYPE_DOC_UPDATE, note.id(), "real edit".getBytes());

        assertThat(snapshotStore.listSince(note.id(), 0)).hasSize(1);
    }

    @Test
    void should_notAcceptFurtherDocUpdates_forANoteAfterItWasDeleted() {
        // Regression (Codex-Verifikationsreview des Security-Fixes, s.
        // /tmp/codex-research/theoretical-optimum-report.md Fund #1): notifyNoteDeleted raeumte
        // nur joinedNotesBySession auf, NIE writableNotesBySession - der separate WRITE-Cache
        // ueberlebte die Loeschung und liess eine (fehlerhafte/boeswillige) Session weiter
        // Updates fuer eine laengst geloeschte NoteId persistieren.
        var vaultId = VaultId.newId();
        var note = notes.create(vaultId, "shared.md", NoteLevel.of(1), "creator");
        var role = authorization.createRole(vaultId, "writer", Set.of(Permission.READ, Permission.WRITE));
        var group = authorization.createGroup(vaultId, "writers");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "writer-actor");
        var writer = connect(vaultId, "writer-actor");
        send(writer, SyncFrame.TYPE_JOIN, note.id(), new byte[0]);

        relay.onNoteDeleted(note.id());
        send(writer, SyncFrame.TYPE_DOC_UPDATE, note.id(), "update after deletion".getBytes());

        assertThat(snapshotStore.listSince(note.id(), 0)).isEmpty();
    }
}
