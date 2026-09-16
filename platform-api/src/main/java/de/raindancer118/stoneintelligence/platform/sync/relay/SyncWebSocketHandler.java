package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.nio.ByteBuffer;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * Duenner Adapter zwischen Spring-WebSocket und {@link SyncRelayService}. Yjs-Updates sind
 * binaer, deshalb {@link BinaryWebSocketHandler} statt Text - der Server parst den Inhalt nicht
 * ("dummer Server").
 */
@Component
public class SyncWebSocketHandler extends BinaryWebSocketHandler {

    private final SyncRelayService relay;

    public SyncWebSocketHandler(SyncRelayService relay) {
        this.relay = relay;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        relay.onJoin(noteIdOf(session), new WebSocketSyncSession(session));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var payload = new byte[message.getPayloadLength()];
        message.getPayload().get(payload);
        var ciphertext = isCiphertextNote(session);
        relay.onUpdate(noteIdOf(session), new WebSocketSyncSession(session), payload, ciphertext);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        relay.onLeave(noteIdOf(session), new WebSocketSyncSession(session));
    }

    private NoteId noteIdOf(WebSocketSession session) {
        return (NoteId) session.getAttributes().get(TicketHandshakeInterceptor.ATTR_NOTE_ID);
    }

    private boolean isCiphertextNote(WebSocketSession session) {
        // Level-101-Notes (E2EE) werden im Ticket-Claim markiert, sobald der E2EE-Spike (Plan.md
        // Abschnitt 6, Phase 2) das Note-Level beim Ticket-Issuing mitliefert. Bis dahin: false.
        return false;
    }

    /** Wrappt eine Spring-{@link WebSocketSession} als {@link SyncSession}. */
    private record WebSocketSyncSession(WebSocketSession session) implements SyncSession {

        @Override
        public String id() {
            return session.getId();
        }

        @Override
        public void sendUpdate(byte[] payload) {
            try {
                session.sendMessage(new BinaryMessage(ByteBuffer.wrap(payload)));
            } catch (java.io.IOException e) {
                throw new SyncSessionSendException(session.getId(), e);
            }
        }

        @Override
        public void close(int code, String reason) {
            try {
                session.close(new CloseStatus(code, reason));
            } catch (java.io.IOException ignored) {
                // Session ist ohnehin bereits weg - nichts weiter zu tun.
            }
        }
    }
}
