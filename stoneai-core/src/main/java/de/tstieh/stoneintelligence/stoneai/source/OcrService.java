package de.tstieh.stoneintelligence.stoneai.source;

/**
 * Reads text off a rendered page image. The real implementation asks a vision model through the
 * AI gateway; tests supply a fake, which keeps the loaders offline-testable.
 */
@FunctionalInterface
public interface OcrService {

    /**
     * @param pngImage   the rendered page as PNG bytes
     * @param pageNumber the one-based page number, for prompts and error messages
     * @return the transcribed text, empty when the page holds none
     */
    String read(byte[] pngImage, int pageNumber);
}
