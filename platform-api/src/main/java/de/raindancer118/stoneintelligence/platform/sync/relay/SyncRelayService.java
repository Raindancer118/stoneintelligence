package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.util.concurrent.ConcurrentHashMap;
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

    private final SnapshotStore snapshotStore;
    private final SyncRoomRegistry registry;
    /**
     * EIN Lock-Objekt pro Notiz, das {@link #onJoin} und {@link #onUpdate} fuer dieselbe Notiz
     * gegeneinander atomar macht - ehemals ein P1-Bug (s.
     * docs/sync-comparison-review-2026-09-18.md "Catchup-complete kann ein paralleles Live-Update
     * ueberholen"): registry.join() trug die Session schon vor dem Historie-Lesen ein, ein
     * dazwischen eintreffendes {@code onUpdate} broadcastete daher zusaetzlich an den noch
     * mitten im Catchup steckenden Joiner - der bekam dasselbe Update danach ERNEUT ueber die
     * eigene (inzwischen den neuen Stand enthaltende) Historie, dupliziert. Waechst unbegrenzt mit
     * der Anzahl je gesehener Notizen (kein Eintrag wird je entfernt) - dieselbe dokumentierte
     * Grenze wie {@link SyncRoomRegistry} (Einzelinstanz-Betrieb, kein Cluster-Broker).
     */
    private final ConcurrentHashMap<NoteId, Object> noteLocks = new ConcurrentHashMap<>();
    /**
     * Letzter bekannter Awareness-Zustand je (Notiz, Session) - rein zum Nachliefern an spaeter
     * beitretende Sessions (P1-Fund, s. docs/sync-comparison-review-2026-09-18.md "Awareness has
     * stale-cursor and late-join gaps": ein Late-Joiner sah bisher niemanden, bis irgendein Peer
     * zufaellig seinen Cursor bewegte). Bleibt "dumm" - die Bytes werden nie dekodiert, nur
     * verwahrt und unveraendert weitergereicht. Deckt NICHT die Kehrseite ab: verlaesst eine
     * Session den Raum, bekommen SCHON verbundene Peers keinen aktiven "Geist-Cursor entfernen"-
     * Broadcast (dafuer muesste der Server die Yjs-Awareness-Client-ID aus dem Payload dekodieren,
     * um eine Entfernungs-Nachricht zu konstruieren - bewusst nicht gemacht, dokumentierte Grenze).
     */
    private final ConcurrentHashMap<NoteId, ConcurrentHashMap<String, byte[]>> latestAwarenessBySession =
        new ConcurrentHashMap<>();

    public SyncRelayService(SnapshotStore snapshotStore, SyncRoomRegistry registry) {
        this.snapshotStore = snapshotStore;
        this.registry = registry;
    }

    private Object lockFor(NoteId noteId) {
        return noteLocks.computeIfAbsent(noteId, id -> new Object());
    }

    /**
     * Neuer Client tritt bei: Late-Joiner-Catchup mit der kompletten Update-Historie, danach ein
     * explizites Abschlusssignal (s. {@link SyncSession#sendCatchupComplete}) - erst dann weiss
     * der Client sicher, dass ein (noch) leeres lokales Dokument nicht auf eine noch unterwegs
     * befindliche Historie wartet. Synchronisiert auf {@link #lockFor(NoteId)}, s. Klassendoc.
     */
    public void onJoin(NoteId noteId, SyncSession session) {
        synchronized (lockFor(noteId)) {
            registry.join(noteId, session);
            for (var update : snapshotStore.listSince(noteId, 0)) {
                session.sendDocUpdate(noteId, update.payload());
            }
            var retainedAwareness = latestAwarenessBySession.get(noteId);
            if (retainedAwareness != null) {
                for (var entry : retainedAwareness.entrySet()) {
                    if (!entry.getKey().equals(session.id())) {
                        session.sendAwarenessUpdate(noteId, entry.getValue());
                    }
                }
            }
            session.sendCatchupComplete(noteId);
        }
    }

    /**
     * Eingehendes Yjs-Dokument-Update: persistieren, dann an alle anderen Sessions im Room
     * verteilen. Synchronisiert auf {@link #lockFor(NoteId)}, s. Klassendoc.
     */
    public void onUpdate(NoteId noteId, SyncSession sender, byte[] payload, boolean ciphertext) {
        synchronized (lockFor(noteId)) {
            snapshotStore.append(noteId, payload, ciphertext);
            registry.broadcastExcept(noteId, sender, session -> session.sendDocUpdate(noteId, payload));
        }
    }

    /** HTTP-Editor und WebSocket benutzen dieselbe Update-Historie und denselben Raum. */
    public java.util.Optional<UpdateRecord> saveIfCurrent(NoteId noteId, long expectedRevision, byte[] payload) {
        synchronized (lockFor(noteId)) {
            var appended = snapshotStore.appendIfCurrent(noteId, expectedRevision, payload);
            appended.ifPresent(update -> registry.broadcastExcept(noteId, null,
                session -> session.sendDocUpdate(noteId, update.payload())));
            return appended;
        }
    }

    /**
     * Eingehende Awareness-/Cursor-Nachricht (Anforderungen.md: "Über Websocket-Verbindungen
     * sollen die Cursor anderer Nutzer live sichtbar sein"): NUR weiterleiten, NIE persistieren
     * - das waere kein Dokument-Zustand, sondern ephemere Praesenz-Information.
     */
    public void onAwarenessUpdate(NoteId noteId, SyncSession sender, byte[] payload) {
        latestAwarenessBySession.computeIfAbsent(noteId, id -> new ConcurrentHashMap<>()).put(sender.id(), payload);
        registry.broadcastExcept(noteId, sender, session -> session.sendAwarenessUpdate(noteId, payload));
    }

    public void onLeave(NoteId noteId, SyncSession session) {
        registry.leave(noteId, session);
        var retained = latestAwarenessBySession.get(noteId);
        if (retained != null) {
            retained.remove(session.id());
        }
    }

    /**
     * Die Note wurde geloescht (Tombstone) - alle beteiligten Sessions werden benachrichtigt und
     * verlassen den Raum, ihre Verbindung selbst bleibt fuer andere gejointe Notizen bestehen.
     */
    public void onNoteDeleted(NoteId noteId) {
        registry.notifyDeletedAndLeaveAll(noteId);
        latestAwarenessBySession.remove(noteId);
    }
}
