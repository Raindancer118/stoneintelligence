package de.tstieh.stoneintelligence.platform.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;

/** Wie {@code JdbcNoteEmbeddingRepository}, nur ohne Index: vergleicht jeden Abschnitt mit jedem. */
public final class FakeNoteEmbeddingRepository implements NoteEmbeddingRepository {

    private record Stored(VaultId vaultId, String model, String hash, List<Chunk> chunks) {
    }

    private final NoteRepository notes;
    private final Map<NoteId, Stored> stored = new LinkedHashMap<>();

    public FakeNoteEmbeddingRepository(NoteRepository notes) {
        this.notes = notes;
    }

    private boolean exists(VaultId vaultId, NoteId noteId) {
        return notes.findById(vaultId, noteId).isPresent();
    }

    @Override
    public synchronized List<State> states(VaultId vaultId) {
        return stored.entrySet().stream()
            .filter(e -> e.getValue().vaultId().equals(vaultId) && exists(vaultId, e.getKey()))
            .map(e -> new State(e.getKey(), e.getValue().model(), e.getValue().hash())).toList();
    }

    @Override
    public synchronized void replace(VaultId vaultId, NoteId noteId, String model, String contentHash, List<Chunk> chunks) {
        stored.put(noteId, new Stored(vaultId, model, contentHash, List.copyOf(chunks)));
    }

    @Override
    public synchronized List<Similar> similarTo(VaultId vaultId, NoteId noteId, int limit) {
        var own = stored.get(noteId);
        if (own == null || !own.vaultId().equals(vaultId)) {
            return List.of();
        }
        var best = new HashMap<NoteId, Similar>();
        for (var entry : stored.entrySet()) {
            if (entry.getKey().equals(noteId) || !entry.getValue().vaultId().equals(vaultId) || !exists(vaultId, entry.getKey())) {
                continue;
            }
            for (var mine : own.chunks()) {
                for (var theirs : entry.getValue().chunks()) {
                    var similarity = dot(mine.vector(), theirs.vector());
                    var current = best.get(entry.getKey());
                    if (current == null || similarity > current.similarity()) {
                        best.put(entry.getKey(), new Similar(entry.getKey(), theirs.index(), theirs.heading(), similarity));
                    }
                }
            }
        }
        var result = new ArrayList<>(best.values());
        result.sort(Comparator.comparingDouble(Similar::similarity).reversed());
        return result.subList(0, Math.min(limit, result.size()));
    }

    private static double dot(float[] a, float[] b) {
        var sum = 0.0;
        for (var i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
