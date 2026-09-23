package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.vault.ForbiddenException;
import de.raindancer118.stoneintelligence.platform.vault.Note;
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
 * <p>Die Berechtigungspruefung findet bei JOIN statt (frueher: bei Ticket-Ausstellung), genau
 * EINMAL pro Notiz, nicht pro Nachricht: {@link Permission#READ} entscheidet, ob ueberhaupt
 * gejoint werden darf (Catchup, Awareness), {@link Permission#WRITE} zusaetzlich, ob Dokument-
 * Updates von dieser Session akzeptiert werden ({@link #writableNotesBySession}) - ehemals ein
 * P0-Sicherheitsbug (s. {@code docs/sync-comparison-review-2026-09-18.md}): jeder gejointe
 * Client durfte schreiben, READ war effektiv WRITE.
 */
@Component
public class SyncWebSocketHandler extends BinaryWebSocketHandler {

    private final SyncRelayService relay;
    private final NoteRepository notes;
    private final VaultAccessGuard access;
    private final VaultAnnouncementService announcements;

    /** Pro Session (WS-Verbindung) die aktuell gejointen Notiz-Raeume - Grundlage fuer Autorisierung und Cleanup. */
    private final Map<String, Set<NoteId>> joinedNotesBySession = new ConcurrentHashMap<>();
    /** Verbindungen, die Inhalts-Ankuendigungen (Typ 9) abonniert haben. */
    private final Set<String> contentUpdateSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> folderEventSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> fileEventSessions = ConcurrentHashMap.newKeySet();
    /**
     * Pro Session die gejointen Notizen, fuer die der Actor zusaetzlich {@link Permission#WRITE}
     * hat - getrennt von {@link #joinedNotesBySession} (das nur READ voraussetzt), weil sonst
     * jeder Join automatisch Schreibrecht gewaehrt haette (s. Klassendoc).
     */
    private final Map<String, Set<NoteId>> writableNotesBySession = new ConcurrentHashMap<>();

    public SyncWebSocketHandler(SyncRelayService relay, NoteRepository notes, VaultAccessGuard access,
                                VaultAnnouncementService announcements) {
        this.relay = relay;
        this.notes = notes;
        this.access = access;
        this.announcements = announcements;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        joinedNotesBySession.put(session.getId(), ConcurrentHashMap.newKeySet());
        writableNotesBySession.put(session.getId(), ConcurrentHashMap.newKeySet());
        // Vault-weite Bestandsankuendigungen gelten fuer die GESAMTE Verbindung, unabhaengig
        // davon, welche Notizen sie gerade gejoint hat - deshalb schon hier, nicht erst beim Join.
        announcements.subscribe(vaultIdOf(session), new WebSocketSyncSession(session));
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
            case SyncFrame.TYPE_SUBSCRIBE_CONTENT_UPDATES -> contentUpdateSessions.add(session.getId());
            case SyncFrame.TYPE_SUBSCRIBE_FOLDER_EVENTS -> folderEventSessions.add(session.getId());
            case SyncFrame.TYPE_SUBSCRIBE_FILE_EVENTS -> fileEventSessions.add(session.getId());
            case SyncFrame.TYPE_AWARENESS -> {
                if (hasJoined(session, frame.noteId())) {
                    relay.onAwarenessUpdate(frame.noteId(), syncSession, frame.payload());
                }
            }
            default -> {
                if (hasWriteAccess(session, frame.noteId())) {
                    relay.onUpdate(frame.noteId(), syncSession, frame.payload(), isCiphertextNote(frame.noteId()));
                    var vaultId = vaultIdOf(session);
                    announcements.announceNoteUpdated(vaultId, frame.noteId(),
                        () -> notes.findById(vaultId, frame.noteId()).map(Note::path));
                }
            }
        }
    }

    /**
     * Prueft READ-Berechtigung auf die konkrete Notiz (Vault + Pfad, s. {@link VaultAccessGuard})
     * und traegt den Raum bei Erfolg als gejoint ein. Existiert die Notiz nicht in diesem Vault
     * oder fehlt die READ-Berechtigung, passiert einfach nichts (kein Join, kein Fehler-Frame) -
     * der Client bekommt schlicht keinen Catchup und keine Updates fuer diese NoteId, ohne dass
     * ein Rueckschluss moeglich ist, WARUM (existiert nicht vs. keine Berechtigung). Ob die
     * Session zusaetzlich WRITE hat, wird separat (nicht-werfend) geprueft und in {@link
     * #writableNotesBySession} vermerkt - ein reiner Leser joint erfolgreich (sieht Catchup +
     * Live-Updates), darf aber keine eigenen Updates einspielen (s. {@link #hasWriteAccess}).
     */
    private void handleJoin(WebSocketSession session, NoteId noteId, SyncSession syncSession) {
        var vaultId = vaultIdOf(session);
        var actor = actorOf(session);
        var note = notes.findById(vaultId, noteId);
        // Dateien haben keinen Yjs-Inhalt (ADR 0009) - ihre Bytes laufen ueber /files.
        if (note.isEmpty() || note.get().isFile()) {
            return;
        }
        try {
            access.require(vaultId, actor, Permission.READ, note.get().path());
        } catch (ForbiddenException denied) {
            return;
        }
        joinedNotesBySession.get(session.getId()).add(noteId);
        if (hasPermission(vaultId, actor, Permission.WRITE, note.get().path())) {
            writableNotesBySession.get(session.getId()).add(noteId);
        }
        relay.onJoin(noteId, syncSession);
    }

    private void handleLeave(WebSocketSession session, NoteId noteId, SyncSession syncSession) {
        var joined = joinedNotesBySession.get(session.getId());
        var writable = writableNotesBySession.get(session.getId());
        if (writable != null) {
            writable.remove(noteId);
        }
        if (joined != null && joined.remove(noteId)) {
            relay.onLeave(noteId, syncSession);
        }
    }

    private boolean hasJoined(WebSocketSession session, NoteId noteId) {
        var joined = joinedNotesBySession.get(session.getId());
        return joined != null && joined.contains(noteId);
    }

    private boolean hasWriteAccess(WebSocketSession session, NoteId noteId) {
        var writable = writableNotesBySession.get(session.getId());
        return writable != null && writable.contains(noteId);
    }

    private boolean hasPermission(VaultId vaultId, String actor, Permission permission, String path) {
        try {
            access.require(vaultId, actor, permission, path);
            return true;
        } catch (ForbiddenException denied) {
            return false;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        announcements.unsubscribe(vaultIdOf(session), new WebSocketSyncSession(session));
        writableNotesBySession.remove(session.getId());
        contentUpdateSessions.remove(session.getId());
        folderEventSessions.remove(session.getId());
        fileEventSessions.remove(session.getId());
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

    /**
     * Wrappt eine Spring-{@link WebSocketSession} als {@link SyncSession}, framt mit
     * {@link SyncFrame}. Bewusst eine NICHT-statische innere Klasse (statt eines record wie
     * vorher) - {@link #notifyNoteDeleted} muss den eigenen {@link #joinedNotesBySession}-Eintrag
     * dieser Session bereinigen, sonst wuerde ein (fehlerhafter/boeswilliger) Client nach einer
     * Loeschung weiter Updates fuer die laengst geloeschte NoteId senden koennen.
     */
    private final class WebSocketSyncSession implements SyncSession, VaultSubscriber {

        private final WebSocketSession session;

        private WebSocketSyncSession(WebSocketSession session) {
            this.session = session;
        }

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

        @Override
        public void notifyNoteDeleted(NoteId noteId) {
            var joined = joinedNotesBySession.get(session.getId());
            if (joined != null) {
                joined.remove(noteId);
            }
            // OHNE das ueberlebte der separate WRITE-Cache die Loeschung (P0-Sicherheitsfix in
            // SyncWebSocketHandler.handleJoin fuehrte diesen zweiten Cache erst ein, s. dessen
            // Klassendoc) - eine Session haette fuer eine laengst geloeschte NoteId weiter
            // Dokument-Updates einspielen koennen, obwohl sie laut hasJoined() nicht mal mehr
            // gejoint war (Fund aus der Codex-Verifikationsreview des Security-Fixes).
            var writable = writableNotesBySession.get(session.getId());
            if (writable != null) {
                writable.remove(noteId);
            }
            send(SyncFrame.TYPE_NOTE_DELETED, noteId, new byte[0]);
        }

        @Override
        public void sendCatchupComplete(NoteId noteId) {
            send(SyncFrame.TYPE_CATCHUP_COMPLETE, noteId, new byte[0]);
        }

        /**
         * Eine Bestandsankuendigung darf nur an Empfaenger gehen, die die Notiz auch lesen
         * duerfen - sonst verriete allein die Ankuendigung Existenz und Ablage fremder Notizen.
         * Geprueft wird gegen den PFAD (nicht die NoteId): bei einer Loeschung ist die Notiz
         * bereits weg, eine Id-basierte Pruefung liefe ins Leere.
         */
        @Override
        public boolean wantsContentUpdates() {
            return contentUpdateSessions.contains(session.getId());
        }

        @Override
        public boolean wantsFileEvents() {
            return fileEventSessions.contains(session.getId());
        }

        @Override
        public boolean wantsFolderEvents() {
            return folderEventSessions.contains(session.getId());
        }

        @Override
        public boolean hasJoined(NoteId noteId) {
            var joined = joinedNotesBySession.get(session.getId());
            return joined != null && joined.contains(noteId);
        }

        @Override
        public boolean mayRead(String path) {
            return hasPermission(vaultIdOf(session), actorOf(session), Permission.READ, path);
        }

        @Override
        public void sendVaultEvent(byte messageType, NoteId noteId, String path) {
            send(messageType, noteId, path.getBytes(StandardCharsets.UTF_8));
        }


        private void send(byte messageType, NoteId noteId, byte[] payload) {
            var frame = new SyncFrame(messageType, noteId, payload);
            try {
                // WebSocketSession.sendMessage() ist NICHT threadsicher (Tomcat/Spring-Doku) -
                // seit der Multiplexing-Umstellung kann dieselbe Session gleichzeitig aus
                // verschiedenen Threads beliefert werden (z. B. ein JOIN-Catchup fuer Notiz A UND
                // ein Broadcast fuer Notiz B praktisch zeitgleich). Ohne diese Synchronisierung
                // gehen unter Last vereinzelt Frames verloren (live per Simulation gefunden:
                // VaultSyncSimulationIT, ein Burst aus 120 gleichzeitigen Joins verlor
                // zuverlaessig genau eine zufaellige Notiz).
                synchronized (session) {
                    session.sendMessage(new BinaryMessage(frame.encode()));
                }
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

        /**
         * Nicht mehr automatisch generiert, seit dies kein record mehr ist (s. Klassendoc oben) -
         * OHNE das wuerde {@code SyncRoomRegistry.leave} (nutzt {@code Set.remove}, verlangt
         * strukturelle Gleichheit) den bei JOIN eingetragenen Eintrag nie wiederfinden, weil pro
         * Nachricht eine FRISCHE {@code WebSocketSyncSession}-Instanz erzeugt wird.
         */
        @Override
        public boolean equals(Object obj) {
            return obj instanceof WebSocketSyncSession other && session.equals(other.session);
        }

        @Override
        public int hashCode() {
            return session.hashCode();
        }
    }
}
