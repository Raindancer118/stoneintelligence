package de.raindancer118.stoneai.vault;

import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.source.SourceDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * Maintains one index note listing every document StoneAI has processed and the notes it
 * produced. Each document owns a managed block in it, so re-processing updates that document's
 * entry and nothing else — including anything the user added to the index by hand.
 */
public final class MocWriter {

    private static final String INDEX_TITLE = "StoneAI Index";

    private final StoneAiConfig config;
    private final Supplier<LocalDate> clock;
    private final boolean dryRun;
    private final NoteStore store;

    public MocWriter(StoneAiConfig config, Supplier<LocalDate> clock, boolean dryRun) {
        this(config, clock, dryRun, NoteStore.files());
    }

    public MocWriter(StoneAiConfig config, Supplier<LocalDate> clock, boolean dryRun, NoteStore store) {
        this.config = config;
        this.clock = clock;
        this.dryRun = dryRun;
        this.store = store;
    }

    public Path file() {
        return config.vault().mocDir().resolve(NoteFileName.forTitle(INDEX_TITLE));
    }

    public Path update(SourceDocument document, List<String> noteTitles, String sourceLink)
            throws IOException {
        Path file = file();
        String today = clock.get().toString();

        StringBuilder block = new StringBuilder();
        block.append("### ").append(sourceLink).append('\n')
                .append("*").append(noteTitles.size()).append(" Notizen, zuletzt ")
                .append(today).append("*\n\n");
        noteTitles.forEach(title -> block.append("- [[").append(title).append("]]\n"));

        String existing = store.exists(file) ? store.read(file) : "";
        Frontmatter.Document parsed = Frontmatter.of(existing);
        Frontmatter frontmatter = parsed.frontmatter()
                .mergeAdditively(Frontmatter.empty()
                        .withScalar("title", INDEX_TITLE)
                        .withList("tags", List.of(config.notes().tag(), "moc"))
                        .withScalar("type", "moc")
                        .withScalar("created", today))
                .withScalar("updated", today);

        String body = parsed.body().isBlank()
                ? "#" + config.notes().tag() + "\n\n# " + INDEX_TITLE + "\n"
                : parsed.body();

        String content = frontmatter.render() + "\n"
                + ManagedBlock.apply(body, "moc-" + document.sha256().substring(0, 8), block.toString());

        if (!dryRun) {
            store.write(file, content);
        }
        return file;
    }
}
