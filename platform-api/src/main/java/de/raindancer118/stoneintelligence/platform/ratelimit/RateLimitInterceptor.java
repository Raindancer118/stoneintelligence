package de.raindancer118.stoneintelligence.platform.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rate-Limit pro Nutzer (X-Actor-Header, s. {@code TicketController}/{@code NoteController} fuer
 * denselben provisorischen Identitaets-Platzhalter bis Phase 3 OIDC) - faellt auf die
 * Remote-Adresse zurueck, wenn der Header fehlt, statt komplett ungebremst durchzulassen.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var actor = request.getHeader("X-Actor");
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
