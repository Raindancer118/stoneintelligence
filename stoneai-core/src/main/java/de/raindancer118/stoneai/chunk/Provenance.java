package de.raindancer118.stoneai.chunk;

import java.nio.file.Path;

/**
 * Where a piece of text came from. Every generated note cites this, so a claim in the vault can
 * always be traced back to the page or section it was read from.
 *
 * @param page        one-based page number, or {@code null} for documents without pages
 * @param headingPath the chain of Markdown headings, e.g. {@code "Relationen > Äquivalenzrelation"}
 */
public record Provenance(String documentTitle, Path file, Integer page, String headingPath) {

    /** A short human reference like {@code "Skript, S. 42"} or {@code "Notiz — Relationen"}. */
    public String label() {
        if (page != null) {
            return documentTitle + ", S. " + page;
        }
        return headingPath == null || headingPath.isBlank()
                ? documentTitle
                : documentTitle + " — " + headingPath;
    }
}
