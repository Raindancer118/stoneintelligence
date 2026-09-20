package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import de.raindancer118.stoneintelligence.domain.id.NoteId;

public final class FakeSnapshotStore implements SnapshotStore {

    private final Map<NoteId, List<UpdateRecord>> updatesByNote = new ConcurrentHashMap<>();

    @Override
    public synchronized UpdateRecord append(NoteId noteId, byte[] payload, boolean ciphertext) {
        var updates = updatesByNote.computeIfAbsent(noteId, id -> new ArrayList<>());
        var record = new UpdateRecord(updates.size() + 1L, payload, ciphertext);
        updates.add(record);
        return record;
    }

    @Override
    public synchronized java.util.Optional<UpdateRecord> appendIfCurrent(NoteId noteId, long expectedRevision, byte[] payload) {
        if (updatesByNote.getOrDefault(noteId, List.of()).size() != expectedRevision) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(append(noteId, payload, false));
    }

    @Override
    public synchronized List<UpdateRecord> listSince(NoteId noteId, long afterServerSequence) {
        return updatesByNote.getOrDefault(noteId, List.of()).stream()
            .filter(record -> record.serverSequence() > afterServerSequence)
            .toList();
    }
}
