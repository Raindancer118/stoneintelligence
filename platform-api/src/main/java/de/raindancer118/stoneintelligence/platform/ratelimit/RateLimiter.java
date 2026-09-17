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

    private static final int DEFAULT_MAX_TRACKED_KEYS = 10_000;

    private final Clock clock;
    private final int capacity;
    private final double refillTokensPerSecond;
    private final int maxTrackedKeys;
    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock, int capacity, double refillTokensPerSecond) {
        this(clock, capacity, refillTokensPerSecond, DEFAULT_MAX_TRACKED_KEYS);
    }

    /**
     * @param maxTrackedKeys ab dieser Anzahl beobachteter Schluessel raeumt {@link #tryConsume}
     *                       beilaeufig "idle" Buckets auf (voll aufgefuellt = unbenutzt) - sonst
     *                       waechst die Map unbegrenzt, wenn ein Client (z. B. ueber rotierende
     *                       X-Actor-Werte) beliebig viele Schluessel erzeugt (Speicher-DoS).
     */
    public RateLimiter(Clock clock, int capacity, double refillTokensPerSecond, int maxTrackedKeys) {
        this.clock = clock;
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.maxTrackedKeys = maxTrackedKeys;
    }

    public RateLimitResult tryConsume(String key) {
        var bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, clock.instant()));
        RateLimitResult result;
        synchronized (bucket) {
            refill(bucket);
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                result = RateLimitResult.permit();
            } else {
                var secondsToWait = (1.0 - bucket.tokens) / refillTokensPerSecond;
                var millisToWait = (long) Math.ceil(secondsToWait * 1000.0);
                result = RateLimitResult.reject(Duration.ofMillis(millisToWait));
            }
        }
        if (buckets.size() > maxTrackedKeys) {
            evictIdleBuckets();
        }
        return result;
    }

    /** Nur fuer Tests: Anzahl aktuell verfolgter Schluessel. */
    int trackedKeyCount() {
        return buckets.size();
    }

    private void evictIdleBuckets() {
        for (var entry : buckets.entrySet()) {
            var bucket = entry.getValue();
            synchronized (bucket) {
                // Erst auffuellen (der Schluessel wurde evtl. lange nicht mehr angefragt, sein
                // Tokenstand ist sonst veraltet), dann pruefen: ein Bucket auf voller Kapazitaet
                // verhaelt sich identisch, ob er hier bleibt oder beim naechsten tryConsume()
                // neu angelegt wird - gefahrlos entfernbar.
                refill(bucket);
                if (bucket.tokens >= capacity) {
                    buckets.remove(entry.getKey(), bucket);
                }
            }
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
