package de.raindancer118.stoneintelligence.platform.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token-Bucket-Rate-Limiter (Plan.md Abschnitt 4.4, "ab Phase 3"). Bewusst selbst implementiert
 * statt einer Drittbibliothek (Bucket4j o.ae.) - der Algorithmus ist klein, exhaustiv testbar
 * und braucht keine ungeprueften API-Annahmen ueber eine konkrete Library-Version.
 */
class RateLimiterTest {

    private final Instant now = Instant.parse("2026-09-21T10:00:00Z");

    private RateLimiter limiterAt(Instant instant, int capacity, double refillPerSecond) {
        return new RateLimiter(new MutableClock(instant, ZoneOffset.UTC), capacity, refillPerSecond);
    }

    @Nested
    class WithinCapacity {

        @Test
        void should_allow_when_bucketHasUnusedCapacity() {
            var limiter = limiterAt(now, 5, 1.0);

            assertThat(limiter.tryConsume("tom").allowed()).isTrue();
        }

        @Test
        void should_allowUpToCapacity_beforeRejecting() {
            var limiter = limiterAt(now, 3, 1.0);

            assertThat(limiter.tryConsume("tom").allowed()).isTrue();
            assertThat(limiter.tryConsume("tom").allowed()).isTrue();
            assertThat(limiter.tryConsume("tom").allowed()).isTrue();
            assertThat(limiter.tryConsume("tom").allowed()).isFalse();
        }
    }

    @Nested
    class ExceedingCapacity {

        @Test
        void should_rejectWithPositiveRetryAfter_when_capacityExhausted() {
            var limiter = limiterAt(now, 1, 1.0);
            limiter.tryConsume("tom");

            var result = limiter.tryConsume("tom");

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfter()).isPositive();
        }
    }

    @Nested
    class Refill {

        @Test
        void should_allowAgain_when_enoughTimeElapsedToRefillOneToken() {
            var clock = new MutableClock(now, ZoneOffset.UTC);
            var limiter = new RateLimiter(clock, 1, 1.0);
            limiter.tryConsume("tom");
            assertThat(limiter.tryConsume("tom").allowed()).isFalse();

            clock.advanceBy(Duration.ofSeconds(1));

            assertThat(limiter.tryConsume("tom").allowed()).isTrue();
        }

        @Test
        void should_notExceedCapacity_evenAfterLongIdlePeriod() {
            var clock = new MutableClock(now, ZoneOffset.UTC);
            var limiter = new RateLimiter(clock, 2, 1.0);

            clock.advanceBy(Duration.ofHours(1));

            assertThat(limiter.tryConsume("tom").allowed()).isTrue();
            assertThat(limiter.tryConsume("tom").allowed()).isTrue();
            assertThat(limiter.tryConsume("tom").allowed()).isFalse();
        }
    }

    @Nested
    class PerKeyIsolation {

        @Test
        void should_trackBucketsIndependently_perKey() {
            var limiter = limiterAt(now, 1, 1.0);
            limiter.tryConsume("tom");

            assertThat(limiter.tryConsume("tom").allowed()).isFalse();
            assertThat(limiter.tryConsume("alice").allowed()).isTrue();
        }
    }
}
