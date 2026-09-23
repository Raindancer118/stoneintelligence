package de.raindancer118.stoneai.vault;

import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.protection.ProtectionPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A vault that exists only in memory - stands in for a hosted vault the pipeline reaches over an API. */
public final class InMemoryNoteStore implements NoteStore {

    public final Map<Path, String> notes = new LinkedHashMap<>();
    public final Set<Path> refused = new HashSet<>();
    public final List<Path> writes = new ArrayList<>();

    @Override
    public boolean exists(Path file) {
        return notes.containsKey(file);
    }

    @Override
    public String read(Path file) {
        return notes.get(file);
    }

    @Override
    public void write(Path file, String content) throws IOException {
        if (refused.contains(file)) {
            throw new NoteWriteRefusedException("von einem Menschen angelegt");
        }
        writes.add(file);
        notes.put(file, content);
    }

    @Override
    public List<IndexedNote> indexable(StoneAiConfig config, ProtectionPolicy protection) {
        return notes.keySet().stream().map(file -> IndexedNote.fromContent(file, notes.get(file))).toList();
    }
}
