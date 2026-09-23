package de.raindancer118.stoneai.extract;

/** The prompt used to read text off a scanned page. Public so the pipeline can reach it. */
public final class VisionPrompts {

    private VisionPrompts() {
    }

    public static String ocr(int pageNumber, String language) {
        return Prompts.ocr(pageNumber, language);
    }
}
