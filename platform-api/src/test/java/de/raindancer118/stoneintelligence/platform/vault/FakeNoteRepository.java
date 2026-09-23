package de.raindancer118.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    // Monoton wachsende Sequenznummer je Note (spiegelt die bigserial-Spalte der Jdbc-Variante) -
    // NICHT die NoteId selbst, die hat keine Beziehung zur Einfuegereihenfolge (s. ReconciliationCursor).
    private final Map<NoteId, Long> noteSequences = new LinkedHashMap<>();
    private final AtomicLong noteSequenceCounter = new AtomicLong(0);
    private final Map<String, Tombstone> tombstonesByOperationKey = new LinkedHashMap<>();
    private final AtomicLong tombstoneSequenceCounter = new AtomicLong(0);

    @Override
    public synchronized Note create(VaultId vaultId, String path, NoteLevel level, String createdBy) {
        var note = new Note(NoteId.newId(), vaultId, path, level, createdBy, Instant.now());
        notes.put(note.id(), note);
        noteSequences.put(note.id(), noteSequenceCounter.incrementAndGet());
        return note;
    }

    @Override
    public synchronized java.util.List<Note> findByPath(VaultId vaultId, String path) {
        return notes.values().stream().filter(n -> n.vaultId().equals(vaultId) && n.path().equals(path)).toList();
    }

    @Override
    public synchronized Optional<Note> findById(VaultId vaultId, NoteId id) {
        return Optional.ofNullable(notes.get(id)).filter(note -> note.vaultId().equals(vaultId));
    }

    @Override
    public synchronized Note rename(VaultId vaultId, NoteId noteId, String newPath) {
        var existing = findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId));
        var renamed = new Note(existing.id(), existing.vaultId(), newPath, existing.level(),
            existing.createdBy(), existing.createdAt());
        notes.put(noteId, renamed);
        return renamed;
    }

    @Override
    public synchronized ReconciliationPage list(VaultId vaultId, String cursorToken, int pageSize) {
        var cursor = ReconciliationCursor.decode(cursorToken);
        var epochId = cursor.map(ReconciliationCursor::epochId).orElseGet(UUID::randomUUID);
        var lastSeenSequence = cursor.flatMap(ReconciliationCursor::lastSeenSequence).orElse(0L);

        var candidates = notes.values().stream()
            .filter(note -> note.vaultId().equals(vaultId))
            .filter(note -> noteSequences.get(note.id()) > lastSeenSequence)
            .sorted(Comparator.comparingLong(note -> noteSequences.get(note.id())))
            .toList();

        var complete = candidates.size() <= pageSize;
        var page = complete ? candidates : candidates.subList(0, pageSize);
        var nextCursor = complete
            ? Optional.<String>empty()
            : Optional.of(ReconciliationCursor.of(epochId, noteSequences.get(page.getLast().id())).encode());

        return new ReconciliationPage(epochId, complete, nextCursor, page);
    }

    @Override
    public synchronized Tombstone delete(VaultId vaultId, NoteId noteId, String operationId, String deletedBy) {
        var key = vaultId.value() + "|" + noteId.value() + "|" + operationId;
        var existing = tombstonesByOperationKey.get(key);
        if (existing != null) {
            return existing;
        }

        findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId));
        notes.remove(noteId);
        noteSequences.remove(noteId);
        var tombstone = new Tombstone(
            UUID.randomUUID(), vaultId, noteId, operationId, tombstoneSequenceCounter.incrementAndGet(), deletedBy, Instant.now());
        tombstonesByOperationKey.put(key, tombstone);
        return tombstone;
    }
}
