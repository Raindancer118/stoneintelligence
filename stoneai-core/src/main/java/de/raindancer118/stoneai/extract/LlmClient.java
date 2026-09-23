package de.raindancer118.stoneai.extract;

/**
 * The pipeline's view of a language model. Kept deliberately small so the whole extraction is
 * testable without network access; the real implementation sits in the CLI module and routes
 * through the AI gateway's provider chains.
 */
public interface LlmClient {

    LlmAnswer complete(Tier tier, String system, String user);

    LlmAnswer readImage(byte[] pngImage, String prompt);
}
