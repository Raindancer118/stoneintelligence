package de.tstieh.stoneintelligence.platform.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rate-Limit NUR fuer unauthentifizierte Anfragen (nach Remote-Adresse) - der Zweck ist,
 * anonymen Missbrauch (z. B. Scanning gegen Endpunkte, die ohnehin gleich mit 401 abgewiesen
 * wuerden) zu bremsen, nicht legitime Nutzung durch bereits ueber OIDC identifizierte, auditierbare
 * Nutzer zu drosseln. Ein Vault-weiter Sync (Hunderte Notizen, je zwei Requests) ist normale
 * Plugin-Nutzung, kein Angriff - authentifizierte Requests werden deshalb ungebremst durchgelassen
 * (live beobachtet: der pauschale Bucket blockierte den initialen Sync grosser Vaults dauerhaft).
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
        if (authentication != null && authentication.isAuthenticated()) {
            return true;
        }

        var result = limiter.tryConsume(request.getRemoteAddr());
        if (!result.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, result.retryAfter().toSeconds())));
            return false;
        }
        return true;
    }
}
