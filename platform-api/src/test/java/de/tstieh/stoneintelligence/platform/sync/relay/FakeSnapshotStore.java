package de.tstieh.stoneintelligence.platform.sync.relay;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import de.tstieh.stoneintelligence.domain.id.NoteId;

public final class FakeSnapshotStore implements SnapshotStore {

    private record Stored(UpdateRecord record, UpdateInfo info) { }

    private final Map<NoteId, List<Stored>> updatesByNote = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;

    public FakeSnapshotStore() {
        this(Instant::now);
    }

    public FakeSnapshotStore(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Override
    public synchronized UpdateRecord append(NoteId noteId, byte[] payload, boolean ciphertext, String actor) {
        var updates = updatesByNote.computeIfAbsent(noteId, id -> new ArrayList<>());
        var sequence = updates.size() + 1L;
        var record = new UpdateRecord(sequence, payload, ciphertext);
        updates.add(new Stored(record, new UpdateInfo(sequence, actor, clock.get(), ciphertext)));
        return record;
    }

    @Override
    public synchronized java.util.Optional<UpdateRecord> appendIfCurrent(NoteId noteId, long expectedRevision, byte[] payload, String actor) {
        if (updatesByNote.getOrDefault(noteId, List.of()).size() != expectedRevision) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(append(noteId, payload, false, actor));
    }

    @Override
    public synchronized List<UpdateRecord> listSince(NoteId noteId, long afterServerSequence) {
        return updatesByNote.getOrDefault(noteId, List.of()).stream().map(Stored::record)
            .filter(record -> record.serverSequence() > afterServerSequence)
            .toList();
    }

    @Override
    public synchronized List<UpdateRecord> listUpTo(NoteId noteId, long serverSequence) {
        return updatesByNote.getOrDefault(noteId, List.of()).stream().map(Stored::record)
            .filter(record -> record.serverSequence() <= serverSequence)
            .toList();
    }

    @Override
    public synchronized List<UpdateInfo> log(NoteId noteId) {
        return updatesByNote.getOrDefault(noteId, List.of()).stream().map(Stored::info).toList();
    }

    @Override
    public synchronized Map<NoteId, Long> latestRevisions(java.util.Collection<NoteId> noteIds) {
        var revisions = new java.util.HashMap<NoteId, Long>();
        noteIds.forEach(id -> revisions.put(id, (long) updatesByNote.getOrDefault(id, List.of()).size()));
        return revisions;
    }
}
