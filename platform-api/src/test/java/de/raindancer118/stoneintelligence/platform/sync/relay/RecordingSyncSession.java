package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.util.ArrayList;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;

final class RecordingSyncSession implements SyncSession {

    private final String id;
    final List<byte[]> receivedDocUpdates = new ArrayList<>();
    final List<NoteId> receivedDocUpdateNoteIds = new ArrayList<>();
    final List<byte[]> receivedAwarenessUpdates = new ArrayList<>();
    final List<NoteId> notifiedDeletedNoteIds = new ArrayList<>();
    final List<NoteId> catchupCompletedNoteIds = new ArrayList<>();
    Integer closedWithCode;
    String closedWithReason;

    /**
     * EIN gemeinsamer, chronologisch geordneter Log ueber alle Ereignistypen - die separaten
     * {@code receivedDocUpdates}/{@code catchupCompletedNoteIds}-Listen oben verlieren die
     * relative Reihenfolge ZWISCHEN Ereignistypen (z. B. "kam das Update vor oder nach dem
     * Catchup-Abschluss an?"), was fuer Nebenlaeufigkeits-Regressionstests genau die Frage ist.
     */
    final List<String> orderedEvents = new java.util.concurrent.CopyOnWriteArrayList<>();

    RecordingSyncSession(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void sendDocUpdate(NoteId noteId, byte[] payload) {
        receivedDocUpdateNoteIds.add(noteId);
        receivedDocUpdates.add(payload);
        orderedEvents.add("doc-update:" + new String(payload, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void sendAwarenessUpdate(NoteId noteId, byte[] payload) {
        receivedAwarenessUpdates.add(payload);
    }

    @Override
    public void notifyNoteDeleted(NoteId noteId) {
        notifiedDeletedNoteIds.add(noteId);
    }

    @Override
    public void sendCatchupComplete(NoteId noteId) {
        catchupCompletedNoteIds.add(noteId);
        orderedEvents.add("catchup-complete");
    }

    @Override
    public void close(int code, String reason) {
        this.closedWithCode = code;
        this.closedWithReason = reason;
    }
}
