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
        String name = fileFor(document).getFileName().toString();
        return "[[" + config.vault().sourcesFolder() + "/" + name.substring(0, name.length() - 3) + "]]";
    }

    /**
     * The source note's file. Vaults written before file names kept their spaces have it as
     * {@code DnD-Charaktere.md} - that file is used on, so a document never gets two source notes.
     */
    public Path fileFor(SourceDocument document) {
        Path current = config.vault().sourcesDir().resolve(NoteFileName.forTitle(document.title()));
        Path legacy = config.vault().sourcesDir().resolve(NoteFileName.forTitle(document.title()).replace(' ', '-'));
        return !store.exists(current) && store.exists(legacy) ? legacy : current;
    }

    /** @param noteLinks wikilinks to the notes of this run - already resolved to existing files */
    public Path write(SourceDocument document, List<String> noteLinks, Path attachment) throws IOException {
        return write(document, noteLinks, attachment, List.of(), List.of());
    }

    /**
     * @param unread   parts the run did not get to (token budget) - named so nobody takes the
     *                 notes for the whole document
     * @param unusable parts whose model answers stayed unusable after a repair round
     */
    public Path write(SourceDocument document, List<String> noteLinks, Path attachment,
                      List<String> unread, List<String> unusable) throws IOException {
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
        if (!unread.isEmpty()) {
            block.append("> [!warning] Nicht verarbeitet (Token-Budget des Laufs erschöpft): ")
                    .append(String.join("; ", unread)).append("\n\n");
        }
        if (!unusable.isEmpty()) {
            block.append("> [!warning] Nicht auswertbar (Antwort des Modells unbrauchbar): ")
                    .append(String.join("; ", unusable)).append("\n\n");
        }
        if (!document.skippedPages().isEmpty()) {
            block.append("> Übersprungene Seiten: ").append(document.skippedPages()).append("\n\n");
        }
        if (!store.localFiles()) {
            // Gehostet (StoneIntelligence): das Plugin oeffnet unter dieser Adresse den Dialog.
            block.append("[KI-Änderungen anzeigen oder rückgängig machen](obsidian://stoneintelligence-ai-changes)\n\n");
        }
        block.append("## Abgeleitete Notizen\n\n");
        if (noteLinks.isEmpty()) {
            block.append("_Keine._\n");
        } else {
            noteLinks.forEach(link -> block.append("- ").append(link).append('\n'));
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
