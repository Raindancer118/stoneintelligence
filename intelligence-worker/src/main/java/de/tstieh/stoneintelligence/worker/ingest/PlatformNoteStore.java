package de.tstieh.stoneintelligence.worker.ingest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.protection.ProtectionPolicy;
import de.tstieh.stoneintelligence.stoneai.vault.IndexedNote;
import de.tstieh.stoneintelligence.stoneai.vault.NoteStore;
import de.tstieh.stoneintelligence.stoneai.vault.NoteWriteRefusedException;
import de.tstieh.stoneintelligence.worker.platform.ListedNote;
import de.tstieh.stoneintelligence.worker.platform.PlatformApi;
import de.tstieh.stoneintelligence.worker.platform.PlatformRefusedException;

/**
 * Der gemeinsame Vault als {@link NoteStore} der StoneAI-Pipeline: jede Notiz liegt unter der
 * virtuellen Wurzel {@link #ROOT}, gelesen und geschrieben wird ueber platform-api. Von Menschen
 * angelegte Notizen werden verlinkt, aber nie veraendert; neue Notizen bekommen das Level des
 * Quelldokuments.
 */
final class PlatformNoteStore implements NoteStore {

    static final Path ROOT = Path.of("/vault");

    private final PlatformApi platform;
    private final String vaultId;
    private final UUID changeSetId;
    private final int level;
    private final Map<String, ListedNote> byPath = new LinkedHashMap<>();

    private PlatformNoteStore(PlatformApi platform, String vaultId, UUID changeSetId, int level) {
        this.platform = platform;
        this.vaultId = vaultId;
        this.changeSetId = changeSetId;
        this.level = level;
    }

    static PlatformNoteStore load(PlatformApi platform, String vaultId, UUID changeSetId, int level) {
        var store = new PlatformNoteStore(platform, vaultId, changeSetId, level);
        platform.notes(vaultId, changeSetId).forEach(note -> store.byPath.put(note.path(), note));
        return store;
    }

    @Override
    public boolean exists(Path file) {
        var path = relative(file);
        return path != null && byPath.containsKey(path);
    }

    @Override
    public String read(Path file) throws IOException {
        var note = byPath.get(relative(file));
        if (note == null) {
            throw new IOException("keine Notiz unter " + file);
        }
        return platform.read(vaultId, changeSetId, note.noteId());
    }

    @Override
    public void write(Path file, String content) throws IOException {
        var path = relative(file);
        if (path == null) {
            throw new NoteWriteRefusedException("liegt außerhalb des Vaults");
        }
        var existing = byPath.get(path);
        try {
            if (existing == null) {
                var noteId = platform.create(vaultId, changeSetId, path, content, level);
                byPath.put(path, new ListedNote(noteId, path, level, "ki:", "NOTE"));
            } else if (!existing.writtenByAi()) {
                throw new NoteWriteRefusedException("von einem Menschen angelegt - die KI verändert sie nicht");
            } else {
                platform.update(vaultId, changeSetId, existing.noteId(), content);
            }
        } catch (PlatformRefusedException refused) {
            throw new NoteWriteRefusedException(refused.getMessage());
        }
    }

    @Override
    public Path writeAttachment(Path file, byte[] content) throws IOException {
        var path = relative(file);
        if (path == null) {
            throw new NoteWriteRefusedException("liegt außerhalb des Vaults");
        }
        try {
            var stored = platform.storeFile(vaultId, changeSetId, path, content, "application/pdf", level);
            byPath.putIfAbsent(stored, new ListedNote(null, stored, level, "ki:", "FILE"));
            return ROOT.resolve(stored);
        } catch (PlatformRefusedException refused) {
            throw new NoteWriteRefusedException(refused.getMessage());
        }
    }

    /** Menschen-Notizen per Dateiname (ohne sie zu lesen), KI-Notizen mit Titel und Aliassen. */
    @Override
    public List<IndexedNote> indexable(StoneAiConfig config, ProtectionPolicy protection) throws IOException {
        var notes = new ArrayList<IndexedNote>();
        for (var note : byPath.values()) {
            if (note.isFile()) {
                continue;
            }
            var file = ROOT.resolve(note.path());
            notes.add(note.writtenByAi()
                ? IndexedNote.fromContent(file, platform.read(vaultId, changeSetId, note.noteId()))
                : new IndexedNote(file, IndexedNote.titleOf(file), List.of()));
        }
        return notes;
    }

    /** Vault-relativer Pfad mit {@code /}, oder {@code null} ausserhalb der Wurzel. */
    private static String relative(Path file) {
        var normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(ROOT) || normalized.equals(ROOT)) {
            return null;
        }
        return ROOT.relativize(normalized).toString().replace('\\', '/');
    }
}
