package de.raindancer118.stoneintelligence.platform.ratelimit;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor interceptor;

    public RateLimitConfig(RateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
    }

    @Bean
    public RateLimiter rateLimiter(
        @Value("${stoneintelligence.ratelimit.capacity:60}") int capacity,
        @Value("${stoneintelligence.ratelimit.refill-per-second:1.0}") double refillTokensPerSecond
    ) {
        return new RateLimiter(Clock.systemUTC(), capacity, refillTokensPerSecond);
    }
}
