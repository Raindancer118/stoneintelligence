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

    public WriteResult write(DraftNote note, String documentHash, String sourceLink) throws IOException {
        return write(note, documentHash, sourceLink, null);
    }

    /**
     * @param documentHash hash of the source document — makes the managed block id stable across
     *                     runs and distinct per source
     * @param sourceLink   a wikilink to the source note; citations link there when there is no original
     * @param original     the original document stored in the vault, or {@code null} - page
     *                     citations of a PDF open it at that page
     */
    public WriteResult write(DraftNote note, String documentHash, String sourceLink, Path original) throws IOException {
        Path file = fileFor(note);
        // Register before rendering, so a note can be linked from the very block that creates it.
        index.register(note.title(), note.aliases(), file);
        String blockId = blockId(documentHash, note);
        String block = renderBlock(note, file, sourceLink, original);

        if (!store.exists(file)) {
            String content = renderNewNote(note, file, blockId, block);
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
        Frontmatter merged = withoutLegacyProperties(document.frontmatter(), file)
                .mergeAdditively(frontmatterFor(note, file))
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

    private String renderNewNote(DraftNote note, Path file, String blockId, String block) {
        String header = frontmatterFor(note, file).render();
        String body = "#" + config.notes().tag() + "\n\n"
                + ManagedBlock.apply("", blockId, block);
        return header + "\n" + body;
    }

    /** The generated text itself: definition first, then the body, then where it came from. */
    private String renderBlock(DraftNote note, Path self, String sourceLink, Path original) {
        StringBuilder block = new StringBuilder();
        if (note.definition() != null && !note.definition().isBlank()) {
            block.append("> ").append(resolveLinks(de.raindancer118.stoneai.note.MathDelimiters.forObsidian(note.definition().strip()))
                    .replace("\n", "\n> ")).append("\n\n");
        }
        block.append(escapeLinksInTables(resolveLinks(de.raindancer118.stoneai.note.MathDelimiters.forObsidian(note.body().strip()))));

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

        String sources = note.sources().stream()
                .collect(java.util.stream.Collectors.toMap(Provenance::label, source -> source, (a, b) -> a,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .map(source -> cite(source, sourceLink, original))
                .collect(java.util.stream.Collectors.joining("; "));
        if (!sources.isBlank()) {
            block.append("\n\n*Quelle: ").append(sources).append('*');
        }
        return block.toString();
    }

    /**
     * One citation, as a link to where it can be checked: the page of the stored PDF (Obsidian
     * opens it there), else the source note. Plain text only when neither exists.
     */
    private String cite(Provenance source, String sourceLink, Path original) {
        String label = source.label().replace("|", "-").replace("]", ")").replace("[", "(");
        if (original != null && source.page() != null && original.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            Path root = config.vault().resolvedPath();
            String target = original.startsWith(root)
                    ? root.relativize(original).toString().replace('\\', '/')
                    : original.getFileName().toString();
            return "[[" + target + "#page=" + source.page() + "|" + label + "]]";
        }
        if (sourceLink != null && sourceLink.startsWith("[[") && sourceLink.endsWith("]]")) {
            String target = sourceLink.substring(2, sourceLink.length() - 2);
            int alias = target.indexOf('|');
            return "[[" + (alias >= 0 ? target.substring(0, alias) : target) + "|" + label + "]]";
        }
        return label;
    }

    private static final java.util.regex.Pattern ALIASED_LINK =
            java.util.regex.Pattern.compile("(\\[\\[[^\\]|]*?)(?<!\\\\)\\|([^\\]]*\\]\\])");

    /**
     * In a Markdown table the | of {@code [[Ziel|Text]]} would end the cell - Obsidian expects it
     * escaped there. Everywhere else the link stays as it is.
     */
    private static String escapeLinksInTables(String text) {
        return text.lines()
                .map(line -> line.stripLeading().startsWith("|")
                        ? ALIASED_LINK.matcher(line).replaceAll("$1\\\\|$2")
                        : line)
                .collect(java.util.stream.Collectors.joining("\n"));
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
            // In einer Tabelle schreibt das Modell [[Ziel\\|Text]] - der Backslash gehoert nicht zum Ziel.
            String target = matcher.group(1).strip().replaceAll("\\\\+$", "");
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

    /**
     * Only what Obsidian itself uses (aliases, tags) and the dates. Where a note comes from stands
     * linked in its text, related notes under "Siehe auch" - as properties they only got in the way
     * of reading (issue #1). The title is kept only where the file name had to differ from it.
     */
    private Frontmatter frontmatterFor(DraftNote note, Path file) {
        Frontmatter frontmatter = Frontmatter.empty();
        if (!IndexedNote.titleOf(file).equals(note.title())) {
            frontmatter = frontmatter.withScalar("title", note.title());
        }
        if (!note.aliases().isEmpty()) {
            frontmatter = frontmatter.withList("aliases", note.aliases());
        }
        return frontmatter
                .withList("tags", tagsFor(note))
                .withScalar("created", today())
                .withScalar("updated", today());
    }

    /** Bookkeeping earlier versions wrote into the properties of their own notes. */
    private static final Set<String> LEGACY_PROPERTIES =
            Set.of("type", "source", "source_page", "entities", "related", "confidence");

    /**
     * A note the AI created ({@code type: concept}) loses the bookkeeping properties of earlier
     * versions; a note a person started keeps every property as it is.
     */
    private static Frontmatter withoutLegacyProperties(Frontmatter frontmatter, Path file) {
        if (!"concept".equals(frontmatter.scalar("type"))) {
            return frontmatter;
        }
        Set<String> drop = new java.util.HashSet<>(LEGACY_PROPERTIES);
        if (IndexedNote.titleOf(file).equals(frontmatter.scalar("title"))) {
            drop.add("title");
        }
        return frontmatter.without(drop);
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
