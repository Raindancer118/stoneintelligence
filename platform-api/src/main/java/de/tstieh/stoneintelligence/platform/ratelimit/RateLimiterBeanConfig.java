package de.tstieh.stoneintelligence.platform.ratelimit;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bewusst getrennt von {@link RateLimitConfig}: {@code RateLimitConfig} haengt (ueber
 * {@link RateLimitInterceptor}) am {@link RateLimiter}-Bean - waere die Bean-Factory-Methode auf
 * derselben Klasse, entstuende ein Zirkelbezug (RateLimitConfig -> RateLimitInterceptor ->
 * RateLimiter -> RateLimitConfig), den Spring beim Kontextstart mit
 * "Requested bean is currently in creation" ablehnt.
 */
@Configuration
public class RateLimiterBeanConfig {

    @Bean
    public RateLimiter rateLimiter(
        @Value("${stoneintelligence.ratelimit.capacity:60}") int capacity,
        @Value("${stoneintelligence.ratelimit.refill-per-second:1.0}") double refillTokensPerSecond
    ) {
        return new RateLimiter(Clock.systemUTC(), capacity, refillTokensPerSecond);
    }
}
