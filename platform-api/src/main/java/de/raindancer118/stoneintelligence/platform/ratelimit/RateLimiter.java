package de.raindancer118.stoneintelligence.platform.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Token-Bucket-Rate-Limiter pro Schluessel (API-Key/Nutzer), Plan.md Abschnitt 4.4 ("Rate-Limits
 * serverseitig ... ab Phase 3"). Bewusst selbst implementiert statt einer Drittbibliothek wie
 * Bucket4j - der Algorithmus ist klein, exhaustiv testbar und braucht keine Annahmen ueber eine
 * konkrete, hier ungeprüfte Library-API-Version.
 *
 * <p>Bewusst In-Memory, einzelinstanz-gebunden - dieselbe dokumentierte Grenze wie
 * {@code TicketService}/{@code SyncRoomRegistry}: Mehr-Instanz-Betrieb braucht einen geteilten
 * Zaehler (z. B. Redis), das ist ein spaeterer, eigenstaendiger Schritt.
 */
public final class RateLimiter {

    private final Clock clock;
    private final int capacity;
    private final double refillTokensPerSecond;
    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock, int capacity, double refillTokensPerSecond) {
        this.clock = clock;
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
    }

    public RateLimitResult tryConsume(String key) {
        var bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, clock.instant()));
        synchronized (bucket) {
            refill(bucket);
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return RateLimitResult.permit();
            }
            var secondsToWait = (1.0 - bucket.tokens) / refillTokensPerSecond;
            var millisToWait = (long) Math.ceil(secondsToWait * 1000.0);
            return RateLimitResult.reject(Duration.ofMillis(millisToWait));
        }
    }

    private void refill(TokenBucket bucket) {
        var now = clock.instant();
        var elapsedMillis = Duration.between(bucket.lastRefill, now).toMillis();
        if (elapsedMillis <= 0) {
            return;
        }
        var refilled = (elapsedMillis / 1000.0) * refillTokensPerSecond;
        bucket.tokens = Math.min(capacity, bucket.tokens + refilled);
        bucket.lastRefill = now;
    }

    private static final class TokenBucket {
        private double tokens;
        private java.time.Instant lastRefill;

        TokenBucket(int capacity, java.time.Instant now) {
            this.tokens = capacity;
            this.lastRefill = now;
        }
    }
}
