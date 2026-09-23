package de.raindancer118.stoneai.chunk;

import java.nio.file.Path;

/**
 * Where a piece of text came from. Every generated note cites this, so a claim in the vault can
 * always be traced back to the page or section it was read from.
 *
 * @param page        one-based page number, or {@code null} for documents without pages
 * @param headingPath the chain of Markdown headings, e.g. {@code "Relationen > Äquivalenzrelation"}
 * @param lastPage    the last page when the text spans several (slides packed together), else {@code null}
 */
public record Provenance(String documentTitle, Path file, Integer page, String headingPath, Integer lastPage) {

    public Provenance(String documentTitle, Path file, Integer page, String headingPath) {
        this(documentTitle, file, page, headingPath, null);
    }

    /** A short human reference like {@code "Skript, S. 42"}, {@code "Folien, S. 12–18"} or {@code "Notiz — Relationen"}. */
    public String label() {
        if (page != null) {
            return documentTitle + ", S. " + page + (lastPage != null && !lastPage.equals(page) ? "–" + lastPage : "");
        }
        return headingPath == null || headingPath.isBlank()
                ? documentTitle
                : documentTitle + " — " + headingPath;
    }
}
