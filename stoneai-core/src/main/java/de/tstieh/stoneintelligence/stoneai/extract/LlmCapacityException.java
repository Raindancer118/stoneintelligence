package de.tstieh.stoneintelligence.stoneai.extract;

import java.time.Instant;

/**
 * Every model the run may use is out of capacity (rate limit, used-up quota) - not for one call,
 * but until {@link #availableAgainAt()}. Unlike an unusable answer this is never worked around
 * (a skipped page, unmerged text): the run stops before writing anything, so it can be repeated
 * in full once capacity is back.
 */
public class LlmCapacityException extends IllegalStateException {

    private final transient Instant availableAgainAt;

    public LlmCapacityException(String message, Instant availableAgainAt) {
        super(message);
        this.availableAgainAt = availableAgainAt;
    }

    public LlmCapacityException(String message, Instant availableAgainAt, Throwable cause) {
        super(message, cause);
        this.availableAgainAt = availableAgainAt;
    }

    /** When a model is expected to answer again, or {@code null} if no provider said. */
    public Instant availableAgainAt() {
        return availableAgainAt;
    }
}
