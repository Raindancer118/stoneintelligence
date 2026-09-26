package de.tstieh.stoneintelligence.worker.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.worker.platform.ClaimedJob;
import de.tstieh.stoneintelligence.worker.platform.ListedNote;
import de.tstieh.stoneintelligence.worker.platform.PlatformApi;
import de.tstieh.stoneintelligence.worker.platform.PlatformRefusedException;

/** platform-api im Kleinen: Notizen eines Vaults, Jobs, und was der Worker gemeldet hat. */
class FakePlatform implements PlatformApi {

    record Stored(String noteId, String path, int level, String createdBy, String text, String kind) {

        Stored(String noteId, String path, int level, String createdBy, String text) {
            this(noteId, path, level, createdBy, text, "NOTE");
        }
    }

    final Map<String, byte[]> files = new LinkedHashMap<>();

    final Map<String, Stored> notes = new LinkedHashMap<>();
    final List<ClaimedJob> queue = new ArrayList<>();
    final Map<UUID, byte[]> documents = new LinkedHashMap<>();
    final List<String> events = new ArrayList<>();
    String agent = "ki:Gemini";
    final List<String> services = new ArrayList<>(List.of("gemini"));
    final Map<String, List<de.tstieh.stoneintelligence.worker.platform.ProviderReport>> capacityReports = new LinkedHashMap<>();
    /** Nach so vielen Fortschrittsmeldungen gilt der Job als abgebrochen (-1 = nie). */
    int cancelAfterProgress = -1;
    private int progressCalls;
    private boolean cancelled;

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
        if (cancelled || cancelAfterProgress >= 0 && progressCalls++ >= cancelAfterProgress) {
            cancelled = true;
            throw new de.tstieh.stoneintelligence.worker.platform.JobGoneException("HTTP 410: Dieser Job läuft nicht (mehr)");
        }
        events.add("progress " + percent + " " + message);
    }

    @Override
    public void waitForCapacity(UUID jobId, String error, java.time.Instant availableAt) {
        events.add("wait " + availableAt + " " + error);
    }

    @Override
    public List<String> services() {
        return List.copyOf(services);
    }

    @Override
    public void reportCapacity(String serviceId, List<de.tstieh.stoneintelligence.worker.platform.ProviderReport> providers) {
        capacityReports.put(serviceId, List.copyOf(providers));
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
        return notes.values().stream().map(n -> new ListedNote(n.noteId(), n.path(), n.level(), n.createdBy(), n.kind())).toList();
    }

    @Override
    public String read(String vaultId, UUID changeSetId, String noteId) {
        var note = notes.get(noteId);
        if ("FILE".equals(note.kind())) {
            throw new PlatformRefusedException("Das ist eine Datei, keine Notiz");
        }
        return note.text();
    }

    @Override
    public String create(String vaultId, UUID changeSetId, String path, String text, int level) {
        if (cancelled) {
            throw new PlatformRefusedException("HTTP 422: Diese KI-Änderung wurde rückgängig gemacht");
        }
        if (notes.values().stream().anyMatch(n -> n.path().equals(path))) {
            throw new PlatformRefusedException("Unter " + path + " liegt schon eine Notiz");
        }
        var id = UUID.randomUUID().toString();
        notes.put(id, new Stored(id, path, level, agent, text));
        events.add("create " + path);
        return id;
    }

    @Override
    public String storeFile(String vaultId, UUID changeSetId, String path, byte[] content, String contentType, int level) {
        var taken = notes.values().stream().anyMatch(n -> n.path().equals(path));
        var stored = taken ? path.replace(".pdf", " (2).pdf") : path;
        var id = UUID.randomUUID().toString();
        notes.put(id, new Stored(id, stored, level, agent, null, "FILE"));
        files.put(stored, content);
        events.add("file " + stored);
        return stored;
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

    /** Wie der Server: nur Markup einfuegen, an der ersten verlinkbaren Stelle. */
    @Override
    public int link(String vaultId, UUID changeSetId, String noteId, List<de.tstieh.stoneintelligence.worker.platform.ProposedLink> links) {
        var note = notes.get(noteId);
        var text = note.text();
        var applied = 0;
        for (var link : links) {
            var targetPath = notes.get(link.target()).path();
            var target = targetPath.substring(targetPath.lastIndexOf('/') + 1).replaceAll("\\.md$", "");
            var inserted = de.tstieh.stoneintelligence.domain.link.LinkText.insert(text, target, link.anchor(), link.allowRelated());
            if (inserted.isPresent()) {
                text = inserted.get().text();
                applied++;
            }
        }
        notes.put(noteId, new Stored(noteId, note.path(), note.level(), note.createdBy(), text));
        events.add("link " + note.path() + " " + applied);
        return applied;
    }
}
