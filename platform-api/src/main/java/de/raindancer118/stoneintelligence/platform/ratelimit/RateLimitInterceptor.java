package de.raindancer118.stoneintelligence.platform.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rate-Limit pro Nutzer (authentifizierter OIDC-Principal seit Phase 3, s.
 * {@code SecurityConfig}) - faellt auf die Remote-Adresse zurueck, wenn (noch) keine
 * Authentifizierung vorliegt (z. B. ein Request, der ohnehin gleich mit 401 abgewiesen wird),
 * statt komplett ungebremst durchzulassen.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var actor = (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : null;
        var key = (actor != null && !actor.isBlank()) ? actor : request.getRemoteAddr();

        var result = limiter.tryConsume(key);
        if (!result.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, result.retryAfter().toSeconds())));
            return false;
        }
        return true;
    }
}
