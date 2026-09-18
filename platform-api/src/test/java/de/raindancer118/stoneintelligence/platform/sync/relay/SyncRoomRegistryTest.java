package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.util.ArrayList;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression fuer den P2-Fund "One failed recipient can abort room broadcast" (s.
 * docs/sync-comparison-review-2026-09-18.md): ein werfender Empfaenger durfte den Broadcast an
 * alle SPAETER in der Iteration folgenden, gesunden Empfaenger nicht verhindern.
 */
class SyncRoomRegistryTest {

    private final SyncRoomRegistry registry = new SyncRoomRegistry();
    private final NoteId noteId = NoteId.newId();

    @Test
    void should_stillDeliverToLaterHealthyRecipients_when_anEarlierRecipientThrowsOnSend() {
        var sender = new RecordingSyncSession("sender");
        var broken = new ThrowingSyncSession("broken");
        var healthy = new RecordingSyncSession("healthy");
        registry.join(noteId, sender);
        registry.join(noteId, broken);
        registry.join(noteId, healthy);

        registry.broadcastExcept(noteId, sender, session -> session.sendDocUpdate(noteId, "payload".getBytes()));

        assertThat(healthy.receivedDocUpdates).containsExactly("payload".getBytes());
    }

    @Test
    void should_evictTheThrowingRecipient_soASubsequentBroadcastNeverRetriesIt() {
        var sender = new RecordingSyncSession("sender");
        var broken = new ThrowingSyncSession("broken");
        registry.join(noteId, sender);
        registry.join(noteId, broken);

        registry.broadcastExcept(noteId, sender, session -> session.sendDocUpdate(noteId, "first".getBytes()));
        broken.attempts.clear();
        registry.broadcastExcept(noteId, sender, session -> session.sendDocUpdate(noteId, "second".getBytes()));

        assertThat(broken.attempts).isEmpty();
    }

    /** Simuliert eine Session, deren zugrundeliegender WebSocket-Versand fehlschlaegt (s. {@code WebSocketSyncSession.send}). */
    private static final class ThrowingSyncSession implements SyncSession {
        private final String id;
        final List<byte[]> attempts = new ArrayList<>();

        ThrowingSyncSession(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void sendDocUpdate(NoteId noteId, byte[] payload) {
            attempts.add(payload);
            throw new SyncSessionSendException(id, new java.io.IOException("simulated send failure"));
        }

        @Override
        public void sendAwarenessUpdate(NoteId noteId, byte[] payload) {
            throw new SyncSessionSendException(id, new java.io.IOException("simulated send failure"));
        }

        @Override
        public void notifyNoteDeleted(NoteId noteId) {
        }

        @Override
        public void sendCatchupComplete(NoteId noteId) {
        }

        @Override
        public void close(int code, String reason) {
        }
    }
}
