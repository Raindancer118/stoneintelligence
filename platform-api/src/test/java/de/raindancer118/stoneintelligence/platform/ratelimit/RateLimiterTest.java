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
    class BoundedGrowth {

        @Test
        void should_evictIdleBuckets_when_trackedKeyCountExceedsThreshold() {
            // Ohne Eviction waechst die Map unbegrenzt, wenn ein Client beliebig viele
            // Schluessel erzeugt (z. B. rotierende X-Actor-Werte) - Speicher-DoS.
            var clock = new MutableClock(now, ZoneOffset.UTC);
            var limiter = new RateLimiter(clock, 1, 1.0, 3);

            limiter.tryConsume("key-1");
            limiter.tryConsume("key-2");
            limiter.tryConsume("key-3");
            assertThat(limiter.trackedKeyCount()).isEqualTo(3);

            // Genug Zeit vergeht, dass key-1..3 wieder auf volle Kapazitaet aufgefuellt waeren
            // (also "idle") - erst DANACH wird ein vierter Schluessel angefragt und die
            // beilaeufige Eviction ausgeloest.
            clock.advanceBy(Duration.ofSeconds(10));
            limiter.tryConsume("key-4");

            assertThat(limiter.trackedKeyCount()).isLessThan(4);
        }

        @Test
        void should_notEvictARecentlyConsumedBucket_evenWhenOverThreshold() {
            // Ohne Zeitablauf bleibt "busy-key" unterhalb voller Kapazitaet (ein Token
            // verbraucht) und gilt daher nicht als idle, selbst wenn eine Eviction-Runde laeuft.
            var limiter = new RateLimiter(new MutableClock(now, ZoneOffset.UTC), 5, 1.0, 1);

            limiter.tryConsume("busy-key");
            limiter.tryConsume("other-key-1");
            limiter.tryConsume("other-key-2");

            assertThat(limiter.tryConsume("busy-key").allowed()).isTrue();
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
