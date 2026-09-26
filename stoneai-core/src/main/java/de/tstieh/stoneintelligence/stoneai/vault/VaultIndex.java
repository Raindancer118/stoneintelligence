package de.tstieh.stoneintelligence.stoneai.vault;

import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.note.TextSimilarity;
import de.tstieh.stoneintelligence.stoneai.protection.ProtectionPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;

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
    /** Titles and aliases as written - the fuzzy comparison normalises them itself. */
    private final Map<String, Path> byName = new LinkedHashMap<>();
    private final Map<Path, String> titles = new LinkedHashMap<>();
    /** Every file a link could collide with, including source notes and attachments. */
    private final Set<Path> files = new LinkedHashSet<>();

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
            // Source notes and the index are the pipeline's own bookkeeping: a topic that happens
            // to share a document's name must not be written into its source note.
            if (note.file().startsWith(config.vault().sourcesDir()) || note.file().startsWith(config.vault().mocDir())) {
                index.reserve(note.file());
            } else {
                index.register(note.title(), note.aliases(), note.file());
            }
        }
        return index;
    }

    /** Adds a note to the index — used after writing, so one run does not duplicate itself. */
    public void register(String title, List<String> aliases, Path file) {
        titles.putIfAbsent(file, title);
        files.add(file);
        add(title, file);
        aliases.forEach(alias -> add(alias, file));
    }

    /** A file links must not be confused with, which is not itself a note to write into. */
    public void reserve(Path file) {
        files.add(file);
    }

    private void add(String name, Path file) {
        byKey.putIfAbsent(TextSimilarity.normalise(name), file);
        byName.putIfAbsent(TextSimilarity.plain(name), file);
    }

    /** The file holding a note with this title, exactly or close enough. */
    public Optional<Path> resolve(String title, double threshold) {
        Path exact = byKey.get(TextSimilarity.normalise(title));
        if (exact != null) {
            return Optional.of(exact);
        }
        return byName.entrySet().stream()
                .filter(entry -> TextSimilarity.sameConcept(entry.getKey(), title, threshold))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** The title of the note in {@code file} - the one it had first, not a later variant. */
    public String titleOf(Path file) {
        return titles.getOrDefault(file, baseName(file));
    }

    /**
     * A wikilink Obsidian resolves to exactly {@code file}: by file name, since that is what
     * Obsidian matches, with the title as display text where the two differ, and with the path
     * when another file shares the name.
     */
    public String linkTo(Path file, Path vaultRoot) {
        String name = baseName(file);
        boolean ambiguous = files.stream().anyMatch(other -> !other.equals(file)
                && NoteFileName.sameFile(baseName(other), name));
        String target = ambiguous && file.startsWith(vaultRoot)
                ? stripMd(vaultRoot.relativize(file).toString().replace('\\', '/'))
                : name;
        String title = titleOf(file);
        return title.equals(name) ? "[[" + target + "]]" : "[[" + target + "|" + title + "]]";
    }

    private static String baseName(Path file) {
        return stripMd(file.getFileName().toString());
    }

    private static String stripMd(String name) {
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    public int size() {
        return titles.size();
    }

    public List<String> titles() {
        return new ArrayList<>(titles.values());
    }
}
