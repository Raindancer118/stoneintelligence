package de.tstieh.stoneintelligence.stoneai.source;

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
 * @param unreadablePages image pages the vision model could not read (provider unavailable) -
 *                   unlike skipped pages these hold content that is missing from the notes
 */
public record SourceDocument(Path file,
                             String title,
                             DocumentKind kind,
                             List<Page> pages,
                             List<Integer> skippedPages,
                             boolean truncated,
                             String sha256,
                             List<Integer> unreadablePages) {

    public SourceDocument {
        pages = List.copyOf(pages);
        skippedPages = List.copyOf(skippedPages);
        unreadablePages = List.copyOf(unreadablePages);
    }

    public SourceDocument(Path file, String title, DocumentKind kind, List<Page> pages, List<Integer> skippedPages,
                          boolean truncated, String sha256) {
        this(file, title, kind, pages, skippedPages, truncated, sha256, List.of());
    }

    /** A dense book page, for sizing documents without pages (Markdown, text). */
    private static final int CHARS_PER_PAGE = 3_000;

    /**
     * How long the document is, in pages - for its token budget and its note limit. A text file is
     * one page however long it is, so it counts by its length.
     */
    public int lengthInPages() {
        long chars = pages.stream().mapToLong(page -> page.text().length()).sum();
        return (int) Math.max(pages.size(), chars / CHARS_PER_PAGE);
    }

    public String fullText() {
        return pages.stream().map(Page::text).reduce((a, b) -> a + "\n\n" + b).orElse("");
    }
}
