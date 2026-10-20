package de.tstieh.stoneintelligence.platform.security;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import de.tstieh.stoneintelligence.platform.identity.BlockedSubjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Nach der Token-Pruefung (ADR 0013): ein per SCIM gesperrtes Konto kommt nicht weiter, auch mit
 * noch gueltigem Token. Sonst werden IdP-Gruppen und Anzeigename der Person uebernommen.
 */
public class IdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdentityFilter.class);

    private final IdentitySync sync;
    private final BlockedSubjects blocked;
    private final String groupsClaim;
    private final String nameClaim;

    /** {@code groupsClaim} darf ein Pfad sein ({@code realm_access.roles} bei Keycloak). */
    public IdentityFilter(IdentitySync sync, BlockedSubjects blocked, String groupsClaim, String nameClaim) {
        this.sync = sync;
        this.blocked = blocked;
        this.groupsClaim = groupsClaim;
        this.nameClaim = nameClaim;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            if (blocked.isBlocked(jwt.getName())) {
                SecurityContextHolder.clearContext();
                response.setHeader("WWW-Authenticate",
                    "Bearer error=\"invalid_token\", error_description=\"The account has been deactivated\"");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "account deactivated");
                return;
            }
            try {
                sync.seen(jwt.getName(), displayName(jwt.getToken()), groups(jwt.getToken()));
            } catch (RuntimeException failed) {
                // Die Anfrage selbst haengt nicht daran - die Gruppen werden bei der naechsten erneut uebernommen.
                log.warn("Could not record IdP groups of {}", jwt.getName(), failed);
            }
        }
        chain.doFilter(request, response);
    }

    private String displayName(Jwt token) {
        var name = token.getClaimAsString(nameClaim);
        return name != null && !name.isBlank() ? name : token.getClaimAsString("preferred_username");
    }

    /** {@code null}, wenn der Claim fehlt - dann gilt: in keiner Gruppe. */
    private Collection<String> groups(Jwt token) {
        Object value = token.getClaims();
        for (var part : groupsClaim.split("\\.")) {
            value = value instanceof Map<?, ?> map ? map.get(part) : null;
        }
        return switch (value) {
            case null -> null;
            case Collection<?> collection -> collection.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
            case String single -> List.of(single);
            default -> null;
        };
    }
}
