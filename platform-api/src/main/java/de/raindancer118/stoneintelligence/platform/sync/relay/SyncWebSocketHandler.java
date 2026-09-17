package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.vault.ForbiddenException;
import de.raindancer118.stoneintelligence.platform.vault.NoteRepository;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * Duenner Adapter zwischen Spring-WebSocket und {@link SyncRelayService}. Eine Verbindung ist
 * seit der Multiplexing-Umstellung NICHT mehr an genau eine Notiz gebunden (das Ticket beim
 * Handshake traegt nur noch {@code vaultId}+{@code actor}, s. {@link TicketHandshakeInterceptor})
 * - stattdessen sendet der Client explizite {@code JOIN}/{@code LEAVE}-Kontrollnachrichten
 * (jede mit {@link SyncFrame} geframt), und diese eine Verbindung kann beliebig viele
 * Notiz-"Raeume" gleichzeitig halten. Das war der gesamte Zweck der Umstellung: vorher brauchte
 * jede Notiz ihre eigene WebSocket-Verbindung, ein Vault mit vielen Notizen hat auf manchen
 * Plattformen (Mobile) die Verbindungen des Betriebssystems/der WebView ausgeschoepft.
 *
 * <p>Die Berechtigungspruefung ({@link Permission#READ}) findet jetzt bei JOIN statt (frueher:
 * bei Ticket-Ausstellung) - genau EINMAL pro Notiz, nicht pro Nachricht (dieselbe bekannte
 * Grenze wie vorher: der Relay unterscheidet einzelne Update-/Awareness-Nachrichten nicht nach
 * Lese-/Schreibrecht).
 */
@Component
public class SyncWebSocketHandler extends BinaryWebSocketHandler {

    private final SyncRelayService relay;
    private final NoteRepository notes;
    private final VaultAccessGuard access;

    /** Pro Session (WS-Verbindung) die aktuell gejointen Notiz-Raeume - Grundlage fuer Autorisierung und Cleanup. */
    private final Map<String, Set<NoteId>> joinedNotesBySession = new ConcurrentHashMap<>();

    public SyncWebSocketHandler(SyncRelayService relay, NoteRepository notes, VaultAccessGuard access) {
        this.relay = relay;
        this.notes = notes;
        this.access = access;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        joinedNotesBySession.put(session.getId(), ConcurrentHashMap.newKeySet());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var raw = new byte[message.getPayloadLength()];
        message.getPayload().get(raw);

        SyncFrame frame;
        try {
            frame = SyncFrame.decode(raw);
        } catch (IllegalArgumentException malformed) {
            return;
        }

        var syncSession = new WebSocketSyncSession(session);
        switch (frame.messageType()) {
            case SyncFrame.TYPE_JOIN -> handleJoin(session, frame.noteId(), syncSession);
            case SyncFrame.TYPE_LEAVE -> handleLeave(session, frame.noteId(), syncSession);
            case SyncFrame.TYPE_AWARENESS -> {
                if (hasJoined(session, frame.noteId())) {
                    relay.onAwarenessUpdate(frame.noteId(), syncSession, frame.payload());
                }
            }
            default -> {
                if (hasJoined(session, frame.noteId())) {
                    relay.onUpdate(frame.noteId(), syncSession, frame.payload(), isCiphertextNote(frame.noteId()));
                }
            }
        }
    }

    /**
     * Prueft READ-Berechtigung auf die konkrete Notiz (Vault + Pfad, s. {@link VaultAccessGuard})
     * und traegt den Raum bei Erfolg als gejoint ein. Existiert die Notiz nicht in diesem Vault
     * oder fehlt die Berechtigung, passiert einfach nichts (kein Join, kein Fehler-Frame) - der
     * Client bekommt schlicht keinen Catchup und keine Updates fuer diese NoteId, ohne dass ein
     * Rueckschluss moeglich ist, WARUM (existiert nicht vs. keine Berechtigung).
     */
    private void handleJoin(WebSocketSession session, NoteId noteId, SyncSession syncSession) {
        var vaultId = vaultIdOf(session);
        var actor = actorOf(session);
        var note = notes.findById(vaultId, noteId);
        if (note.isEmpty()) {
            return;
        }
        try {
            access.require(vaultId, actor, Permission.READ, note.get().path());
        } catch (ForbiddenException denied) {
            return;
        }
        joinedNotesBySession.get(session.getId()).add(noteId);
        relay.onJoin(noteId, syncSession);
    }

    private void handleLeave(WebSocketSession session, NoteId noteId, SyncSession syncSession) {
        var joined = joinedNotesBySession.get(session.getId());
        if (joined != null && joined.remove(noteId)) {
            relay.onLeave(noteId, syncSession);
        }
    }

    private boolean hasJoined(WebSocketSession session, NoteId noteId) {
        var joined = joinedNotesBySession.get(session.getId());
        return joined != null && joined.contains(noteId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var joined = joinedNotesBySession.remove(session.getId());
        if (joined == null) {
            return;
        }
        var syncSession = new WebSocketSyncSession(session);
        for (var noteId : joined) {
            relay.onLeave(noteId, syncSession);
        }
    }

    private VaultId vaultIdOf(WebSocketSession session) {
        return (VaultId) session.getAttributes().get(TicketHandshakeInterceptor.ATTR_VAULT_ID);
    }

    private String actorOf(WebSocketSession session) {
        return (String) session.getAttributes().get(TicketHandshakeInterceptor.ATTR_ACTOR);
    }

    private boolean isCiphertextNote(NoteId noteId) {
        // Level-101-Notes (E2EE) werden im Ticket-Claim markiert, sobald der E2EE-Spike (Plan.md
        // Abschnitt 6, Phase 2) das Note-Level beim Join mitliefert. Bis dahin: false.
        return false;
    }

    /** Wrappt eine Spring-{@link WebSocketSession} als {@link SyncSession}, framt mit {@link SyncFrame}. */
    private record WebSocketSyncSession(WebSocketSession session) implements SyncSession {

        @Override
        public String id() {
            return session.getId();
        }

        @Override
        public void sendDocUpdate(NoteId noteId, byte[] payload) {
            send(SyncFrame.TYPE_DOC_UPDATE, noteId, payload);
        }

        @Override
        public void sendAwarenessUpdate(NoteId noteId, byte[] payload) {
            send(SyncFrame.TYPE_AWARENESS, noteId, payload);
        }

        private void send(byte messageType, NoteId noteId, byte[] payload) {
            var frame = new SyncFrame(messageType, noteId, payload);
            try {
                session.sendMessage(new BinaryMessage(frame.encode()));
            } catch (IOException e) {
                throw new SyncSessionSendException(session.getId(), e);
            }
        }

        @Override
        public void close(int code, String reason) {
            try {
                session.close(new CloseStatus(code, reason));
            } catch (IOException ignored) {
                // Session ist ohnehin bereits weg - nichts weiter zu tun.
            }
        }
    }
}
