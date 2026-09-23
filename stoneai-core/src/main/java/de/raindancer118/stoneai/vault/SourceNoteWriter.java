package de.raindancer118.stoneai.vault;

import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.source.SourceDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * Writes the note that stands for a processed document: what was read, when, from which file,
 * and which notes came out of it. It is what makes a claim in the vault traceable — without it
 * the generated notes would cite a page number of a document nothing links to.
 *
 * <p>Like every other write it uses a managed block, so re-processing a document updates its
 * source note instead of piling up copies.
 */
public final class SourceNoteWriter {

    private final StoneAiConfig config;
    private final Supplier<LocalDate> clock;
    private final boolean dryRun;
    private final NoteStore store;

    public SourceNoteWriter(StoneAiConfig config, Supplier<LocalDate> clock, boolean dryRun) {
        this(config, clock, dryRun, NoteStore.files());
    }

    public SourceNoteWriter(StoneAiConfig config, Supplier<LocalDate> clock, boolean dryRun, NoteStore store) {
        this.config = config;
        this.clock = clock;
        this.dryRun = dryRun;
        this.store = store;
    }

    /** The wikilink other notes use to point at this document's source note. */
    public String linkFor(SourceDocument document) {
        String name = NoteFileName.forTitle(document.title());
        return "[[" + config.vault().sourcesFolder() + "/" + name.substring(0, name.length() - 3) + "]]";
    }

    public Path fileFor(SourceDocument document) {
        return config.vault().sourcesDir().resolve(NoteFileName.forTitle(document.title()));
    }

    public Path write(SourceDocument document, List<String> noteTitles, Path attachment) throws IOException {
        Path file = fileFor(document);
        String today = clock.get().toString();

        Frontmatter frontmatter = Frontmatter.empty()
                .withScalar("title", document.title())
                .withList("tags", List.of(config.notes().tag(), "quelle"))
                .withScalar("type", "source")
                .withScalar("document", document.file().getFileName().toString())
                .withScalar("kind", document.kind().name().toLowerCase(java.util.Locale.ROOT))
                .withScalar("pages", String.valueOf(document.pages().size()))
                .withScalar("sha256", document.sha256())
                .withScalar("created", today)
                .withScalar("updated", today);

        StringBuilder block = new StringBuilder();
        block.append("Verarbeitet am ").append(today).append(".\n\n");
        if (attachment != null) {
            block.append("**Original:** ![[").append(attachment.getFileName()).append("]]\n\n");
        } else if (store.localFiles()) {
            block.append("**Original:** `").append(document.file()).append("`\n\n");
        } else {
            block.append("**Original:** ").append(document.file().getFileName()).append("\n\n");
        }
        if (document.truncated()) {
            block.append("> Nur die ersten ").append(document.pages().size())
                    .append(" Seiten gelesen (`ingest.maxPages`).\n\n");
        }
        if (!document.skippedPages().isEmpty()) {
            block.append("> Übersprungene Seiten: ").append(document.skippedPages()).append("\n\n");
        }
        block.append("## Abgeleitete Notizen\n\n");
        if (noteTitles.isEmpty()) {
            block.append("_Keine._\n");
        } else {
            noteTitles.forEach(title -> block.append("- [[").append(title).append("]]\n"));
        }

        String blockId = "src-" + document.sha256().substring(0, 8);
        String existing = store.exists(file) ? store.read(file) : "";
        Frontmatter.Document parsed = Frontmatter.of(existing);
        String content = parsed.frontmatter().mergeAdditively(frontmatter).withScalar("updated", today).render()
                + "\n#" + config.notes().tag() + "\n\n"
                + ManagedBlock.apply(stripLeadingTag(parsed.body()), blockId, block.toString());

        if (!dryRun) {
            store.write(file, content);
        }
        return file;
    }

    /** Drops the inline tag line so re-rendering does not stack it up. */
    private String stripLeadingTag(String body) {
        String marker = "#" + config.notes().tag();
        String trimmed = body.stripLeading();
        return trimmed.startsWith(marker) ? trimmed.substring(marker.length()).stripLeading() : body;
    }
}
