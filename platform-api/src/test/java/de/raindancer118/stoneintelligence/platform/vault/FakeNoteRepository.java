package de.raindancer118.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;

/** In-Memory-Fake fuer Tests, spiegelt exakt die Semantik von {@code JdbcNoteRepository} wider. */
public final class FakeNoteRepository implements NoteRepository {

    private final Map<NoteId, Note> notes = new LinkedHashMap<>();
    private final Map<String, Tombstone> tombstonesByOperationKey = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public synchronized Note create(VaultId vaultId, String path, NoteLevel level, String createdBy) {
        var note = new Note(NoteId.newId(), vaultId, path, level, createdBy, Instant.now());
        notes.put(note.id(), note);
        return note;
    }

    @Override
    public synchronized Optional<Note> findById(NoteId id) {
        return Optional.ofNullable(notes.get(id));
    }

    @Override
    public synchronized ReconciliationPage list(VaultId vaultId, String cursorToken, int pageSize) {
        var cursor = ReconciliationCursor.decode(cursorToken);
        var epochId = cursor.map(ReconciliationCursor::epochId).orElseGet(UUID::randomUUID);
        var lastSeenId = cursor.flatMap(ReconciliationCursor::lastSeenId);

        var candidates = notes.values().stream()
            .filter(note -> note.vaultId().equals(vaultId))
            .sorted((a, b) -> a.id().value().compareTo(b.id().value()))
            .toList();

        var startIndex = lastSeenId
            .map(id -> indexAfter(candidates, id))
            .orElse(0);

        var page = new ArrayList<Note>();
        var index = startIndex;
        while (index < candidates.size() && page.size() < pageSize) {
            page.add(candidates.get(index));
            index++;
        }

        var complete = index >= candidates.size();
        var nextCursor = complete
            ? Optional.<String>empty()
            : Optional.of(ReconciliationCursor.of(epochId, page.getLast().id()).encode());

        return new ReconciliationPage(epochId, complete, nextCursor, page);
    }

    private int indexAfter(List<Note> candidates, NoteId lastSeenId) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).id().equals(lastSeenId)) {
                return i + 1;
            }
        }
        return candidates.size();
    }

    @Override
    public synchronized Tombstone delete(VaultId vaultId, NoteId noteId, String operationId, String deletedBy) {
        var key = vaultId.value() + "|" + operationId;
        var existing = tombstonesByOperationKey.get(key);
        if (existing != null) {
            return existing;
        }

        notes.remove(noteId);
        var tombstone = new Tombstone(
            UUID.randomUUID(), vaultId, noteId, operationId, sequence.incrementAndGet(), deletedBy, Instant.now());
        tombstonesByOperationKey.put(key, tombstone);
        return tombstone;
    }
}
