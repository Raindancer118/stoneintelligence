package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.nio.ByteBuffer;
import java.util.Arrays;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * Duenner Adapter zwischen Spring-WebSocket und {@link SyncRelayService}. Yjs-Updates sind
 * binaer, deshalb {@link BinaryWebSocketHandler} statt Text - der Server parst den
 * Yjs-Dokumentinhalt nicht ("dummer Server"), muss aber zwischen Dokument-Updates und
 * Awareness-/Cursor-Nachrichten unterscheiden (erstes Byte = Nachrichtentyp), da nur Erstere
 * persistiert werden duerfen.
 */
@Component
public class SyncWebSocketHandler extends BinaryWebSocketHandler {

    public static final byte MESSAGE_TYPE_DOC_UPDATE = 0;
    public static final byte MESSAGE_TYPE_AWARENESS = 1;

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
        var raw = new byte[message.getPayloadLength()];
        message.getPayload().get(raw);
        if (raw.length == 0) {
            return;
        }

        var messageType = raw[0];
        var payload = Arrays.copyOfRange(raw, 1, raw.length);
        var syncSession = new WebSocketSyncSession(session);
        var noteId = noteIdOf(session);

        if (messageType == MESSAGE_TYPE_AWARENESS) {
            relay.onAwarenessUpdate(noteId, syncSession, payload);
        } else {
            relay.onUpdate(noteId, syncSession, payload, isCiphertextNote(session));
        }
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

    /** Wrappt eine Spring-{@link WebSocketSession} als {@link SyncSession}, fuegt die Typ-Byte-Framing hinzu. */
    private record WebSocketSyncSession(WebSocketSession session) implements SyncSession {

        @Override
        public String id() {
            return session.getId();
        }

        @Override
        public void sendDocUpdate(byte[] payload) {
            send(MESSAGE_TYPE_DOC_UPDATE, payload);
        }

        @Override
        public void sendAwarenessUpdate(byte[] payload) {
            send(MESSAGE_TYPE_AWARENESS, payload);
        }

        private void send(byte messageType, byte[] payload) {
            var framed = ByteBuffer.allocate(1 + payload.length);
            framed.put(messageType);
            framed.put(payload);
            framed.flip();
            try {
                session.sendMessage(new BinaryMessage(framed));
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
