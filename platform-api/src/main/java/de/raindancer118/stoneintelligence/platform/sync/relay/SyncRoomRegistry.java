package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import org.springframework.stereotype.Component;

/**
 * Haelt die pro Note aktuell verbundenen {@link SyncSession}s im Speicher (ein "Room" je Note).
 * Bewusst in-memory und einzelinstanz-gebunden - Mehr-Instanz-Betrieb braucht einen Broker
 * (z. B. Redis Pub/Sub) zwischen den platform-api-Prozessen; dokumentierter Folgeschritt.
 */
@Component
public class SyncRoomRegistry {

    private final Map<NoteId, Set<SyncSession>> rooms = new ConcurrentHashMap<>();

    public void join(NoteId noteId, SyncSession session) {
        rooms.computeIfAbsent(noteId, id -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void leave(NoteId noteId, SyncSession session) {
        rooms.computeIfPresent(noteId, (id, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /**
     * Isoliert jeden Empfaenger einzeln (P2-Fix, s. docs/sync-comparison-review-2026-09-18.md
     * "One failed recipient can abort room broadcast"): ein einzelner werfender Versand (z. B.
     * {@link SyncSessionSendException} bei einer bereits toten, aber noch nicht abgeraeumten
     * Verbindung) durfte den Broadcast an alle SPAETER in der Iteration folgenden, gesunden
     * Sessions nicht verhindern - das persistierte Update haette diese sonst erst bei ihrem
     * naechsten vollstaendigen Catchup gesehen, statt live. Die werfende Session wird zusaetzlich
     * sofort aus dem Room entfernt, statt bei jedem weiteren Broadcast erneut zu scheitern.
     */
    public void broadcastExcept(NoteId noteId, SyncSession sender, java.util.function.Consumer<SyncSession> action) {
        for (var session : rooms.getOrDefault(noteId, Set.of())) {
            if (session.id().equals(sender.id())) {
                continue;
            }
            try {
                action.accept(session);
            } catch (RuntimeException failedSend) {
                leave(noteId, session);
            }
        }
    }

    /**
     * Die Notiz wurde geloescht: entfernt den Raum und benachrichtigt jede beteiligte Session -
     * schliesst dabei bewusst NICHT die Verbindung selbst (s. {@link SyncSession#notifyNoteDeleted}),
     * seit der Multiplexing-Umstellung koennte dieselbe Verbindung noch weitere, nicht geloeschte
     * Notizen bedienen.
     */
    public void notifyDeletedAndLeaveAll(NoteId noteId) {
        var sessions = rooms.remove(noteId);
        if (sessions == null) {
            return;
        }
        for (var session : sessions) {
            session.notifyNoteDeleted(noteId);
        }
    }

    public int sessionCount(NoteId noteId) {
        return rooms.getOrDefault(noteId, Set.of()).size();
    }
}
