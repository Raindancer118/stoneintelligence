package de.raindancer118.stoneintelligence.platform.sync.relay;

import de.raindancer118.stoneintelligence.domain.id.NoteId;
import org.springframework.stereotype.Service;

/**
 * Kernlogik des Yjs-Relays: der Server persistiert und verteilt rohe Update-Blobs, ohne sie zu
 * interpretieren ("dummer Server", Plan.md Abschnitt 2). Getrennt von
 * {@link SyncWebSocketHandler}, damit diese Logik ohne Spring-WebSocket-Infrastruktur testbar
 * bleibt.
 */
@Service
public class SyncRelayService {

    public static final int CLOSE_CODE_NOTE_DELETED = 4404;

    private final SnapshotStore snapshotStore;
    private final SyncRoomRegistry registry;

    public SyncRelayService(SnapshotStore snapshotStore, SyncRoomRegistry registry) {
        this.snapshotStore = snapshotStore;
        this.registry = registry;
    }

    /** Neuer Client tritt bei: Late-Joiner-Catchup mit der kompletten Update-Historie. */
    public void onJoin(NoteId noteId, SyncSession session) {
        registry.join(noteId, session);
        for (var update : snapshotStore.listSince(noteId, 0)) {
            session.sendUpdate(update.payload());
        }
    }

    /** Eingehendes Yjs-Update: persistieren, dann an alle anderen Sessions im Room verteilen. */
    public void onUpdate(NoteId noteId, SyncSession sender, byte[] payload, boolean ciphertext) {
        snapshotStore.append(noteId, payload, ciphertext);
        registry.broadcastExcept(noteId, sender, payload);
    }

    public void onLeave(NoteId noteId, SyncSession session) {
        registry.leave(noteId, session);
    }

    /** Die Note wurde geloescht (Tombstone) - alle verbundenen Clients werden getrennt. */
    public void onNoteDeleted(NoteId noteId) {
        registry.closeAll(noteId, CLOSE_CODE_NOTE_DELETED, "note deleted");
    }
}
