package de.raindancer118.stoneintelligence.platform.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentifiziert den KI-Worker an {@code /internal/**} ueber ein gemeinsames Service-Token
 * (ADR 0008). Konstantzeitiger Vergleich; ohne ausreichend langes Token ist der Zugang zu.
 */
public class AiWorkerTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-StoneIntelligence-Worker-Token";
    public static final String ROLE = "AI_WORKER";
    static final int MIN_TOKEN_LENGTH = 32;

    private final byte[] expected;

    public AiWorkerTokenFilter(String token) {
        this.expected = token != null && token.length() >= MIN_TOKEN_LENGTH ? token.getBytes(StandardCharsets.UTF_8) : null;
    }

    public boolean enabled() {
        return expected != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var presented = request.getHeader(HEADER);
        if (expected != null && presented != null
                && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "ai-worker", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
        }
        chain.doFilter(request, response);
    }
}
