package de.raindancer118.stoneai.vault;

import de.raindancer118.stoneai.chunk.Provenance;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.note.DraftNote;
import de.raindancer118.stoneai.protection.ProtectionDecision;
import de.raindancer118.stoneai.protection.ProtectionPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Writes notes into the vault. The rule this class exists to enforce: <em>nothing a person wrote
 * is ever changed.</em>
 *
 * <p>Generated text goes into a {@link ManagedBlock} whose id is derived from the source document
 * and the note title, so a re-run updates exactly its own block, two documents contributing to
 * the same note keep separate blocks, and every byte outside those blocks is copied through
 * untouched. Frontmatter is merged additively — new keys are added, lists are unioned, existing
 * values win. A note carrying the protection tag is not touched at all.
 */
public final class VaultWriter {

    private final StoneAiConfig config;
    private final ProtectionPolicy protection;
    private final Supplier<LocalDate> clock;
    private final VaultIndex index;
    private final NoteStore store;
    private final boolean dryRun;

    public VaultWriter(StoneAiConfig config, ProtectionPolicy protection, Supplier<LocalDate> clock) {
        this(config, protection, clock, VaultIndex.empty(), NoteStore.files(), false);
    }

    public VaultWriter(StoneAiConfig config, ProtectionPolicy protection,
                       Supplier<LocalDate> clock, VaultIndex index) {
        this(config, protection, clock, index, NoteStore.files(), false);
    }

    public VaultWriter(StoneAiConfig config, ProtectionPolicy protection,
                       Supplier<LocalDate> clock, VaultIndex index, NoteStore store) {
        this(config, protection, clock, index, store, false);
    }

    private VaultWriter(StoneAiConfig config, ProtectionPolicy protection,
                        Supplier<LocalDate> clock, VaultIndex index, NoteStore store, boolean dryRun) {
        this.config = config;
        this.protection = protection;
        this.clock = clock;
        this.index = index;
        this.store = store;
        this.dryRun = dryRun;
    }

    /** A writer that reports what it would do without touching the vault. */
    public VaultWriter dryRun() {
        return new VaultWriter(config, protection, clock, index, store, true);
    }

    /**
     * @param documentHash hash of the source document — makes the managed block id stable across
     *                     runs and distinct per source
     * @param sourceLink   a wikilink to the source note, written into the frontmatter
     */
    public WriteResult write(DraftNote note, String documentHash, String sourceLink) throws IOException {
        Path file = fileFor(note);
        // Register before rendering, so a note can be linked from the very block that creates it.
        index.register(note.title(), note.aliases(), file);
        String blockId = blockId(documentHash, note);
        String block = renderBlock(note, file);

        if (!store.exists(file)) {
            String content = renderNewNote(note, sourceLink, blockId, block);
            if (!dryRun) {
                try {
                    store.write(file, content);
                } catch (NoteWriteRefusedException refused) {
                    return WriteResult.skipped(file, refused.getMessage());
                }
            }
            return WriteResult.created(file, content);
        }

        String existing = store.read(file);
        ProtectionDecision decision = protection.inspectText(existing);
        if (decision.isProtected() && config.protection().protectExistingNotes()) {
            return WriteResult.skipped(file, "Notiz ist geschützt: " + decision.reason());
        }

        Frontmatter.Document document = Frontmatter.of(existing);
        Frontmatter merged = document.frontmatter()
                .mergeAdditively(frontmatterFor(note, sourceLink))
                .withScalar("updated", today());
        String body = ManagedBlock.apply(document.body(), blockId, block);
        String content = merged.render() + "\n" + body;

        if (content.equals(existing)) {
            return WriteResult.unchanged(file);
        }
        if (!dryRun) {
            try {
                store.write(file, content);
            } catch (NoteWriteRefusedException refused) {
                return WriteResult.skipped(file, refused.getMessage());
            }
        }
        return WriteResult.appended(file, content);
    }

    /**
     * Where a note belongs: an existing note with this title (or a close enough one, or a
     * matching alias) if the vault already has one, otherwise a fresh file in the notes folder.
     * Without this lookup a second run under a slightly different title would grow a pile of
     * near-duplicates beside the note it should have extended.
     */
    public Path fileFor(DraftNote note) {
        return index.resolve(note.title(), config.notes().similarityThreshold())
                .orElseGet(() -> config.vault().notesDir().resolve(NoteFileName.forTitle(note.title())));
    }

    /**
     * A stable id per (document, note): the same run twice updates one block, while a second
     * document contributing to the same note gets its own.
     */
    public static String blockId(String documentHash, DraftNote note) {
        String prefix = documentHash.length() > 8 ? documentHash.substring(0, 8) : documentHash;
        String key = Integer.toHexString(note.key().hashCode());
        return prefix + "-" + key;
    }

    private String renderNewNote(DraftNote note, String sourceLink, String blockId, String block) {
        String header = frontmatterFor(note, sourceLink).render();
        String body = "#" + config.notes().tag() + "\n\n"
                + ManagedBlock.apply("", blockId, block);
        return header + "\n" + body;
    }

    /** The generated text itself: definition first, then the body, then where it came from. */
    private String renderBlock(DraftNote note, Path self) {
        StringBuilder block = new StringBuilder();
        if (note.definition() != null && !note.definition().isBlank()) {
            block.append("> ").append(resolveLinks(note.definition().strip()).replace("\n", "\n> ")).append("\n\n");
        }
        block.append(resolveLinks(note.body().strip()));

        // Only link to notes that exist or are being written in this same run. A vault full of
        // broken links is worse than no links: Obsidian's graph fills up with phantom nodes and
        // every one of them looks like a note someone forgot to write.
        List<String> links = note.related().stream()
                .map(title -> index.resolve(title, config.notes().similarityThreshold()))
                .flatMap(java.util.Optional::stream)
                .filter(file -> !file.equals(self))
                .distinct()
                .map(file -> index.linkTo(file, config.vault().resolvedPath()))
                .toList();
        if (!links.isEmpty()) {
            block.append("\n\n**Siehe auch:** ").append(String.join(", ", links));
        }

        String sources = note.sources().stream().map(Provenance::label).distinct()
                .reduce((a, b) -> a + "; " + b).orElse("");
        if (!sources.isBlank()) {
            block.append("\n\n*Quelle: ").append(sources).append('*');
        }
        return block.toString();
    }

    private static final java.util.regex.Pattern WIKILINK =
            java.util.regex.Pattern.compile("(?<!!)\\[\\[([^\\]|#]+)(#[^\\]|]*)?(?:\\|([^\\]]*))?\\]\\]");

    /**
     * The model's own [[links]]: rewritten to the file they resolve to, or reduced to plain text
     * when there is no such note - a dangling link is never written.
     */
    private String resolveLinks(String text) {
        java.util.regex.Matcher matcher = WIKILINK.matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String target = matcher.group(1).strip();
            String shown = matcher.group(3) == null ? target : matcher.group(3).strip();
            String replacement = index.resolve(target, config.notes().similarityThreshold())
                    .map(file -> {
                        String link = index.linkTo(file, config.vault().resolvedPath());
                        String name = link.substring(2, link.indexOf('|') > 0 ? link.indexOf('|') : link.length() - 2);
                        return name.equals(shown) ? "[[" + name + "]]" : "[[" + name + "|" + shown + "]]";
                    })
                    .orElse(shown);
            matcher.appendReplacement(resolved, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private Frontmatter frontmatterFor(DraftNote note, String sourceLink) {
        Frontmatter frontmatter = Frontmatter.empty()
                .withScalar("title", note.title())
                .withList("aliases", note.aliases())
                .withList("tags", tagsFor(note))
                .withScalar("type", "concept");

        if (sourceLink != null && !sourceLink.isBlank()) {
            frontmatter = frontmatter.withScalar("source", sourceLink);
        }
        Integer page = note.sources().stream()
                .map(Provenance::page)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (page != null) {
            frontmatter = frontmatter.withScalar("source_page", String.valueOf(page));
        }
        if (!note.entities().isEmpty()) {
            frontmatter = frontmatter.withNested("entities", note.entities());
        }
        // Plain titles, not links: they cost nothing while missing and let a later run connect them.
        if (!note.related().isEmpty()) {
            frontmatter = frontmatter.withList("related", note.related());
        }
        return frontmatter
                .withScalar("created", today())
                .withScalar("updated", today())
                .withScalar("confidence", trim(note.confidence()));
    }

    private List<String> tagsFor(DraftNote note) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(config.notes().tag());
        tags.addAll(config.notes().extraTags());
        tags.addAll(note.tags());
        return new ArrayList<>(tags);
    }

    private String today() {
        return clock.get().toString();
    }

    private static String trim(double value) {
        String rendered = Double.toString(value);
        return rendered.endsWith(".0") ? rendered.substring(0, rendered.length() - 2) : rendered;
    }

    static void writeAtomically(Path file, String content) throws IOException {
        NoteStore.files().write(file, content);
    }
}
