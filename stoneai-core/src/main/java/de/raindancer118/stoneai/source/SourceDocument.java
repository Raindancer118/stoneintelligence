package de.raindancer118.stoneai.source;

import java.nio.file.Path;
import java.util.List;

/**
 * A document after loading: its readable pages plus the provenance the notes will cite.
 *
 * @param file       where the document came from
 * @param title      a human title, from metadata or the file name
 * @param kind       what type of document this was
 * @param pages      the pages that could be read, in order
 * @param skippedPages page numbers that were deliberately not read (image pages without vision)
 * @param truncated  whether reading stopped at the configured page limit
 * @param sha256     hash of the file, so a re-run recognises the same document
 */
public record SourceDocument(Path file,
                             String title,
                             DocumentKind kind,
                             List<Page> pages,
                             List<Integer> skippedPages,
                             boolean truncated,
                             String sha256) {

    public SourceDocument {
        pages = List.copyOf(pages);
        skippedPages = List.copyOf(skippedPages);
    }

    public String fullText() {
        return pages.stream().map(Page::text).reduce((a, b) -> a + "\n\n" + b).orElse("");
    }
}
