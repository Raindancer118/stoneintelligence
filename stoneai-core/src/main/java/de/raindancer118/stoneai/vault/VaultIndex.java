package de.raindancer118.stoneai.vault;

import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.note.TextSimilarity;
import de.raindancer118.stoneai.protection.ProtectionPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Knows which notes the vault already holds, so a run adds to what is there instead of creating
 * a near-duplicate beside it. Titles and aliases are indexed under a normalised key; a fuzzy
 * lookup catches the inflection differences that make {@code Äquivalenzrelation} and
 * {@code Äquivalenzrelationen} the same note.
 *
 * <p>Protected notes are left out entirely: they must be neither read nor written, so as far as
 * the rest of the pipeline is concerned they do not exist.
 */
public final class VaultIndex {

    private final Map<String, Path> byKey = new LinkedHashMap<>();
    private final Map<Path, String> titles = new LinkedHashMap<>();

    private VaultIndex() {
    }

    public static VaultIndex empty() {
        return new VaultIndex();
    }

    public static VaultIndex build(StoneAiConfig config, ProtectionPolicy protection) throws IOException {
        return build(config, protection, NoteStore.files());
    }

    public static VaultIndex build(StoneAiConfig config, ProtectionPolicy protection, NoteStore store) throws IOException {
        VaultIndex index = new VaultIndex();
        for (IndexedNote note : store.indexable(config, protection)) {
            index.register(note.title(), note.aliases(), note.file());
        }
        return index;
    }

    /** Adds a note to the index — used after writing, so one run does not duplicate itself. */
    public void register(String title, List<String> aliases, Path file) {
        titles.put(file, title);
        byKey.putIfAbsent(TextSimilarity.normalise(title), file);
        aliases.forEach(alias -> byKey.putIfAbsent(TextSimilarity.normalise(alias), file));
    }

    /** The file holding a note with this title, exactly or close enough. */
    public Optional<Path> resolve(String title, double threshold) {
        Path exact = byKey.get(TextSimilarity.normalise(title));
        if (exact != null) {
            return Optional.of(exact);
        }
        return byKey.entrySet().stream()
                .filter(entry -> TextSimilarity.similarity(entry.getKey(), title) >= threshold)
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public int size() {
        return titles.size();
    }

    public List<String> titles() {
        return new ArrayList<>(titles.values());
    }
}
