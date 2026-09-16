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

    public void broadcastExcept(NoteId noteId, SyncSession sender, java.util.function.Consumer<SyncSession> action) {
        for (var session : rooms.getOrDefault(noteId, Set.of())) {
            if (!session.id().equals(sender.id())) {
                action.accept(session);
            }
        }
    }

    public void closeAll(NoteId noteId, int code, String reason) {
        var sessions = rooms.remove(noteId);
        if (sessions == null) {
            return;
        }
        for (var session : sessions) {
            session.close(code, reason);
        }
    }

    public int sessionCount(NoteId noteId) {
        return rooms.getOrDefault(noteId, Set.of()).size();
    }
}
