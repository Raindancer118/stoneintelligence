package de.tstieh.stoneintelligence.stoneai.extract;

/** What a model returned, plus what it cost — the token count drives the per-run budget. */
public record LlmAnswer(String text, int tokensUsed, String model) {
}
