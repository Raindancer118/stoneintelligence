package de.raindancer118.stoneintelligence.platform.ratelimit;

import java.time.Duration;

public record RateLimitResult(boolean allowed, Duration retryAfter) {

    static RateLimitResult permit() {
        return new RateLimitResult(true, Duration.ZERO);
    }

    static RateLimitResult reject(Duration retryAfter) {
        return new RateLimitResult(false, retryAfter);
    }
}
