package de.raindancer118.stoneintelligence.worker.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import de.raindancer118.stoneintelligence.worker.platform.ClaimedJob;
import de.raindancer118.stoneintelligence.worker.platform.ListedNote;
import de.raindancer118.stoneintelligence.worker.platform.PlatformApi;
import de.raindancer118.stoneintelligence.worker.platform.PlatformRefusedException;

/** platform-api im Kleinen: Notizen eines Vaults, Jobs, und was der Worker gemeldet hat. */
final class FakePlatform implements PlatformApi {

    record Stored(String noteId, String path, int level, String createdBy, String text) { }

    final Map<String, Stored> notes = new LinkedHashMap<>();
    final List<ClaimedJob> queue = new ArrayList<>();
    final Map<UUID, byte[]> documents = new LinkedHashMap<>();
    final List<String> events = new ArrayList<>();
    String agent = "ki:Gemini";

    void human(String path, String text, int level) {
        var id = UUID.randomUUID().toString();
        notes.put(id, new Stored(id, path, level, "tom", text));
    }

    @Override
    public Optional<ClaimedJob> claim() {
        return queue.isEmpty() ? Optional.empty() : Optional.of(queue.removeFirst());
    }

    @Override
    public byte[] document(UUID jobId) {
        return documents.get(jobId);
    }

    @Override
    public void progress(UUID jobId, String message, Integer percent) {
        events.add("progress " + percent + " " + message);
    }

    @Override
    public void complete(UUID jobId) {
        events.add("complete");
    }

    @Override
    public void fail(UUID jobId, String error, boolean retryable) {
        events.add((retryable ? "retry " : "fail ") + error);
    }

    @Override
    public List<ListedNote> notes(String vaultId, UUID changeSetId) {
        return notes.values().stream().map(n -> new ListedNote(n.noteId(), n.path(), n.level(), n.createdBy())).toList();
    }

    @Override
    public String read(String vaultId, UUID changeSetId, String noteId) {
        return notes.get(noteId).text();
    }

    @Override
    public String create(String vaultId, UUID changeSetId, String path, String text, int level) {
        if (notes.values().stream().anyMatch(n -> n.path().equals(path))) {
            throw new PlatformRefusedException("Unter " + path + " liegt schon eine Notiz");
        }
        var id = UUID.randomUUID().toString();
        notes.put(id, new Stored(id, path, level, agent, text));
        events.add("create " + path);
        return id;
    }

    @Override
    public void update(String vaultId, UUID changeSetId, String noteId, String text) {
        var note = notes.get(noteId);
        if (!note.createdBy().startsWith("ki:")) {
            throw new PlatformRefusedException("Notizen von Menschen verändert die KI nicht");
        }
        notes.put(noteId, new Stored(noteId, note.path(), note.level(), note.createdBy(), text));
        events.add("update " + note.path());
    }
}
