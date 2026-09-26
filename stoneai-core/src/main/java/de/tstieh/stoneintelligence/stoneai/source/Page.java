package de.tstieh.stoneintelligence.stoneai.source;

/**
 * One page of a source document. {@code fromOcr} records that the text came from a vision model
 * rather than a text layer, which matters for how much the extraction should trust it.
 */
public record Page(int number, String text, boolean fromOcr) {
}
